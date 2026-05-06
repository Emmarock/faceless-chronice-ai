package com.faceless.ai.repository;

import com.faceless.ai.entity.SocialConnection;
import com.faceless.ai.entity.SocialPlatform;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SocialConnectionRepository extends BaseRepository<SocialConnection, UUID> {

    List<SocialConnection> findAllByUserId(String userId);

    Optional<SocialConnection> findByUserIdAndPlatform(String userId, SocialPlatform platform);

    void deleteByUserIdAndPlatform(String userId, SocialPlatform platform);
}