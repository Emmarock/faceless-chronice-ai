package com.faceless.ai.service.consumer;

import com.faceless.ai.entity.Asset;
import com.faceless.ai.entity.AssetType;
import com.faceless.ai.entity.Job;
import com.faceless.ai.entity.Video;
import com.faceless.ai.model.JobFileDTO;
import com.faceless.ai.model.MediaMode;
import com.faceless.ai.model.Scene;
import com.faceless.ai.model.VideoScript;
import com.faceless.ai.repository.AssetRepository;
import com.faceless.ai.service.JobService;
import com.faceless.ai.service.S3StorageService;
import com.faceless.ai.service.VideoPipelineService;
import com.faceless.ai.service.producer.PipelineProducer;
import com.faceless.ai.service.producer.PipelineStage;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class VideoCombineConsumer {

    private final ObjectMapper objectMapper;
    private final AssetRepository assetRepository;
    private final JobService jobService;
    private final S3StorageService s3StorageService;
    private final VideoPipelineService videoPipelineService;
    private final PipelineProducer pipelineProducer;

    /**
     * ffmpeg concat across many scenes can run for several minutes. The 15 min
     * visibility window matches the queue's Terraform default; throwing leaves
     * the message for redelivery up to maxReceiveCount before it lands in DLQ.
     */
    @SqsListener(value = "${chronicleai.queue.video-combine}",
                 messageVisibilitySeconds = "900",
                 maxConcurrentMessages = "1")
    public void consume(String payload) throws Exception {
        log.info("VideoCombineConsumer received job");

        JobFileDTO jobFileDTO = objectMapper.readValue(payload, JobFileDTO.class);
        Job job = jobService.getJob(jobFileDTO.getJobId());
        String jobId = job.getId().toString().replace("-", "");
        String createdBy = jobFileDTO.getCreatedBy();
        VideoScript videoScript = jobFileDTO.getVideoScript();
        String title = videoScript.getTitle();
        String hook  = videoScript.getHook();

        videoPipelineService.ensureOutputDirs();

        // Load voice assets (metadata = sceneId), image assets (metadata = sceneId_index),
        // source-video assets (metadata = sceneId, video-mode scenes only), and
        // previously-rendered clip assets (metadata = sceneId). Existing clips whose
        // S3 object still exists are reused — only invalidated scenes (e.g. after a
        // user edits an image) are re-rendered, then the final video is re-concatenated.
        Map<Integer, Asset> voiceBySegment = loadVoicesBySegment(job.getId());
        Map<Integer, List<Asset>> imagesBySegment = loadImagesBySegment(job.getId());
        Map<Integer, Asset> sourceVideoBySegment = loadSourceVideosBySegment(job.getId());
        Map<Integer, Asset> clipBySegment = loadClipsBySegment(job.getId());

        if (voiceBySegment.isEmpty() || (imagesBySegment.isEmpty() && sourceVideoBySegment.isEmpty())) {
            throw new IllegalStateException(
                    "Missing assets for job " + jobId + ". voices=" + voiceBySegment.size()
                    + " imageSegments=" + imagesBySegment.size()
                    + " sourceVideoSegments=" + sourceVideoBySegment.size());
        }

        // Guard against stale DB records pointing to S3 keys that no longer exist
        // (e.g. LocalStack restarted). If any asset is missing from S3, wipe the
        // stale DB records and restart the pipeline from the audio stage.
        boolean voiceStale = voiceBySegment.values().stream().anyMatch(a -> !s3StorageService.exists(a.getUrl()));
        boolean imageStale = imagesBySegment.values().stream()
                .flatMap(List::stream)
                .anyMatch(a -> !s3StorageService.exists(a.getUrl()));
        boolean sourceVideoStale = sourceVideoBySegment.values().stream()
                .anyMatch(a -> !s3StorageService.exists(a.getUrl()));
        if (voiceStale || imageStale || sourceVideoStale) {
            log.warn("Stale S3 assets detected for job {} (voiceStale={}, imageStale={}, sourceVideoStale={}). "
                    + "Clearing DB records and re-queuing to audio-generation queue.",
                    jobId, voiceStale, imageStale, sourceVideoStale);
            assetRepository.deleteAll(assetRepository.findByJobIdAndAssetType(job.getId(), AssetType.VOICE));
            assetRepository.deleteAll(assetRepository.findByJobIdAndAssetType(job.getId(), AssetType.IMAGE));
            assetRepository.deleteAll(assetRepository.findByJobIdAndAssetType(job.getId(), AssetType.SOURCE_VIDEO));
            pipelineProducer.send(PipelineStage.AUDIO_GENERATION, payload);
            return;
        }

        List<String> clipPaths = new ArrayList<>();

        // Assemble one clip per segment in playback order (-2, -1, 1…N, 1000)
        for (int segmentId : voiceBySegment.keySet().stream().sorted().toList()) {
            Asset voiceAsset = voiceBySegment.get(segmentId);
            Scene segment = videoScript.findSegmentById(segmentId);
            MediaMode mode = segment != null && segment.getMediaMode() != null
                    ? segment.getMediaMode()
                    : MediaMode.IMAGES;

            if (voiceAsset == null) {
                throw new IllegalStateException(
                        "Missing voice asset for segment " + segmentId + " in job " + jobId);
            }

            Asset existingClip = clipBySegment.get(segmentId);
            String clipUrl;
            Path clipPath;
            if (existingClip != null && s3StorageService.exists(existingClip.getUrl())) {
                // Reuse cached clip — image / source-video edits to *other* scenes
                // shouldn't trigger an ffmpeg rebuild here. The mutation endpoints
                // delete the affected scene's clip row, so this branch only fires
                // for untouched scenes.
                clipUrl = existingClip.getUrl();
                clipPath = s3StorageService.downloadToTemp(clipUrl, ".mp4");
                log.info("Segment {} clip reused from cache: {}", segmentId, clipUrl);
            } else {
                String audioUrl = voiceAsset.getUrl();
                String audioExt = audioUrl.substring(audioUrl.lastIndexOf('.'));
                Path audioPath = s3StorageService.downloadToTemp(audioUrl, audioExt);
                double durationSec = videoPipelineService.getAudioDurationSeconds(audioPath);

                if (mode == MediaMode.VIDEO_CLIP) {
                    Asset sourceVideoAsset = sourceVideoBySegment.get(segmentId);
                    if (sourceVideoAsset == null) {
                        throw new IllegalStateException(
                                "Segment " + segmentId + " is in VIDEO_CLIP mode but has no SOURCE_VIDEO asset");
                    }
                    Path sourceVideoPath = s3StorageService.downloadToTemp(sourceVideoAsset.getUrl(), ".mp4");
                    clipPath = videoPipelineService.assembleSceneFromVideo(
                            sourceVideoPath, audioPath, jobId, segmentId, durationSec);
                } else {
                    List<Asset> imageAssets = imagesBySegment.get(segmentId);
                    if (imageAssets == null || imageAssets.isEmpty()) {
                        throw new IllegalStateException(
                                "Segment " + segmentId + " is in IMAGES mode but has no IMAGE assets");
                    }
                    List<Path> imagePaths = new ArrayList<>();
                    for (Asset imgAsset : imageAssets) {
                        String imgUrl = imgAsset.getUrl();
                        int imgDot = imgUrl.lastIndexOf('.');
                        String imgExt = imgDot >= 0 ? imgUrl.substring(imgDot) : ".jpg";
                        imagePaths.add(s3StorageService.downloadToTemp(imgUrl, imgExt));
                    }
                    clipPath = videoPipelineService.assembleScene(
                            imagePaths, audioPath, jobId, segmentId, durationSec);
                }

                String clipKey = "jobs/" + jobId + "/clips/segment_" + segmentId + ".mp4";
                clipUrl = s3StorageService.upload(clipPath, clipKey);

                Asset clipAsset = Asset.builder()
                        .assetType(AssetType.VIDEO_CLIP)
                        .url(clipUrl)
                        .metadata(String.valueOf(segmentId))
                        .build();
                jobService.saveAssets(job, List.of(clipAsset), createdBy);
                log.info("Segment {} clip assembled in {} mode: {}", segmentId, mode, clipPath);
            }

            // Write clip URL back to the corresponding segment in the VideoScript
            // (segment was already resolved above for the mode dispatch).
            if (segment != null) {
                segment.setVideoFile(clipUrl);
            }

            clipPaths.add(clipPath.toString());
        }

        // Concatenate all clips into the final video
        Path finalVideoPath = videoPipelineService.concatenateScenes(clipPaths, jobId);
        String finalKey = "jobs/" + jobId + "/final/final_video.mp4";
        String finalVideoUrl = s3StorageService.upload(finalVideoPath, finalKey);

        int totalDurationSec = (int) videoPipelineService.getTotalDurationSeconds(clipPaths);
        Video savedVideo = jobService.saveVideo(
                job,
                title != null ? title : "Faceless Chronicle " + jobId,
                hook  != null ? hook  : "",
                finalVideoUrl,
                totalDurationSec,
                createdBy);

        // Persist the fully-enriched VideoScript (with voiceFile, imageFile, videoFile
        // set on every segment) back to the DB so the resume API can reconstruct it.
        jobService.updateScript(job, objectMapper.writeValueAsString(videoScript));

        // Publish AFTER both transactions above have committed — sending inside
        // saveVideo() caused the YouTubeUploadConsumer to receive the message before
        // the video row was visible in DB, resulting in "Video not found".
        pipelineProducer.send(PipelineStage.YOUTUBE_UPLOAD, savedVideo.getId().toString());

        log.info("Video combine complete for job {}. Final video: {}", jobId, finalVideoUrl);
    }

    private Map<Integer, Asset> loadVoicesBySegment(java.util.UUID jobId) {
        return assetRepository.findByJobIdAndAssetType(jobId, AssetType.VOICE).stream()
                .filter(a -> a.getMetadata() != null)
                .collect(Collectors.toMap(
                        a -> Integer.parseInt(a.getMetadata()),
                        a -> a));
    }

    private Map<Integer, Asset> loadSourceVideosBySegment(java.util.UUID jobId) {
        return assetRepository.findByJobIdAndAssetType(jobId, AssetType.SOURCE_VIDEO).stream()
                .filter(a -> a.getMetadata() != null)
                .collect(Collectors.toMap(
                        a -> Integer.parseInt(a.getMetadata()),
                        a -> a,
                        // A scene only has one source video; defensive merge keeps
                        // the most recent row if the table somehow has dupes.
                        (older, newer) -> newer));
    }

    private Map<Integer, Asset> loadClipsBySegment(java.util.UUID jobId) {
        return assetRepository.findByJobIdAndAssetType(jobId, AssetType.VIDEO_CLIP).stream()
                .filter(a -> a.getMetadata() != null)
                .collect(Collectors.toMap(
                        a -> Integer.parseInt(a.getMetadata()),
                        a -> a,
                        (older, newer) -> newer));
    }

    // Image metadata format is "{sceneId}_{index}" (e.g. "1_0", "1_1", "-2_0").
    // Group by the sceneId part, sorted by the index part so images within a
    // scene play in generation order (the DB query has no ORDER BY, so without
    // an explicit sort the order is undefined).
    private Map<Integer, List<Asset>> loadImagesBySegment(java.util.UUID jobId) {
        return assetRepository.findByJobIdAndAssetType(jobId, AssetType.IMAGE).stream()
                .filter(a -> a.getMetadata() != null)
                .sorted(java.util.Comparator.comparingInt(VideoCombineConsumer::imageIndex))
                .collect(Collectors.groupingBy(
                        VideoCombineConsumer::imageSceneId,
                        Collectors.toList()));
    }

    private static int imageSceneId(Asset asset) {
        String meta = asset.getMetadata();
        int sep = meta.indexOf('_');
        // Handle both new "sceneId_index" and legacy "sceneId" formats
        return Integer.parseInt(sep >= 0 ? meta.substring(0, sep) : meta);
    }

    private static int imageIndex(Asset asset) {
        String meta = asset.getMetadata();
        int sep = meta.indexOf('_');
        return sep >= 0 ? Integer.parseInt(meta.substring(sep + 1)) : 0;
    }
}