package com.faceless.ai.service;

import com.faceless.ai.entity.Asset;
import com.faceless.ai.entity.AssetType;
import com.faceless.ai.entity.Job;
import com.faceless.ai.entity.Video;
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
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * CRUD for the per-scene IMAGE asset list. Every mutation:
 *   1. updates IMAGE rows + S3 objects so the next render uses the new set
 *   2. invalidates the affected scene's cached VIDEO_CLIP and the job's final
 *      Video so a subsequent {@code POST /resume} re-renders only what changed
 *      (per-scene clips are reused; the targeted clip + final concat are rebuilt).
 *
 * Image metadata format on the {@link Asset} row is {@code "{sceneId}_{index}"}
 * (e.g. "1_0", "1_1", "-2_0"). Indices are kept contiguous: a delete reindexes
 * the trailing items rather than leaving holes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImageAssetService {

    private final AssetRepository assetRepository;
    private final VideoRepository videoRepository;
    private final S3StorageService s3StorageService;
    private final JobService jobService;

    /**
     * Upload a replacement for the image at slot {@code (sceneId, index)}.
     * The previous S3 object is deleted; the Asset row's URL is overwritten.
     */
    @Transactional
    public void replaceImage(UUID jobId, int sceneId, int index, MultipartFile file, String requestedBy)
            throws IOException {
        Job job = jobService.getJob(jobId);
        Asset existing = requireImage(jobId, sceneId, index);

        String newUrl = uploadUserImage(jobId, sceneId, index, file);
        s3StorageService.delete(existing.getUrl());

        existing.setUrl(newUrl);
        existing.setLastModifiedBy(requestedBy);
        existing.setLastModifiedOn(Instant.now());
        assetRepository.save(existing);

        invalidateScene(job, sceneId);
        log.info("Replaced image {}_{} for job {}", sceneId, index, jobId);
    }

    /**
     * Append a new image as the next index in the scene. Returns the index
     * the image was inserted at.
     */
    @Transactional
    public int appendImage(UUID jobId, int sceneId, MultipartFile file, String requestedBy)
            throws IOException {
        Job job = jobService.getJob(jobId);
        List<Asset> sceneImages = imagesForScene(jobId, sceneId);
        int nextIndex = sceneImages.size();
        // Defensive: if metadata had a gap, append after the highest known index
        if (!sceneImages.isEmpty()) {
            int maxIdx = sceneImages.stream().mapToInt(a -> indexOf(a)).max().orElse(-1);
            nextIndex = Math.max(nextIndex, maxIdx + 1);
        }

        String newUrl = uploadUserImage(jobId, sceneId, nextIndex, file);
        Asset asset = Asset.builder()
                .id(UUID.randomUUID())
                .jobId(jobId)
                .assetType(AssetType.IMAGE)
                .url(newUrl)
                .metadata(sceneId + "_" + nextIndex)
                .createdBy(requestedBy)
                .lastModifiedBy(requestedBy)
                .createdOn(Instant.now())
                .lastModifiedOn(Instant.now())
                .build();
        assetRepository.save(asset);

        invalidateScene(job, sceneId);
        log.info("Appended image {}_{} for job {}", sceneId, nextIndex, jobId);
        return nextIndex;
    }

    /**
     * Remove the image at slot {@code (sceneId, index)} and re-number every
     * later index so the list stays contiguous. The deleted S3 object is
     * removed; trailing S3 objects are left in place — the metadata field on
     * the row is the canonical ordering, not the S3 key.
     */
    @Transactional
    public void deleteImage(UUID jobId, int sceneId, int index, String requestedBy) {
        Job job = jobService.getJob(jobId);
        List<Asset> sceneImages = imagesForScene(jobId, sceneId);
        if (sceneImages.size() <= 1) {
            throw new IllegalStateException(
                    "Refusing to delete the last image for scene " + sceneId
                    + " — every scene needs at least one image to render.");
        }

        Asset target = sceneImages.stream()
                .filter(a -> indexOf(a) == index)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No image at scene " + sceneId + " index " + index + " for job " + jobId));

        s3StorageService.delete(target.getUrl());
        assetRepository.delete(target);

        // Reindex everything after the deleted slot
        sceneImages.stream()
                .filter(a -> indexOf(a) > index)
                .sorted(Comparator.comparingInt(ImageAssetService::indexOf))
                .forEach(a -> {
                    int newIdx = indexOf(a) - 1;
                    a.setMetadata(sceneId + "_" + newIdx);
                    a.setLastModifiedBy(requestedBy);
                    a.setLastModifiedOn(Instant.now());
                    assetRepository.save(a);
                });

        invalidateScene(job, sceneId);
        log.info("Deleted image {}_{} for job {} (reindexed {} trailing slots)",
                sceneId, index, jobId,
                sceneImages.stream().filter(a -> indexOf(a) > index).count());
    }

    public Asset requireImage(UUID jobId, int sceneId, int index) {
        return assetRepository
                .findFirstByJobIdAndAssetTypeAndMetadata(jobId, AssetType.IMAGE, sceneId + "_" + index)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No image at scene " + sceneId + " index " + index + " for job " + jobId));
    }

    private List<Asset> imagesForScene(UUID jobId, int sceneId) {
        return assetRepository.findByJobIdAndAssetType(jobId, AssetType.IMAGE).stream()
                .filter(a -> a.getMetadata() != null)
                .filter(a -> sceneIdOf(a) == sceneId)
                .sorted(Comparator.comparingInt(ImageAssetService::indexOf))
                .toList();
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

    private String uploadUserImage(UUID jobId, int sceneId, int index, MultipartFile file)
            throws IOException {
        String ext = guessExtension(file);
        String jobIdFlat = jobId.toString().replace("-", "");
        String key = "jobs/" + jobIdFlat + "/images/segment_" + sceneId + "_" + index
                + "_user_" + System.currentTimeMillis() + ext;
        Path tmp = Files.createTempFile("user-image-", ext);
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
                if (ext.matches("\\.(jpg|jpeg|png|webp|gif|bmp)")) return ext;
            }
        }
        String contentType = file.getContentType();
        if (contentType != null) {
            return switch (contentType) {
                case "image/png"  -> ".png";
                case "image/webp" -> ".webp";
                case "image/gif"  -> ".gif";
                case "image/bmp"  -> ".bmp";
                default           -> ".jpg";
            };
        }
        return ".jpg";
    }

    private static int sceneIdOf(Asset asset) {
        String meta = asset.getMetadata();
        int sep = meta.indexOf('_');
        return Integer.parseInt(sep >= 0 ? meta.substring(0, sep) : meta);
    }

    private static int indexOf(Asset asset) {
        String meta = asset.getMetadata();
        int sep = meta.indexOf('_');
        return sep >= 0 ? Integer.parseInt(meta.substring(sep + 1)) : 0;
    }
}