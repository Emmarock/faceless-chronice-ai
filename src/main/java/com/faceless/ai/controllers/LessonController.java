package com.faceless.ai.controllers;

import com.faceless.ai.entity.Lesson;
import com.faceless.ai.model.CreateLessonRequest;
import com.faceless.ai.model.LessonDTO;
import com.faceless.ai.service.LessonService;
import com.faceless.ai.service.S3StorageService;
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
 * AI tutor lessons — videos of a {@link com.faceless.ai.entity.Twin} teaching a
 * topic. Creation returns immediately; scripting (Claude) and rendering
 * (HeyGen) finish asynchronously via {@code TwinLessonPoller}, so clients poll
 * {@code GET /api/lessons/{id}} until status is COMPLETED, then play the video
 * from {@code /api/lessons/{id}/stream}.
 */
@RestController
@RequestMapping("/api/lessons")
@RequiredArgsConstructor
@Slf4j
public class LessonController {

    private final LessonService lessonService;
    private final S3StorageService s3StorageService;

    @PostMapping
    public ResponseEntity<LessonDTO> create(@RequestHeader("X-USER") String userId,
                                            @RequestBody CreateLessonRequest request) {
        Lesson lesson = lessonService.createLesson(userId, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(LessonDTO.from(lesson));
    }

    @GetMapping
    public ResponseEntity<List<LessonDTO>> list(@RequestHeader("X-USER") String userId) {
        return ResponseEntity.ok(lessonService.listForUser(userId).stream().map(LessonDTO::from).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<LessonDTO> get(@RequestHeader("X-USER") String userId,
                                         @PathVariable UUID id) {
        return ResponseEntity.ok(LessonDTO.from(lessonService.getForUser(id, userId)));
    }

    /**
     * Streams the rendered lesson MP4 from S3 through the backend (browser never
     * sees S3 creds). Range-aware so the {@code <video>} element can seek.
     * Mirrors {@code VideoController#stream}.
     */
    @GetMapping("/{id}/stream")
    public ResponseEntity<StreamingResponseBody> stream(@PathVariable UUID id,
                                                        @RequestHeader(value = "Range", required = false) String rangeHeader) {
        Lesson lesson = lessonService.getForStream(id);
        String s3Url = lesson.getVideoUrl();
        if (s3Url == null || s3Url.isBlank()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
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
                log.warn("Stream interrupted for lesson {} ({}): {}", id, s3Url, e.getMessage());
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
