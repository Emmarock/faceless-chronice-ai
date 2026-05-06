package com.faceless.ai.controllers;

import com.faceless.ai.entity.Video;
import com.faceless.ai.model.VideoPublishRequest;
import com.faceless.ai.model.VideoPublishResponse;
import com.faceless.ai.model.VideoSummaryDTO;
import com.faceless.ai.service.S3StorageService;
import com.faceless.ai.service.VideoPublishService;
import com.faceless.ai.service.VideoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;
import java.util.UUID;

/**
 * Read-only API for the rendered videos.
 *
 * <p>List + per-video streaming. Streaming honours HTTP Range requests so the
 * browser's {@code <video>} element can seek without downloading the whole file.
 */
@RestController
@RequestMapping("/api/videos")
@RequiredArgsConstructor
@Slf4j
public class VideoController {

    private final VideoService videoService;
    private final S3StorageService s3StorageService;
    private final VideoPublishService videoPublishService;

    @GetMapping
    public ResponseEntity<List<VideoSummaryDTO>> list(@RequestHeader("X-USER") String userId) {
        return ResponseEntity.ok(videoService.listForUser(userId));
    }

    /**
     * Queue a rendered video for upload to one or more connected social
     * platforms. Each platform reports its own status (QUEUED / NOT_CONNECTED
     * / UNSUPPORTED / ALREADY_UPLOADED) so partial successes are visible.
     */
    @PostMapping("/{videoId}/publish")
    public ResponseEntity<VideoPublishResponse> publish(@PathVariable UUID videoId,
                                                        @RequestHeader("X-USER") String userId,
                                                        @RequestBody VideoPublishRequest request) {
        return ResponseEntity.accepted()
                .body(videoPublishService.publish(videoId, userId, request.getPlatforms()));
    }

    /**
     * Streams the video bytes from S3 through the backend so the browser doesn't
     * need direct S3 (or LocalStack) credentials. Supports {@code Range} for
     * seeking inside an HTML5 video element.
     */
    @GetMapping("/{videoId}/stream")
    public ResponseEntity<StreamingResponseBody> stream(@PathVariable UUID videoId,
                                                        @RequestHeader(value = "Range", required = false) String rangeHeader) {
        Video video = videoService.getVideo(videoId);
        String s3Url = video.getStorageUrl();
        long total = s3StorageService.contentLength(s3Url);

        long start = 0;
        long end = total - 1;
        boolean partial = false;

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            String spec = rangeHeader.substring("bytes=".length()).trim();
            int dash = spec.indexOf('-');
            if (dash >= 0) {
                try {
                    String startStr = spec.substring(0, dash);
                    String endStr = spec.substring(dash + 1);
                    if (!startStr.isBlank()) start = Long.parseLong(startStr);
                    if (!endStr.isBlank()) end = Math.min(Long.parseLong(endStr), total - 1);
                    if (start > end || start < 0) {
                        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                                .header(HttpHeaders.CONTENT_RANGE, "bytes */" + total)
                                .build();
                    }
                    partial = true;
                } catch (NumberFormatException e) {
                    // malformed range — fall through and serve the whole thing
                }
            }
        }

        long length = end - start + 1;
        String s3Range = "bytes=" + start + "-" + end;

        StreamingResponseBody body = out -> {
            try (var s3Stream = s3StorageService.openStream(s3Url, s3Range)) {
                s3Stream.transferTo(out);
            } catch (Exception e) {
                log.warn("Stream interrupted for video {} ({}): {}", videoId, s3Url, e.getMessage());
            }
        };

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("video/mp4"));
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.setContentLength(length);
        if (partial) {
            headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + total);
        }

        return new ResponseEntity<>(body, headers, partial ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK);
    }
}