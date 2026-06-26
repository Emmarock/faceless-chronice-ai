package com.faceless.ai.controllers;

import com.faceless.ai.entity.Twin;
import com.faceless.ai.model.TwinDTO;
import com.faceless.ai.service.TwinService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * AI tutor twins — the user's HeyGen avatar + cloned voice.
 *
 * <ul>
 *   <li>{@code POST   /api/twins} — multipart {@code video} (+ optional
 *       {@code name}); starts avatar training, returns the QUEUED/PROCESSING
 *       twin.</li>
 *   <li>{@code GET    /api/twins} — list the caller's twins.</li>
 *   <li>{@code GET    /api/twins/{id}} — single twin (poll for readiness).</li>
 *   <li>{@code DELETE /api/twins/{id}} — remove a twin.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/twins")
@RequiredArgsConstructor
@Slf4j
public class TwinController {

    private final TwinService twinService;

    /**
     * {@code video} is the primary likeness media — a recorded/uploaded video
     * OR a still photo. {@code audio} is an optional separate voice sample used
     * for cloning; when omitted, the voice is cloned from the video's own audio
     * (and a photo-only upload uses the default voice).
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TwinDTO> create(@RequestHeader("X-USER") String userId,
                                          @RequestParam("video") MultipartFile video,
                                          @RequestParam(value = "audio", required = false) MultipartFile audio,
                                          @RequestParam(value = "name", required = false) String name)
            throws Exception {
        Twin twin = twinService.createTwin(userId, name, video, audio);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(TwinDTO.from(twin));
    }

    @GetMapping
    public ResponseEntity<List<TwinDTO>> list(@RequestHeader("X-USER") String userId) {
        return ResponseEntity.ok(twinService.listForUser(userId).stream().map(TwinDTO::from).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<TwinDTO> get(@RequestHeader("X-USER") String userId,
                                       @PathVariable UUID id) {
        return ResponseEntity.ok(TwinDTO.from(twinService.getForUser(id, userId)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@RequestHeader("X-USER") String userId,
                                       @PathVariable UUID id) {
        twinService.deleteForUser(id, userId);
        return ResponseEntity.noContent().build();
    }
}
