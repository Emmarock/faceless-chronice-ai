// repository/VideoRepository.java
package com.faceless.ai.repository;

import com.faceless.ai.entity.Video;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VideoRepository extends BaseRepository<Video, UUID> {

    Optional<Video> findByJobId(UUID jobId);

    Optional<Video> findByJobIdAndCreatedBy(UUID jobId, String createdBy);

    List<Video> findAllByCreatedByOrderByCreatedOnDesc(String createdBy);
}