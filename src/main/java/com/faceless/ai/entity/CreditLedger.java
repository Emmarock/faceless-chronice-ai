package com.faceless.ai.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.UUID;

/**
 * Append-only credit transactions.
 *
 * <p>Every change to {@link Subscription#getCreditBalance()} must be
 * accompanied by a row here. This is the audit trail — if support ever asks
 * "where did my credits go?", the answer is one ledger query away.
 *
 * <p>Rows are never updated or deleted. Mistakes are corrected by writing a
 * compensating {@link LedgerKind#MANUAL_ADJUST} or {@link LedgerKind#REFUND}
 * row, never by mutating history.
 */
@Entity
@Table(
        name = "credit_ledger",
        indexes = {
                @Index(name = "ix_credit_ledger_subscription", columnList = "subscription_id"),
                @Index(name = "ix_credit_ledger_job",          columnList = "job_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class CreditLedger extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subscription_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Subscription subscription;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private LedgerKind kind;

    /**
     * Signed credit delta. Positive for grants / refunds, negative for
     * consumption. The sum of {@code amount} across all rows for a
     * subscription equals the current balance.
     */
    @Column(nullable = false)
    private int amount;

    /** Free-text reason (e.g. "scene 3 image #2", "stripe invoice in_..."). */
    @Column(length = 255)
    private String memo;

    /** Optional job this row attributes to — useful for per-job cost reports. */
    @Column(name = "job_id")
    private UUID jobId;

    /** Optional Stripe invoice / event ID for idempotency on webhook replays. */
    @Column(name = "stripe_reference", length = 128)
    private String stripeReference;
}