package com.faceless.ai.service.consumer;

import com.faceless.ai.entity.Asset;
import com.faceless.ai.entity.AssetType;
import com.faceless.ai.entity.Job;
import com.faceless.ai.model.JobFileDTO;
import com.faceless.ai.model.MediaMode;
import com.faceless.ai.model.Scene;
import com.faceless.ai.repository.AssetRepository;
import com.faceless.ai.service.ChatGPTService;
import com.faceless.ai.service.JobService;
import com.faceless.ai.service.S3StorageService;
import com.faceless.ai.service.image.ImageGenerationService;
import com.faceless.ai.service.producer.PipelineProducer;
import com.faceless.ai.service.producer.PipelineStage;
import com.faceless.ai.service.video.VideoSourceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class ImageGenerationConsumer {

    /**
     * Target on-screen time per image. With a typical 30s scene you get
     * ~7 distinct shots — enough to feel like real B-roll instead of two
     * static frames. Tune by overriding the matching {@code chronicleai.image.*}
     * properties.
     */
    private static final double DEFAULT_SECONDS_PER_IMAGE = 4.0;
    /** Floor so even very short scenes (a 2s title card) still get >1 image. */
    private static final int DEFAULT_MIN_IMAGES_PER_SCENE = 3;
    /** Ceiling so very long scenes don't blow up Pexels quota or ffmpeg time. */
    private static final int DEFAULT_MAX_IMAGES_PER_SCENE = 10;
    /** Used when a scene has no probed duration (legacy / failed probe). */
    private static final int FALLBACK_IMAGES_PER_SCENE = 5;

    private final ObjectMapper objectMapper;
    private final ImageGenerationService imageGenerationService;
    private final ChatGPTService chatGPTService;
    private final JobService jobService;
    private final S3StorageService s3StorageService;
    private final AssetRepository assetRepository;
    private final PipelineProducer pipelineProducer;
    /**
     * Optional — only present when a {@link VideoSourceService} bean is wired
     * (e.g. {@code chronicleai.video.provider=pexels}, the default). When
     * absent, scenes whose {@code mediaMode == VIDEO_CLIP} fail loudly at
     * dispatch time so misconfiguration is obvious instead of silently
     * falling back to images.
     */
    @Autowired(required = false)
    private VideoSourceService videoSourceService;

    @Value("${chronicleai.image.seconds-per-image:" + DEFAULT_SECONDS_PER_IMAGE + "}")
    private double secondsPerImage;
    @Value("${chronicleai.image.min-per-scene:" + DEFAULT_MIN_IMAGES_PER_SCENE + "}")
    private int minImagesPerScene;
    @Value("${chronicleai.image.max-per-scene:" + DEFAULT_MAX_IMAGES_PER_SCENE + "}")
    private int maxImagesPerScene;

    /**
     * Visibility timeout matches the audio stage (15 min). Image generation
     * for a scene with ~10 prompts can take several minutes against external
     * providers, so giving the listener a longer window prevents redelivery
     * mid-flight. Concurrency lets multiple jobs progress in parallel.
     */
    @SqsListener(value = "${chronicleai.queue.image-generation}",
                 messageVisibilitySeconds = "900",
                 maxConcurrentMessages = "10")
    public void consume(String payload) throws Exception {
        log.info("ImageGenerationConsumer received job");

        JobFileDTO jobFileDTO = objectMapper.readValue(payload, JobFileDTO.class);
        Job job = jobService.getJob(jobFileDTO.getJobId());
        String jobId = job.getId().toString().replace("-", "");
        String createdBy = jobFileDTO.getCreatedBy();

        List<Asset> newAssets = new ArrayList<>();
        for (Scene scene : jobFileDTO.getVideoScript().allSegments()) {
            int sceneId = scene.getScene();
            MediaMode mode = scene.getMediaMode() != null ? scene.getMediaMode() : MediaMode.IMAGES;

            if (mode == MediaMode.VIDEO_CLIP) {
                generateSourceVideoForScene(scene, job, jobId, newAssets);
            } else {
                generateImagesForScene(scene, job, jobId, newAssets);
            }
        }

        if (!newAssets.isEmpty()) {
            jobService.saveAssets(job, newAssets, createdBy);
        }

        log.info("Image generation complete for job {}. Forwarding to video-combine queue.", jobId);
        pipelineProducer.send(PipelineStage.VIDEO_COMBINE, objectMapper.writeValueAsString(jobFileDTO));
    }

    /**
     * Image-mode path (legacy default). Generates N stills from per-image
     * ChatGPT prompts and persists each as an {@link AssetType#IMAGE} row
     * keyed by {@code sceneId_index}.
     */
    private void generateImagesForScene(Scene scene, Job job, String jobId, List<Asset> newAssets) throws Exception {
        int sceneId = scene.getScene();
        // Idempotency: the first image key (sceneId_0) is a proxy for all images.
        // If it exists in DB and in S3, skip the entire scene.
        String firstKey = sceneId + "_0";
        Optional<Asset> firstExisting = assetRepository.findFirstByJobIdAndAssetTypeAndMetadata(
                job.getId(), AssetType.IMAGE, firstKey);
        if (firstExisting.isPresent() && s3StorageService.exists(firstExisting.get().getUrl())) {
            log.info("Images already exist for job {} segment {} — skipping", jobId, sceneId);
            return;
        }

        int count = imagesNeededFor(scene);
        List<String> prompts = chatGPTService.generateImagePrompts(
                scene.getText() == null ? "" : scene.getText(),
                count,
                imageGenerationService.preferredPromptStyle());
        log.info("Requesting {} images for job {} segment {} (duration={}s, distinct prompts={})",
                count, jobId, sceneId, scene.getDurationSeconds(), prompts.size());
        List<Path> imagePaths = imageGenerationService.generateImagesForPrompts(prompts, jobId, sceneId);

        for (int i = 0; i < imagePaths.size(); i++) {
            Path imagePath = imagePaths.get(i);
            String filename = imagePath.getFileName().toString();
            int dot = filename.lastIndexOf('.');
            String extension = dot >= 0 ? filename.substring(dot) : "";
            String s3Key = "jobs/" + jobId + "/images/segment_" + sceneId + "_" + i + extension;
            String s3Url = s3StorageService.upload(imagePath, s3Key);

            newAssets.add(Asset.builder()
                    .assetType(AssetType.IMAGE)
                    .url(s3Url)
                    .metadata(sceneId + "_" + i)
                    .build());
        }
    }

    /**
     * Video-mode path. Generates a single search query (image-prompt helper
     * with count=1) and asks the {@link VideoSourceService} for one source
     * clip per scene. Persisted as {@link AssetType#SOURCE_VIDEO} keyed by
     * {@code sceneId} (no per-shot index — a scene gets one source clip).
     */
    private void generateSourceVideoForScene(Scene scene, Job job, String jobId, List<Asset> newAssets) throws Exception {
        int sceneId = scene.getScene();
        if (videoSourceService == null) {
            throw new IllegalStateException(
                    "Scene " + sceneId + " is in VIDEO_CLIP mode but no VideoSourceService is wired. "
                            + "Check chronicleai.video.provider in application.yaml.");
        }

        String key = String.valueOf(sceneId);
        Optional<Asset> existing = assetRepository.findFirstByJobIdAndAssetTypeAndMetadata(
                job.getId(), AssetType.SOURCE_VIDEO, key);
        if (existing.isPresent() && s3StorageService.exists(existing.get().getUrl())) {
            log.info("Source video already exists for job {} segment {} — skipping", jobId, sceneId);
            return;
        }

        // Reuse the image-prompt helper but ask for exactly one prompt — the
        // video provider only takes one query per scene. preferredPromptStyle
        // selects search-query vs descriptive based on the video provider.
        List<String> prompts = chatGPTService.generateImagePrompts(
                scene.getText() == null ? "" : scene.getText(),
                1,
                videoSourceService.preferredPromptStyle());
        String prompt = prompts.isEmpty() ? scene.getText() : prompts.get(0);
        log.info("Requesting source video for job {} segment {} with prompt='{}'", jobId, sceneId, prompt);

        Path videoPath = videoSourceService.generateVideo(prompt, jobId, sceneId);
        String s3Key = "jobs/" + jobId + "/source_videos/segment_" + sceneId + ".mp4";
        String s3Url = s3StorageService.upload(videoPath, s3Key);

        newAssets.add(Asset.builder()
                .assetType(AssetType.SOURCE_VIDEO)
                .url(s3Url)
                .metadata(key)
                .build());
    }

    /**
     * Choose how many images to generate for a scene. We aim for roughly one
     * image per {@code secondsPerImage} of narration so each shot has time
     * to breathe but the visuals never sit still for half a minute. Falls
     * back to a sensible constant if the audio probe didn't populate
     * {@link Scene#getDurationSeconds()} (e.g. legacy jobs).
     */
    private int imagesNeededFor(Scene scene) {
        Double duration = scene.getDurationSeconds();
        int target = duration != null && duration > 0
                ? (int) Math.ceil(duration / secondsPerImage)
                : FALLBACK_IMAGES_PER_SCENE;
        return Math.max(minImagesPerScene, Math.min(maxImagesPerScene, target));
    }
}