package com.faceless.ai.repository;

import com.faceless.ai.entity.Lesson;
import com.faceless.ai.entity.Status;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LessonRepository extends BaseRepository<Lesson, UUID> {

    List<Lesson> findByUserIdOrderByCreatedOnDesc(String userId);

    Optional<Lesson> findByIdAndUserId(UUID id, String userId);

    /** Used by the poller to advance scripting + rendering. */
    List<Lesson> findByStatusIn(List<Status> statuses);
}
