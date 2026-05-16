package com.faceless.ai.repository;

import com.faceless.ai.entity.AuthProvider;
import com.faceless.ai.entity.UserIdentity;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserIdentityRepository extends BaseRepository<UserIdentity, UUID> {

    Optional<UserIdentity> findByProviderAndProviderUserId(AuthProvider provider, String providerUserId);

    List<UserIdentity> findAllByAppUser_Id(UUID appUserId);
}