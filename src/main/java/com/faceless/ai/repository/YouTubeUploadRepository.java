package com.faceless.ai.repository;

import com.faceless.ai.entity.YouTubeUpload;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface YouTubeUploadRepository extends BaseRepository<YouTubeUpload, UUID> {

    Optional<YouTubeUpload> findFirstByVideoId(UUID videoId);
}