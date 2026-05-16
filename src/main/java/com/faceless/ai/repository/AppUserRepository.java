package com.faceless.ai.repository;

import com.faceless.ai.entity.AppUser;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AppUserRepository extends BaseRepository<AppUser, UUID> {

    Optional<AppUser> findByExternalId(String externalId);

    Optional<AppUser> findByEmail(String email);
}