package com.faceless.ai.repository;

import com.faceless.ai.entity.CreditLedger;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CreditLedgerRepository extends BaseRepository<CreditLedger, UUID> {

    /**
     * Used as the idempotency check on Stripe webhook replays — if we've
     * already written a ledger row for this event, swallow the duplicate.
     */
    Optional<CreditLedger> findByStripeReference(String stripeReference);
}