package com.faceless.ai.model;

import com.faceless.ai.entity.Plan;
import com.faceless.ai.entity.PlanCode;

/**
 * Public view of a {@link Plan}. Stripe price IDs and other server-only
 * fields are intentionally absent — clients trigger checkout by plan code,
 * not by Stripe IDs.
 */
public record PlanDTO(
        PlanCode code,
        String displayName,
        String tagline,
        Integer monthlyPriceCents,
        Integer monthlyCreditGrant,
        boolean highlighted,
        boolean selfServe) {

    public static PlanDTO from(Plan p) {
        boolean selfServe = p.getCode() != PlanCode.FREE
                && p.getCode() != PlanCode.ENTERPRISE
                && p.getStripePriceId() != null
                && !p.getStripePriceId().isBlank();
        return new PlanDTO(
                p.getCode(),
                p.getDisplayName(),
                p.getTagline(),
                p.getMonthlyPriceCents(),
                p.getMonthlyCreditGrant(),
                p.isHighlighted(),
                selfServe);
    }
}