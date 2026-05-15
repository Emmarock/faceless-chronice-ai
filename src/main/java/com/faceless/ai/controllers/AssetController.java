package com.faceless.ai.controllers;

import com.faceless.ai.entity.Asset;
import com.faceless.ai.entity.AssetType;
import com.faceless.ai.model.AssetSummaryDTO;
import com.faceless.ai.model.PagedAssetsDTO;
import com.faceless.ai.model.VideoPublishRequest;
import com.faceless.ai.model.VideoPublishResponse;
import com.faceless.ai.service.AssetLibraryService;
import com.faceless.ai.service.S3StorageService;
import com.faceless.ai.service.VideoPublishService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * User-facing asset library:
 * <ul>
 *   <li>{@code GET  /api/assets} — list everything the caller owns, optional
 *       {@code ?type=IMAGE|SOURCE_VIDEO|VOICE|MUSIC|VIDEO_CLIP|THUMBNAIL}
 *       filter</li>
 *   <li>{@code GET  /api/assets/{id}/raw} — stream the asset's bytes via the
 *       backend so the browser never sees the raw S3 URL or needs S3 creds</li>
 *   <li>{@code POST /api/assets/upload} — upload a standalone asset (no job
 *       context) into the library</li>
 *   <li>{@code POST /api/assets/{id}/reuse} — copy a library/job asset into
 *       the target scene of another job (IMAGE → append, SOURCE_VIDEO →
 *       replace). Triggers the same per-scene invalidation as a manual edit
 *       would, so the next /resume re-renders only what changed.</li>
 *   <li>{@code DELETE /api/assets/{id}} — delete a standalone library asset
 *       (job-bound assets must be removed via the per-scene endpoints so
 *       their scene/index bookkeeping stays consistent)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/assets")
@RequiredArgsConstructor
@Slf4j
public class AssetController {

    private final AssetLibraryService assetLibraryService;
    private final S3StorageService s3StorageService;
    private final VideoPublishService videoPublishService;

    @GetMapping
    public ResponseEntity<PagedAssetsDTO> listAssets(
            @RequestHeader("X-USER") String userId,
            @RequestParam(value = "type", required = false) AssetType type,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "24") int size) {
        return ResponseEntity.ok(assetLibraryService.listAssets(userId, type, page, size));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AssetSummaryDTO> upload(
            @RequestHeader("X-USER") String userId,
            @RequestParam("type") AssetType type,
            @RequestPart("file") MultipartFile file) throws Exception {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(assetLibraryService.uploadStandalone(userId, type, file));
    }

    @PostMapping("/{assetId}/reuse")
    public ResponseEntity<Map<String, Object>> reuseInScene(
            @RequestHeader("X-USER") String userId,
            @PathVariable UUID assetId,
            @RequestParam("jobId") UUID jobId,
            @RequestParam("sceneId") int sceneId) {
        assetLibraryService.reuseInScene(userId, assetId, jobId, sceneId);
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "assetId", assetId,
                "jobId", jobId,
                "sceneId", sceneId));
    }

    /**
     * Queue a rendered clip ({@code VIDEO_CLIP} asset) for upload to one or
     * more connected social platforms. Same response shape as the existing
     * {@code POST /api/videos/{videoId}/publish} endpoint — per-platform
     * status so partial successes are visible.
     */
    @PostMapping("/{assetId}/publish")
    public ResponseEntity<VideoPublishResponse> publishClip(
            @RequestHeader("X-USER") String userId,
            @PathVariable UUID assetId,
            @RequestBody VideoPublishRequest request) {
        return ResponseEntity.accepted()
                .body(videoPublishService.publishAsset(assetId, userId, request.getPlatforms()));
    }

    @DeleteMapping("/{assetId}")
    public ResponseEntity<Void> deleteAsset(
            @RequestHeader("X-USER") String userId,
            @PathVariable UUID assetId) {
        assetLibraryService.deleteLibraryAsset(userId, assetId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Streams the bytes of an asset by id. Deliberately does NOT require
     * {@code X-USER} — browser {@code <img>}/{@code <video>} tags can't
     * carry custom headers through axios interceptors, so any header gate
     * here would 401 every image in the library UI. The asset UUID is
     * unguessable, which is the same access-control posture used by the
     * existing per-scene streaming endpoints in {@code JobFileController}.
     *
     * <p>Cache headers: each asset id maps to exactly one immutable byte
     * stream — a reuse / replace creates a new Asset row with a new id, it
     * never mutates an existing one. So we set {@code public, max-age=1y,
     * immutable} plus a strong ETag and short-circuit on {@code
     * If-None-Match} with 304, which lets the browser (and any CDN in
     * front of us) keep cached copies indefinitely.
     */
    @GetMapping("/{assetId}/raw")
    public ResponseEntity<StreamingResponseBody> streamAsset(
            @PathVariable UUID assetId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        Asset asset = assetLibraryService.findById(assetId);
        if (asset == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .header("X-Asset-Status", "missing")
                    .build();
        }

        // Asset id is immutable per byte stream, so it's a sound strong ETag.
        String etag = "\"" + assetId + "\"";
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                    .build();
        }

        String url = asset.getUrl();
        if (!s3StorageService.exists(url)) {
            log.warn("Asset {} points at missing S3 object {} — returning 404",
                    assetId, url);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .header("X-Asset-Status", "missing")
                    .build();
        }

        long total;
        try {
            total = s3StorageService.contentLength(url);
        } catch (NoSuchKeyException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .header("X-Asset-Status", "missing")
                    .build();
        }

        StreamingResponseBody body = out -> {
            try (var stream = s3StorageService.openStream(url, null)) {
                stream.transferTo(out);
            } catch (Exception e) {
                log.warn("Asset stream interrupted for {}: {}", url, e.getMessage());
            }
        };

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentTypeFor(asset.getAssetType(), url));
        headers.setContentLength(total);
        headers.setETag(etag);
        headers.setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        return ResponseEntity.ok().headers(headers).body(body);
    }

    private static MediaType contentTypeFor(AssetType type, String url) {
        String lower = url == null ? "" : url.toLowerCase();
        return switch (type) {
            case IMAGE, THUMBNAIL -> {
                if (lower.endsWith(".png"))  yield MediaType.IMAGE_PNG;
                if (lower.endsWith(".gif"))  yield MediaType.IMAGE_GIF;
                if (lower.endsWith(".webp")) yield MediaType.parseMediaType("image/webp");
                yield MediaType.IMAGE_JPEG;
            }
            case SOURCE_VIDEO, VIDEO_CLIP -> {
                if (lower.endsWith(".webm")) yield MediaType.parseMediaType("video/webm");
                if (lower.endsWith(".mov"))  yield MediaType.parseMediaType("video/quicktime");
                if (lower.endsWith(".mkv"))  yield MediaType.parseMediaType("video/x-matroska");
                yield MediaType.parseMediaType("video/mp4");
            }
            case VOICE, MUSIC -> {
                if (lower.endsWith(".wav")) yield MediaType.parseMediaType("audio/wav");
                if (lower.endsWith(".ogg")) yield MediaType.parseMediaType("audio/ogg");
                if (lower.endsWith(".m4a")) yield MediaType.parseMediaType("audio/mp4");
                yield MediaType.parseMediaType("audio/mpeg");
            }
        };
    }
}