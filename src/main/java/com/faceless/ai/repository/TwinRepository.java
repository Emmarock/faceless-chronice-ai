package com.faceless.ai.repository;

import com.faceless.ai.entity.Status;
import com.faceless.ai.entity.Twin;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TwinRepository extends BaseRepository<Twin, UUID> {

    List<Twin> findByUserIdOrderByCreatedOnDesc(String userId);

    Optional<Twin> findByIdAndUserId(UUID id, String userId);

    /** Used by the poller to advance in-flight avatar training. */
    List<Twin> findByStatusIn(List<Status> statuses);
}
