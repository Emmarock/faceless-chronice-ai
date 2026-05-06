package com.faceless.ai.service;

import com.faceless.ai.entity.SocialConnection;
import com.faceless.ai.entity.SocialPlatform;
import com.faceless.ai.model.SocialConnectionDTO;
import com.faceless.ai.model.SocialConnectionRequest;
import com.faceless.ai.repository.SocialConnectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SocialConnectionService {

    private final SocialConnectionRepository repository;

    public List<SocialConnectionDTO> listForUser(String userId) {
        return repository.findAllByUserId(userId).stream()
                .map(SocialConnectionDTO::from)
                .toList();
    }

    @Transactional
    public SocialConnectionDTO upsert(String userId, SocialConnectionRequest request) {
        SocialConnection connection = repository.findByUserIdAndPlatform(userId, request.platform())
                .orElseGet(() -> SocialConnection.builder()
                        .userId(userId)
                        .platform(request.platform())
                        .build());

        connection.setAccessToken(request.accessToken());
        connection.setRefreshToken(request.refreshToken());
        connection.setAccountHandle(request.accountHandle());
        connection.setExpiresAt(request.expiresAt());
        connection.setConnectedAt(Instant.now());

        return SocialConnectionDTO.from(repository.save(connection));
    }

    @Transactional
    public void disconnect(String userId, SocialPlatform platform) {
        repository.deleteByUserIdAndPlatform(userId, platform);
    }
}