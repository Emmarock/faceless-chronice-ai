package com.faceless.ai.repository;

import com.faceless.ai.entity.SocialPlatform;
import com.faceless.ai.entity.SocialUpload;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SocialUploadRepository extends BaseRepository<SocialUpload, UUID> {

    Optional<SocialUpload> findFirstByVideoIdAndPlatform(UUID videoId, SocialPlatform platform);
}