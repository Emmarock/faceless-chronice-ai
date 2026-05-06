package com.faceless.ai.service;

import com.faceless.ai.entity.Asset;
import com.faceless.ai.entity.AssetType;
import com.faceless.ai.entity.Job;
import com.faceless.ai.repository.AssetRepository;
import com.faceless.ai.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/**
 * CRUD for the per-scene SOURCE_VIDEO asset (one clip per video-mode scene).
 * Mirrors {@link ImageAssetService} but without the index/reindex bookkeeping
 * — a scene has at most one source clip, keyed by {@code metadata = sceneId}.
 *
 * <p>Every mutation:
 * <ol>
 *   <li>updates the SOURCE_VIDEO row + S3 object so the next render uses the
 *       new clip;</li>
 *   <li>invalidates the affected scene's cached VIDEO_CLIP and the job's
 *       final Video, so a subsequent {@code POST /resume} re-renders only
 *       the changed scene + the final concat.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SourceVideoAssetService {

    private final AssetRepository assetRepository;
    private final VideoRepository videoRepository;
    private final S3StorageService s3StorageService;
    private final JobService jobService;

    /**
     * Upload a replacement source clip for {@code sceneId}. Creates the
     * SOURCE_VIDEO row if absent (so video-mode scenes can be initialised by
     * upload as well as by the auto-fetch path), or overwrites the existing
     * row's URL and deletes the old S3 object.
     */
    @Transactional
    public void replaceSourceVideo(UUID jobId, int sceneId, MultipartFile file, String requestedBy)
            throws IOException {
        Job job = jobService.getJob(jobId);
        Asset existing = findSourceVideo(jobId, sceneId);

        String newUrl = uploadUserVideo(jobId, sceneId, file);
        if (existing == null) {
            Asset asset = Asset.builder()
                    .id(UUID.randomUUID())
                    .jobId(jobId)
                    .assetType(AssetType.SOURCE_VIDEO)
                    .url(newUrl)
                    .metadata(String.valueOf(sceneId))
                    .createdBy(requestedBy)
                    .lastModifiedBy(requestedBy)
                    .createdOn(Instant.now())
                    .lastModifiedOn(Instant.now())
                    .build();
            assetRepository.save(asset);
            log.info("Created SOURCE_VIDEO for scene {} of job {}", sceneId, jobId);
        } else {
            s3StorageService.delete(existing.getUrl());
            existing.setUrl(newUrl);
            existing.setLastModifiedBy(requestedBy);
            existing.setLastModifiedOn(Instant.now());
            assetRepository.save(existing);
            log.info("Replaced SOURCE_VIDEO for scene {} of job {}", sceneId, jobId);
        }

        invalidateScene(job, sceneId);
    }

    /**
     * Remove the source video for {@code sceneId}. The cached clip + final
     * video are invalidated; a subsequent {@code /resume} will re-fetch the
     * source via {@code VideoSourceService} and re-render.
     */
    @Transactional
    public void deleteSourceVideo(UUID jobId, int sceneId) {
        Job job = jobService.getJob(jobId);
        Asset existing = findSourceVideo(jobId, sceneId);
        if (existing == null) {
            throw new IllegalArgumentException(
                    "No source video for scene " + sceneId + " of job " + jobId);
        }
        s3StorageService.delete(existing.getUrl());
        assetRepository.delete(existing);
        invalidateScene(job, sceneId);
        log.info("Deleted SOURCE_VIDEO for scene {} of job {}", sceneId, jobId);
    }

    public Asset requireSourceVideo(UUID jobId, int sceneId) {
        Asset asset = findSourceVideo(jobId, sceneId);
        if (asset == null) {
            throw new IllegalArgumentException(
                    "No source video for scene " + sceneId + " of job " + jobId);
        }
        return asset;
    }

    private Asset findSourceVideo(UUID jobId, int sceneId) {
        return assetRepository
                .findFirstByJobIdAndAssetTypeAndMetadata(jobId, AssetType.SOURCE_VIDEO, String.valueOf(sceneId))
                .orElse(null);
    }

    /**
     * Drop the cached scene clip and the final video so the next pipeline
     * resume rebuilds only the changed scene + the final concat.
     */
    private void invalidateScene(Job job, int sceneId) {
        assetRepository.findFirstByJobIdAndAssetTypeAndMetadata(
                        job.getId(), AssetType.VIDEO_CLIP, String.valueOf(sceneId))
                .ifPresent(clip -> {
                    s3StorageService.delete(clip.getUrl());
                    assetRepository.delete(clip);
                });

        videoRepository.findByJobId(job.getId()).ifPresent(video -> {
            s3StorageService.delete(video.getStorageUrl());
            videoRepository.delete(video);
        });
    }

    private String uploadUserVideo(UUID jobId, int sceneId, MultipartFile file) throws IOException {
        String ext = guessExtension(file);
        String jobIdFlat = jobId.toString().replace("-", "");
        String key = "jobs/" + jobIdFlat + "/source_videos/segment_" + sceneId
                + "_user_" + System.currentTimeMillis() + ext;
        Path tmp = Files.createTempFile("user-video-", ext);
        try {
            file.transferTo(tmp.toFile());
            return s3StorageService.upload(tmp, key);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String guessExtension(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                String ext = name.substring(dot).toLowerCase();
                if (ext.matches("\\.(mp4|mov|webm|mkv|m4v)")) return ext;
            }
        }
        String contentType = file.getContentType();
        if (contentType != null) {
            return switch (contentType) {
                case "video/quicktime"   -> ".mov";
                case "video/webm"        -> ".webm";
                case "video/x-matroska"  -> ".mkv";
                default                  -> ".mp4";
            };
        }
        return ".mp4";
    }
}