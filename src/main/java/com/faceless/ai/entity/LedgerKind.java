package com.faceless.ai.entity;

/**
 * What a {@link CreditLedger} row represents.
 *
 * <p>Convention: {@code amount &gt; 0} for credits (grants, refunds, manual
 * adjustments that add balance) and {@code amount &lt; 0} for debits
 * (consumption). The kind tells us <em>why</em> the balance moved; the
 * signed amount tells us <em>by how much</em>.
 *
 * <p>Adding a new debit kind is the recommended way to instrument a new
 * metered action — the per-action cost map in config keys off this enum.
 */
public enum LedgerKind {
    /** Monthly plan grant on subscription renewal / activation. */
    GRANT_MONTHLY,
    /** One-time grant on first AppUser sign-in (free tier welcome credits). */
    GRANT_WELCOME,
    /** Operator-initiated adjustment (support, retroactive credits). */
    MANUAL_ADJUST,
    /** Spent on script generation (ChatGPT call to produce the VideoScript). */
    DEBIT_SCRIPT,
    /** Spent on a single AI image. */
    DEBIT_IMAGE,
    /** Spent on a single scene's TTS narration. */
    DEBIT_VOICE,
    /** Spent on a single source-video clip fetch / generation. */
    DEBIT_VIDEO_CLIP,
    /** Spent on the final FFmpeg assembly + upload. */
    DEBIT_VIDEO_ASSEMBLY,
    /**
     * Flat upfront charge at job creation that covers the whole pipeline
     * (script + assets + assembly). Lets us fail fast on out-of-credits
     * users instead of mid-render. Granular DEBIT_* rows can supplement
     * later for finer accounting without changing this contract.
     */
    DEBIT_JOB_BUDGET,
    /** Refund of an earlier debit (e.g. failed pipeline stage). */
    REFUND
}