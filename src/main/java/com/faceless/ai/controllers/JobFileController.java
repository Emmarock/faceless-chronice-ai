package com.faceless.ai.controllers;

import com.faceless.ai.entity.Asset;
import com.faceless.ai.model.GenerateJobRequest;
import com.faceless.ai.model.JobFileDTO;
import com.faceless.ai.model.JobProgressDTO;
import com.faceless.ai.model.JobSummaryDTO;
import com.faceless.ai.model.VideoScript;
import com.faceless.ai.service.ImageAssetService;
import com.faceless.ai.service.JobFileService;
import com.faceless.ai.service.S3StorageService;
import com.faceless.ai.service.SourceVideoAssetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/job-file")
@RequiredArgsConstructor
@Slf4j
public class JobFileController {

    private final JobFileService jobFileService;
    private final ImageAssetService imageAssetService;
    private final SourceVideoAssetService sourceVideoAssetService;
    private final S3StorageService s3StorageService;

    @PostMapping("/generate")
    public ResponseEntity<JobFileDTO> generateJobFile(@RequestBody GenerateJobRequest request,
                                                      @RequestHeader("X-USER") String createdBy) throws Exception {
        JobFileDTO jobFile = jobFileService.generateJobFile(request, createdBy);
        return ResponseEntity.ok(jobFile);
    }

    /**
     * Lists all jobs owned by the calling user, newest first. Each entry
     * carries the generated script title (when available) so the frontend can
     * render the jobs list without an extra round-trip per row.
     */
    @GetMapping
    public ResponseEntity<List<JobSummaryDTO>> listJobs(@RequestHeader("X-USER") String createdBy) {
        return ResponseEntity.ok(jobFileService.listJobs(createdBy));
    }

    /**
     * Returns the latest persisted JobFileDTO — the stored VideoScript with any
     * saved voice / image / video URLs merged in. Used by the frontend to load
     * an existing job for review and editing before the user clicks Resume.
     */
    @GetMapping("/{jobId}")
    public ResponseEntity<JobFileDTO> getJobFile(@PathVariable UUID jobId) throws Exception {
        return ResponseEntity.ok(jobFileService.getJobFile(jobId));
    }

    /**
     * Lightweight progress projection for a single job. Designed to be polled
     * at short intervals from the frontend to drive a progress indicator —
     * only the Job row is read (no script / asset enrichment).
     */
    @GetMapping("/{jobId}/progress")
    public ResponseEntity<JobProgressDTO> getJobProgress(@PathVariable UUID jobId) {
        return ResponseEntity.ok(jobFileService.getJobProgress(jobId));
    }

    /**
     * Batch progress projection for every job owned by the caller. Used by the
     * jobs-list page to drive per-row progress bars without re-reading scripts.
     */
    @GetMapping("/progress")
    public ResponseEntity<List<JobProgressDTO>> listJobProgress(@RequestHeader("X-USER") String createdBy) {
        return ResponseEntity.ok(jobFileService.listJobProgress(createdBy));
    }

    /**
     * Persists user edits to the script (typically scene text). The next call
     * to /resume will pick up the edited copy when generating voice / images /
     * video.
     */
    @PutMapping("/{jobId}/script")
    public ResponseEntity<JobFileDTO> updateScript(@PathVariable UUID jobId,
                                                   @RequestBody VideoScript videoScript,
                                                   @RequestHeader("X-USER") String requestedBy) throws Exception {
        return ResponseEntity.ok(jobFileService.updateScript(jobId, videoScript, requestedBy));
    }

    /**
     * Asks the AI to regenerate every regular scene's spoken text on this job
     * while keeping the user's title, hook, and closing intact. Every voice,
     * image, source-video, and rendered-clip asset belonging to a regenerated
     * scene is deleted along with the final video — the next /resume rebuilds
     * them from the new text.
     */
    @PostMapping("/{jobId}/scenes/regenerate")
    public ResponseEntity<JobFileDTO> regenerateScenes(@PathVariable UUID jobId,
                                                       @RequestHeader("X-USER") String requestedBy) throws Exception {
        return ResponseEntity.ok(jobFileService.regenerateScenes(jobId, requestedBy));
    }

    /**
     * Asks the AI to rewrite the spoken text for a single scene, using its
     * neighbours as flow context. Only that scene's downstream assets and the
     * final video are invalidated; sibling scenes' artifacts are untouched.
     */
    @PostMapping("/{jobId}/scenes/{sceneId}/regenerate")
    public ResponseEntity<JobFileDTO> regenerateScene(@PathVariable UUID jobId,
                                                      @PathVariable int sceneId,
                                                      @RequestHeader("X-USER") String requestedBy) throws Exception {
        return ResponseEntity.ok(jobFileService.regenerateScene(jobId, sceneId, requestedBy));
    }

    /**
     * Re-queues an existing job from its last known pipeline state.
     *
     * <p>The service reconstructs the {@link JobFileDTO} from the DB (script +
     * all saved assets), writes all known asset URLs back onto the VideoScript
     * segments, then publishes to the SQS queue for the earliest incomplete stage.
     *
     * <p>Because every consumer is idempotent this endpoint is safe to call
     * multiple times; already-completed stages are automatically skipped.
     *
     * <pre>
     * POST /api/job-file/{jobId}/resume
     * Header: X-USER: &lt;userId&gt;
     * </pre>
     */
    @PostMapping("/{jobId}/resume")
    public ResponseEntity<JobFileDTO> resumeJob(@PathVariable UUID jobId,
                                                @RequestHeader("X-USER") String requestedBy) throws Exception {
        JobFileDTO jobFile = jobFileService.resumeJob(jobId, requestedBy);
        return ResponseEntity.accepted().body(jobFile);
    }

    // ------------------------------------------------------------------ //
    //  Per-scene image editing
    //
    //  Mutating an image invalidates the affected scene's cached video clip
    //  and the final video, so the next /resume re-renders only that scene
    //  plus the final concat.
    // ------------------------------------------------------------------ //

    /**
     * Streams the bytes of the image at scene {@code sceneId}, slot
     * {@code index} from S3. Used by the frontend &lt;img&gt; tags so the
     * browser never needs S3 credentials.
     */
    @GetMapping("/{jobId}/scenes/{sceneId}/images/{index}/raw")
    public ResponseEntity<StreamingResponseBody> streamImage(@PathVariable UUID jobId,
                                                             @PathVariable int sceneId,
                                                             @PathVariable int index) {
        Asset asset = imageAssetService.requireImage(jobId, sceneId, index);
        String url = asset.getUrl();

        // The Asset row can outlive the underlying S3 object — most commonly
        // when LocalStack is restarted while the DB persists. A 404 here lets
        // the &lt;img&gt; element fall back to its error state cleanly instead of
        // surfacing a 500 stack trace, and the user can hit "Replace" on the
        // tile to upload a fresh file at the same slot.
        if (!s3StorageService.exists(url)) {
            log.warn("Image asset {}_{} for job {} points at missing S3 object {} — returning 404",
                    sceneId, index, jobId, url);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .header("X-Image-Status", "missing")
                    .build();
        }

        long total;
        try {
            total = s3StorageService.contentLength(url);
        } catch (NoSuchKeyException e) {
            // Race: object disappeared between exists() and contentLength().
            log.warn("Image asset {}_{} for job {} disappeared mid-request: {}",
                    sceneId, index, jobId, url);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .header("X-Image-Status", "missing")
                    .build();
        }

        StreamingResponseBody body = out -> {
            try (var stream = s3StorageService.openStream(url, null)) {
                stream.transferTo(out);
            } catch (Exception e) {
                log.warn("Image stream interrupted for {}: {}", url, e.getMessage());
            }
        };

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentTypeFor(url));
        headers.setContentLength(total);
        // Don't cache: a replace at the same (sceneId,index) reuses this URL.
        headers.setCacheControl("no-cache, no-store, must-revalidate");
        return ResponseEntity.ok().headers(headers).body(body);
    }

    @PutMapping(value = "/{jobId}/scenes/{sceneId}/images/{index}",
                consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<JobFileDTO> replaceImage(@PathVariable UUID jobId,
                                                   @PathVariable int sceneId,
                                                   @PathVariable int index,
                                                   @RequestPart("file") MultipartFile file,
                                                   @RequestHeader("X-USER") String requestedBy) throws Exception {
        imageAssetService.replaceImage(jobId, sceneId, index, file, requestedBy);
        return ResponseEntity.ok(jobFileService.getJobFile(jobId));
    }

    @PostMapping(value = "/{jobId}/scenes/{sceneId}/images",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> appendImage(@PathVariable UUID jobId,
                                                           @PathVariable int sceneId,
                                                           @RequestPart("file") MultipartFile file,
                                                           @RequestHeader("X-USER") String requestedBy) throws Exception {
        int newIndex = imageAssetService.appendImage(jobId, sceneId, file, requestedBy);
        return ResponseEntity.ok(Map.of(
                "index", newIndex,
                "jobFile", jobFileService.getJobFile(jobId)));
    }

    @DeleteMapping("/{jobId}/scenes/{sceneId}/images/{index}")
    public ResponseEntity<JobFileDTO> deleteImage(@PathVariable UUID jobId,
                                                  @PathVariable int sceneId,
                                                  @PathVariable int index,
                                                  @RequestHeader("X-USER") String requestedBy) throws Exception {
        imageAssetService.deleteImage(jobId, sceneId, index, requestedBy);
        return ResponseEntity.ok(jobFileService.getJobFile(jobId));
    }

    // ------------------------------------------------------------------ //
    //  Per-scene source-video editing (video-mode scenes)
    //
    //  Mirrors the image endpoints but with one clip per scene — no index,
    //  no append. Mutating the source video invalidates the scene's cached
    //  rendered clip and the final video so the next /resume re-renders.
    // ------------------------------------------------------------------ //

    /**
     * Streams the bytes of the source video for {@code sceneId} from S3.
     * Used by the frontend &lt;video&gt; element so the browser never needs
     * S3 credentials. Same 404-on-missing semantics as the image endpoint.
     */
    @GetMapping("/{jobId}/scenes/{sceneId}/source-video/raw")
    public ResponseEntity<StreamingResponseBody> streamSourceVideo(@PathVariable UUID jobId,
                                                                   @PathVariable int sceneId) {
        Asset asset = sourceVideoAssetService.requireSourceVideo(jobId, sceneId);
        String url = asset.getUrl();

        if (!s3StorageService.exists(url)) {
            log.warn("Source video for scene {} of job {} points at missing S3 object {} — returning 404",
                    sceneId, jobId, url);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .header("X-Video-Status", "missing")
                    .build();
        }

        long total;
        try {
            total = s3StorageService.contentLength(url);
        } catch (NoSuchKeyException e) {
            log.warn("Source video for scene {} of job {} disappeared mid-request: {}",
                    sceneId, jobId, url);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .header("X-Video-Status", "missing")
                    .build();
        }

        StreamingResponseBody body = out -> {
            try (var stream = s3StorageService.openStream(url, null)) {
                stream.transferTo(out);
            } catch (Exception e) {
                log.warn("Source-video stream interrupted for {}: {}", url, e.getMessage());
            }
        };

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(videoContentTypeFor(url));
        headers.setContentLength(total);
        headers.setCacheControl("no-cache, no-store, must-revalidate");
        return ResponseEntity.ok().headers(headers).body(body);
    }

    @PutMapping(value = "/{jobId}/scenes/{sceneId}/source-video",
                consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<JobFileDTO> replaceSourceVideo(@PathVariable UUID jobId,
                                                         @PathVariable int sceneId,
                                                         @RequestPart("file") MultipartFile file,
                                                         @RequestHeader("X-USER") String requestedBy) throws Exception {
        sourceVideoAssetService.replaceSourceVideo(jobId, sceneId, file, requestedBy);
        return ResponseEntity.ok(jobFileService.getJobFile(jobId));
    }

    @DeleteMapping("/{jobId}/scenes/{sceneId}/source-video")
    public ResponseEntity<JobFileDTO> deleteSourceVideo(@PathVariable UUID jobId,
                                                        @PathVariable int sceneId) throws Exception {
        sourceVideoAssetService.deleteSourceVideo(jobId, sceneId);
        return ResponseEntity.ok(jobFileService.getJobFile(jobId));
    }

    private static MediaType contentTypeFor(String url) {
        String lower = url.toLowerCase();
        if (lower.endsWith(".png"))  return MediaType.IMAGE_PNG;
        if (lower.endsWith(".gif"))  return MediaType.IMAGE_GIF;
        if (lower.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        return MediaType.IMAGE_JPEG;
    }

    private static MediaType videoContentTypeFor(String url) {
        String lower = url.toLowerCase();
        if (lower.endsWith(".webm")) return MediaType.parseMediaType("video/webm");
        if (lower.endsWith(".mov"))  return MediaType.parseMediaType("video/quicktime");
        if (lower.endsWith(".mkv"))  return MediaType.parseMediaType("video/x-matroska");
        return MediaType.parseMediaType("video/mp4");
    }
}