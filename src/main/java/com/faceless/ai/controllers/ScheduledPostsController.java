package com.faceless.ai.controllers;

import com.faceless.ai.entity.SocialUpload;
import com.faceless.ai.entity.Status;
import com.faceless.ai.model.ScheduledUploadDTO;
import com.faceless.ai.repository.SocialUploadRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read + cancel API for the ScheduledPostsPage. Lists every SocialUpload
 * still in {@link Status#SCHEDULED} for the calling user; allows cancel
 * (rather than delete) so the row stays in the DB as an audit trail of
 * what the user almost posted.
 */
@RestController
@RequestMapping("/api/scheduled-uploads")
@RequiredArgsConstructor
public class ScheduledPostsController {

    private final SocialUploadRepository socialUploadRepository;

    @GetMapping
    public ResponseEntity<List<ScheduledUploadDTO>> list(@RequestHeader("X-USER") String userId) {
        List<ScheduledUploadDTO> rows = socialUploadRepository
                .findAllByCreatedByAndStatusOrderByScheduledAtAsc(userId, Status.SCHEDULED)
                .stream()
                .map(ScheduledUploadDTO::from)
                .toList();
        return ResponseEntity.ok(rows);
    }

    /**
     * Marks a scheduled upload as CANCELLED so the scheduler ignores it.
     * Owner-scoped — cancelling another user's row 404s instead of 403 to
     * avoid leaking which ids exist.
     */
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> cancel(@RequestHeader("X-USER") String userId, @PathVariable UUID id) {
        SocialUpload row = socialUploadRepository.findById(id).orElse(null);
        if (row == null || row.getCreatedBy() == null || !row.getCreatedBy().equals(userId)) {
            return ResponseEntity.notFound().build();
        }
        if (row.getStatus() != Status.SCHEDULED) {
            return ResponseEntity.status(409).build();
        }
        row.setStatus(Status.CANCELLED);
        row.setLastModifiedOn(Instant.now());
        row.setLastModifiedBy(userId);
        socialUploadRepository.save(row);
        return ResponseEntity.noContent().build();
    }
}
