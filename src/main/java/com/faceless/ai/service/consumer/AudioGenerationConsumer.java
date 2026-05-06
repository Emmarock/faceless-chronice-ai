package com.faceless.ai.service.consumer;

import com.faceless.ai.entity.Asset;
import com.faceless.ai.entity.AssetType;
import com.faceless.ai.entity.Job;
import com.faceless.ai.model.JobFileDTO;
import com.faceless.ai.model.Scene;
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
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class AudioGenerationConsumer {

    private final ObjectMapper objectMapper;
    private final VideoPipelineService videoPipelineService;
    private final JobService jobService;
    private final S3StorageService s3StorageService;
    private final AssetRepository assetRepository;
    private final PipelineProducer pipelineProducer;

    /**
     * Spring Cloud AWS auto-acks when the listener method returns normally.
     * Throwing leaves the message in the queue for redelivery via the
     * configured visibility timeout (15 min) up to maxReceiveCount (3),
     * after which it lands in the DLQ — both set in Terraform.
     */
    @SqsListener(value = "${chronicleai.queue.audio-generation}",
                 messageVisibilitySeconds = "900")
    public void consume(String payload) throws Exception {
        log.info("AudioGenerationConsumer received job");

        JobFileDTO jobFileDTO = objectMapper.readValue(payload, JobFileDTO.class);
        Job job = jobService.getJob(jobFileDTO.getJobId());
        String jobId = job.getId().toString().replace("-", "");
        String createdBy = jobFileDTO.getCreatedBy();

        videoPipelineService.ensureOutputDirs();

        // allSegments() returns: title (-2), hook (-1), scenes 1…N, closing (1000).
        // The returned Scene objects are the actual instances stored inside the
        // VideoScript, so setting voiceFile on them persists into the DTO JSON
        // that is forwarded to the next stage.
        List<Asset> newAssets = new ArrayList<>();
        for (Scene scene : jobFileDTO.getVideoScript().allSegments()) {
            int sceneId = scene.getScene();
            String sceneKey = String.valueOf(sceneId);

            // Idempotency: skip if voice already generated and present in S3.
            // We still probe duration from S3 so the image stage can scale
            // its image count for resumed jobs that didn't have it set.
            Optional<Asset> existing = assetRepository.findFirstByJobIdAndAssetTypeAndMetadata(
                    job.getId(), AssetType.VOICE, sceneKey);
            if (existing.isPresent() && s3StorageService.exists(existing.get().getUrl())) {
                scene.setVoiceFile(existing.get().getUrl());
                if (scene.getDurationSeconds() == null) {
                    scene.setDurationSeconds(probeDurationSilently(existing.get().getUrl()));
                }
                log.info("Voice already exists for job {} segment {} — skipping (duration={}s)",
                        jobId, sceneId, scene.getDurationSeconds());
                continue;
            }

            Path audioPath = videoPipelineService.generateVoice(scene.getText(), jobId, sceneId);

            // Probe locally before upload so downstream stages can size
            // image count per scene without re-downloading from S3.
            try {
                scene.setDurationSeconds(videoPipelineService.getAudioDurationSeconds(audioPath));
            } catch (Exception e) {
                log.warn("Could not probe duration for job {} segment {}: {}", jobId, sceneId, e.getMessage());
            }

            String filename = audioPath.getFileName().toString();
            String ext = filename.substring(filename.lastIndexOf('.'));
            String s3Key = "jobs/" + jobId + "/voices/segment_" + sceneId + ext;
            String s3Url = s3StorageService.upload(audioPath, s3Key);

            // Persist URL back onto the scene object so the next stage has it
            scene.setVoiceFile(s3Url);

            newAssets.add(Asset.builder()
                    .assetType(AssetType.VOICE)
                    .url(s3Url)
                    .metadata(sceneKey)
                    .build());
        }

        if (!newAssets.isEmpty()) {
            jobService.saveAssets(job, newAssets, createdBy);
        }

        log.info("Audio generation complete for job {}. Forwarding to image-generation queue.", jobId);
        pipelineProducer.send(PipelineStage.IMAGE_GENERATION, objectMapper.writeValueAsString(jobFileDTO));
    }

    /**
     * Probes a voice file already in S3 for its duration. Used only on the
     * resume path where the voice was generated previously and the local
     * probe in the original run never persisted the duration on the script.
     * Returns {@code null} on any failure rather than aborting the pipeline.
     */
    private Double probeDurationSilently(String s3Url) {
        try {
            int dot = s3Url.lastIndexOf('.');
            String ext = dot >= 0 ? s3Url.substring(dot) : ".mp3";
            Path local = s3StorageService.downloadToTemp(s3Url, ext);
            return videoPipelineService.getAudioDurationSeconds(local);
        } catch (Exception e) {
            log.debug("Duration re-probe failed for {}: {}", s3Url, e.getMessage());
            return null;
        }
    }
}
