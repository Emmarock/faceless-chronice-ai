package com.faceless.ai.service;

import com.faceless.ai.entity.Asset;
import com.faceless.ai.entity.AssetType;
import com.faceless.ai.entity.Job;
import com.faceless.ai.model.AssetSummaryDTO;
import com.faceless.ai.model.PagedAssetsDTO;
import com.faceless.ai.model.VideoScript;
import com.faceless.ai.repository.AssetRepository;
import com.faceless.ai.repository.JobRepository;
import com.faceless.ai.repository.ScriptRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cross-job asset management. Backs the user-facing library page:
 * <ul>
 *   <li>list every asset a user owns (job-bound + standalone uploads)</li>
 *   <li>upload a standalone asset directly into the library (jobId = null)</li>
 *   <li>reuse a library/job asset in a new scene (deep copy: a new S3
 *       object + a new Asset row scoped to the destination job)</li>
 *   <li>delete a library asset (only standalone rows can be deleted here —
 *       job-bound rows are managed via the per-scene endpoints so their
 *       scene/index bookkeeping stays consistent)</li>
 * </ul>
 *
 * <p>Reuse is implemented via S3 server-side copy and delegates to
 * {@link ImageAssetService} / {@link SourceVideoAssetService} so the per-scene
 * cache-invalidation logic stays in one place.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AssetLibraryService {

    private final AssetRepository assetRepository;
    private final JobRepository jobRepository;
    private final ScriptRepository scriptRepository;
    private final S3StorageService s3StorageService;
    private final ImageAssetService imageAssetService;
    private final SourceVideoAssetService sourceVideoAssetService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Asset types that are managed internally by the pipeline but aren't
     * useful in the user-facing library — voice / music tracks and
     * thumbnails. The listing endpoint excludes these on both the
     * unfiltered "All" view and any explicit {@code ?type=} filter that
     * names one of them.
     */
    private static final Set<AssetType> HIDDEN_TYPES =
            Collections.unmodifiableSet(EnumSet.of(AssetType.VOICE, AssetType.MUSIC, AssetType.THUMBNAIL));

    /**
     * Paginated listing of every asset the user owns. {@code page} is
     * zero-indexed; {@code size} is clamped to a sensible range to keep
     * any one response cheap to render. Job titles for the page's rows are
     * resolved in a single batch.
     */
    public PagedAssetsDTO listAssets(String userId, AssetType filter, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 100));
        int safePage = Math.max(0, page);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdOn"));

        // Hidden types are invisible even when requested explicitly — short-
        // circuit to an empty page so a hand-crafted ?type=VOICE call doesn't
        // bypass the denylist.
        if (filter != null && HIDDEN_TYPES.contains(filter)) {
            return PagedAssetsDTO.builder()
                    .items(List.of())
                    .page(safePage)
                    .size(safeSize)
                    .totalItems(0)
                    .totalPages(0)
                    .build();
        }

        Page<Asset> result = (filter == null)
                ? assetRepository.findByCreatedByAndAssetTypeNotIn(userId, HIDDEN_TYPES, pageable)
                : assetRepository.findByCreatedByAndAssetType(userId, filter, pageable);

        // Resolve job titles in one batch instead of N round trips.
        Set<UUID> jobIds = result.getContent().stream()
                .map(Asset::getJobId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> jobTitles = new HashMap<>();
        if (!jobIds.isEmpty()) {
            for (Job j : jobRepository.findAllById(jobIds)) {
                jobTitles.put(j.getId(), resolveJobTitle(j));
            }
        }

        List<AssetSummaryDTO> items = result.getContent().stream()
                .map(a -> AssetSummaryDTO.builder()
                        .id(a.getId())
                        .assetType(a.getAssetType())
                        .jobId(a.getJobId())
                        .jobTitle(a.getJobId() == null ? null : jobTitles.get(a.getJobId()))
                        .metadata(a.getMetadata())
                        .streamUrl("/api/assets/" + a.getId() + "/raw")
                        .createdAt(a.getCreatedOn())
                        .build())
                .toList();

        return PagedAssetsDTO.builder()
                .items(items)
                .page(result.getNumber())
                .size(result.getSize())
                .totalItems(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .build();
    }

    /**
     * Upload a file directly into the user's library — no job context.
     * The {@code jobId} on the resulting Asset row is null; metadata is
     * tagged {@code "library"} so the per-scene services never pick it up
     * by accident.
     */
    @Transactional
    public AssetSummaryDTO uploadStandalone(String userId, AssetType type, MultipartFile file)
            throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Upload file is required.");
        }
        String ext = guessExtension(file, type);
        String userBucket = userBucketKey(userId);
        String key = "library/" + userBucket + "/" + UUID.randomUUID() + ext;

        Path tmp = Files.createTempFile("library-upload-", ext);
        String s3Url;
        try {
            file.transferTo(tmp.toFile());
            s3Url = s3StorageService.upload(tmp, key);
        } finally {
            Files.deleteIfExists(tmp);
        }

        // Don't pre-set .id() here — BaseEntity's @GeneratedValue makes
        // Hibernate generate its own UUID at flush time and silently discard
        // anything we set in the builder. Read the id back off the persisted
        // entity instead, otherwise the returned DTO points at a UUID that
        // doesn't exist in the DB.
        Asset asset = Asset.builder()
                .jobId(null)
                .assetType(type)
                .url(s3Url)
                .metadata("library")
                .createdBy(userId)
                .lastModifiedBy(userId)
                .createdOn(Instant.now())
                .lastModifiedOn(Instant.now())
                .build();
        Asset persisted = assetRepository.save(asset);
        log.info("Uploaded standalone library asset {} ({}) for user {}", persisted.getId(), type, userId);

        return AssetSummaryDTO.builder()
                .id(persisted.getId())
                .assetType(type)
                .metadata(persisted.getMetadata())
                .streamUrl("/api/assets/" + persisted.getId() + "/raw")
                .createdAt(persisted.getCreatedOn())
                .build();
    }

    /**
     * Reuse a library/job asset in the given scene of {@code jobId}. The
     * source S3 object is server-side-copied to a new key, a new Asset row
     * is created in the destination scene's slot, and the scene's downstream
     * artifacts (cached video clip + final video) are invalidated.
     *
     * <p>Currently supported for {@link AssetType#IMAGE} (append) and
     * {@link AssetType#SOURCE_VIDEO} (replace). Other types are exposed for
     * download/preview but reuse for them isn't wired through any per-scene
     * pipeline today.
     */
    @Transactional
    public void reuseInScene(String userId, UUID assetId, UUID jobId, int sceneId) {
        Asset asset = requireOwned(userId, assetId);
        String ext = extensionOf(asset.getUrl());

        switch (asset.getAssetType()) {
            case IMAGE -> imageAssetService.appendImageFromS3(jobId, sceneId, asset.getUrl(), ext, userId);
            case SOURCE_VIDEO -> sourceVideoAssetService.replaceSourceVideoFromS3(jobId, sceneId, asset.getUrl(), ext, userId);
            default -> throw new IllegalArgumentException(
                    "Reusing " + asset.getAssetType() + " assets in a scene is not supported.");
        }
    }

    /**
     * Delete a standalone library asset (jobId = null). Job-bound rows must
     * be removed via the per-scene endpoints so their scene-index
     * bookkeeping stays consistent.
     */
    @Transactional
    public void deleteLibraryAsset(String userId, UUID assetId) {
        Asset asset = requireOwned(userId, assetId);
        if (asset.getJobId() != null) {
            throw new IllegalArgumentException(
                    "Asset is bound to a job — delete it from the job's scene editor instead.");
        }
        s3StorageService.delete(asset.getUrl());
        assetRepository.delete(asset);
        log.info("Deleted standalone library asset {} for user {}", assetId, userId);
    }

    /**
     * Lookup without an ownership check — used by the streaming endpoint,
     * which (intentionally) authorises on URL knowledge alone. Returns
     * {@code null} when the row is gone.
     */
    public Asset findById(UUID assetId) {
        return assetRepository.findById(assetId).orElse(null);
    }

    public Asset requireOwned(String userId, UUID assetId) {
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new IllegalArgumentException("Asset not found: " + assetId));
        if (!userId.equals(asset.getCreatedBy())) {
            throw new IllegalArgumentException("Asset does not belong to user: " + assetId);
        }
        return asset;
    }

    private String resolveJobTitle(Job job) {
        return scriptRepository.findByJobId(job.getId())
                .map(s -> {
                    try {
                        VideoScript vs = objectMapper.readValue(s.getContent(), VideoScript.class);
                        if (vs.getTitle() != null && !vs.getTitle().isBlank()) {
                            return vs.getTitle();
                        }
                    } catch (Exception ignored) {
                        // Fall through to the question fallback.
                    }
                    return null;
                })
                .filter(t -> t != null && !t.isBlank())
                .orElseGet(() -> job.getQuestion() != null ? job.getQuestion() : "(untitled job)");
    }

    /**
     * Stable per-user directory prefix that doesn't leak the raw email. The
     * email is the source of truth (it's the {@code createdBy} value) but a
     * sha-256 prefix keeps S3 keys URL-safe and short.
     */
    private static String userBucketKey(String userId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(userId.getBytes());
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (Exception e) {
            // SHA-256 is guaranteed by the JVM — but if it ever fails, fall
            // back to something deterministic so library uploads still work.
            return Integer.toHexString(userId.hashCode());
        }
    }

    private static String extensionOf(String url) {
        if (url == null) return "";
        int q = url.indexOf('?');
        String clean = q >= 0 ? url.substring(0, q) : url;
        int dot = clean.lastIndexOf('.');
        int slash = clean.lastIndexOf('/');
        return (dot > slash && dot >= 0) ? clean.substring(dot) : "";
    }

    private static String guessExtension(MultipartFile file, AssetType type) {
        String name = file.getOriginalFilename();
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                return name.substring(dot).toLowerCase();
            }
        }
        String contentType = file.getContentType();
        if (contentType != null) {
            return switch (contentType) {
                case "image/png"  -> ".png";
                case "image/webp" -> ".webp";
                case "image/gif"  -> ".gif";
                case "image/bmp"  -> ".bmp";
                case "image/jpeg" -> ".jpg";
                case "video/quicktime"  -> ".mov";
                case "video/webm"       -> ".webm";
                case "video/x-matroska" -> ".mkv";
                case "video/mp4"        -> ".mp4";
                case "audio/mpeg" -> ".mp3";
                case "audio/wav", "audio/x-wav" -> ".wav";
                case "audio/ogg"  -> ".ogg";
                case "audio/mp4"  -> ".m4a";
                default -> defaultExtensionForType(type);
            };
        }
        return defaultExtensionForType(type);
    }

    private static String defaultExtensionForType(AssetType type) {
        return switch (type) {
            case IMAGE, THUMBNAIL -> ".jpg";
            case SOURCE_VIDEO, VIDEO_CLIP -> ".mp4";
            case VOICE, MUSIC -> ".mp3";
        };
    }
}