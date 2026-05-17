package com.faceless.ai.repository;

import com.faceless.ai.entity.Subscription;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SubscriptionRepository extends BaseRepository<Subscription, UUID> {

    Optional<Subscription> findByAppUser_Id(UUID appUserId);

    Optional<Subscription> findByStripeCustomerId(String stripeCustomerId);

    Optional<Subscription> findByStripeSubscriptionId(String stripeSubscriptionId);
}