package com.faceless.ai.repository;

import com.faceless.ai.entity.SocialPlatform;
import com.faceless.ai.entity.SocialUpload;
import com.faceless.ai.entity.Status;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SocialUploadRepository extends BaseRepository<SocialUpload, UUID> {

    Optional<SocialUpload> findFirstByVideoIdAndPlatform(UUID videoId, SocialPlatform platform);

    /**
     * Used by the PostScheduler's polling tick. Picks up scheduled uploads
     * whose firing time has arrived. Ordered by scheduledAt so the oldest
     * deferred posts go out first under load.
     */
    List<SocialUpload> findAllByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
            Status status, Instant cutoff);

    /**
     * Backing query for the ScheduledPostsPage list view — every still-
     * pending scheduled upload the user has queued, regardless of which
     * source (video or asset) it points at.
     */
    List<SocialUpload> findAllByCreatedByAndStatusOrderByScheduledAtAsc(String createdBy, Status status);
}