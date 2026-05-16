package com.faceless.ai.service;

import com.faceless.ai.entity.*;
import com.faceless.ai.model.GenerateJobRequest;
import com.faceless.ai.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class JobService {

    private final JobRepository jobRepository;
    private final JobStageRepository jobStageRepository;
    private final ScriptRepository scriptRepository;
    private final AssetRepository assetRepository;
    private final VideoRepository videoRepository;
    private final SocialConnectionRepository socialConnectionRepository;

    /**
     * Step 1: Create a new job and enqueue for processing
     */
    @Transactional
    public Job createJob(GenerateJobRequest request, String createdBy) {
        SocialConnection connection = resolveConnection(request.getSocialConnectionId(), createdBy);

        Job job = Job.builder()
                .question(request.getQuestion())
                .progress(0)
                .style(request.getStyle())
                .durationSeconds(request.getDurationSeconds())
                .videoFormat(request.getVideoFormat() != null
                        ? request.getVideoFormat()
                        : com.faceless.ai.model.VideoFormat.VIDEO)
                .status(Status.QUEUED)
                .socialConnection(connection)
                .createdBy(createdBy)
                .lastModifiedBy(createdBy)
                .createdOn(Instant.now())
                .lastModifiedOn(Instant.now())
                .build();

        job= jobRepository.save(job);

        // Initialize job stages
        initializeJobStages(job.getId(), createdBy);

        return job;
    }

    /**
     * Step 2: Initialize the pipeline stages for the job
     */
    private void initializeJobStages(UUID jobId, String createdBy) {
        List<String> stages = List.of(
                "RESEARCH",
                "SCRIPT_GENERATION",
                "ASSET_GENERATION",
                "VOICE_GENERATION",
                "VIDEO_RENDERING",
                "COMPLETED"
        );

        for (String stageName : stages) {
            JobStage stage = JobStage.builder()
                    .id(UUID.randomUUID())
                    .jobId(jobId)
                    .stageName(stageName)
                    .status(Status.PENDING)
                    .createdBy(createdBy)
                    .lastModifiedBy(createdBy)
                    .createdOn(Instant.now())
                    .lastModifiedOn(Instant.now())
                    .build();
            jobStageRepository.save(stage);
        }
    }

    /**
     * Step 3: Save generated script
     */
    @Transactional
    public void saveScript(Job job, String scriptContent, String createdBy) {
        Script script = Script.builder()
                .id(UUID.randomUUID())
                .jobId(job.getId())
                .content(scriptContent)
                .sceneCount(countScenes(scriptContent))
                .status(Status.COMPLETED)
                .createdBy(createdBy)
                .lastModifiedBy(createdBy)
                .createdOn(Instant.now())
                .lastModifiedOn(Instant.now())
                .build();

        scriptRepository.save(script);
        updateJobProgress(job, "SCRIPT_GENERATION", 20);
    }

    private int countScenes(String scriptContent) {
        // For now, simple split by "\n\n"
        return scriptContent.split("\n\n").length;
    }

    /**
     * Step 4: Save generated assets
     */
    @Transactional
    public void saveAssets(Job job, List<Asset> assets, String createdBy) {
        for (Asset asset : assets) {
            // Upsert: if a stale record exists for the same job/type/segment, update it
            // rather than creating a duplicate (handles S3-loss + regeneration scenarios).
            assetRepository
                    .findFirstByJobIdAndAssetTypeAndMetadata(job.getId(), asset.getAssetType(), asset.getMetadata())
                    .ifPresentOrElse(
                            existing -> {
                                existing.setUrl(asset.getUrl());
                                existing.setStatus(Status.COMPLETED);
                                existing.setLastModifiedBy(createdBy);
                                existing.setLastModifiedOn(Instant.now());
                                assetRepository.save(existing);
                            },
                            () -> {
                                asset.setId(UUID.randomUUID());
                                asset.setJobId(job.getId());
                                asset.setStatus(Status.COMPLETED);
                                asset.setCreatedBy(createdBy);
                                asset.setLastModifiedBy(createdBy);
                                asset.setCreatedOn(Instant.now());
                                asset.setLastModifiedOn(Instant.now());
                                assetRepository.save(asset);
                            });
        }
        updateJobProgress(job, "ASSET_GENERATION", 50);
    }

    /**
     * Step 5: Save final video
     */
    @Transactional
    public Video saveVideo(Job job, String title, String description, String storageUrl, int duration, String createdBy) {
        Video video = Video.builder()
                .id(UUID.randomUUID())
                .jobId(job.getId())
                .title(title)
                .description(description)
                .durationSeconds(duration)
                .storageUrl(storageUrl)
                .status(Status.COMPLETED)
                .createdBy(createdBy)
                .lastModifiedBy(createdBy)
                .createdOn(Instant.now())
                .lastModifiedOn(Instant.now())
                .build();

        videoRepository.save(video);

        // Final video persisted ⇒ user-visible work is done. updateJobProgress
        // flips job.status to COMPLETED when progress hits 100, so the list
        // page stops showing "PROCESSING · 90%" indefinitely (publishing to
        // YouTube etc. is a separate, optional follow-up stage).
        updateJobProgress(job, "VIDEO_RENDERING", 100);

        return video;
    }

    /**
     * Step 6: Update job stage and progress
     */
    @Transactional
    public void updateJobProgress(Job job, String stageName, int progressPercent) {
        // Update stage
        JobStage stage = jobStageRepository.findByJobIdAndStageName(job.getId(), stageName)
                .orElseThrow(() -> new RuntimeException("Stage not found"));

        stage.setStatus(Status.COMPLETED);
        stage.setFinishedAt(Instant.now());
        stage.setLastModifiedOn(Instant.now());
        jobStageRepository.save(stage);

        // Update job

        job.setProgress(progressPercent);
        job.setStatus(progressPercent >= 100 ? Status.COMPLETED : Status.PROCESSING);
        job.setLastModifiedOn(Instant.now());
        jobRepository.save(job);
    }

    private SocialConnection resolveConnection(UUID socialConnectionId, String createdBy) {
        if (socialConnectionId == null) return null;
        SocialConnection connection = socialConnectionRepository.findById(socialConnectionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Social connection not found: " + socialConnectionId));
        if (!createdBy.equals(connection.getUserId())) {
            throw new IllegalArgumentException(
                    "Social connection " + socialConnectionId + " does not belong to user " + createdBy);
        }
        return connection;
    }

    public Job getJob(UUID jobId) {
        return jobRepository.findById(jobId).orElseThrow(() -> new RuntimeException("Job not found"));
    }

    public List<Job> listJobsByUser(String createdBy) {
        return jobRepository.findByCreatedByOrderByCreatedOnDesc(createdBy);
    }

    public Script getScript(UUID jobId) {
        return scriptRepository.findByJobId(jobId)
                .orElseThrow(() -> new RuntimeException("Script not found for job " + jobId));
    }

    public java.util.Optional<Script> findScript(UUID jobId) {
        return scriptRepository.findByJobId(jobId);
    }

    public List<Asset> getAssets(UUID jobId) {
        return assetRepository.findByJobId(jobId);
    }

    /**
     * Overwrites the stored script content with the fully-enriched VideoScript JSON
     * (after voiceFile / imageFile / videoFile have been written back to each segment).
     * Called by VideoCombineConsumer once the final video is assembled.
     */
    @Transactional
    public void updateScript(Job job, String scriptContent) {
        scriptRepository.findByJobId(job.getId()).ifPresent(script -> {
            script.setContent(scriptContent);
            script.setLastModifiedOn(Instant.now());
            scriptRepository.save(script);
        });
    }
}