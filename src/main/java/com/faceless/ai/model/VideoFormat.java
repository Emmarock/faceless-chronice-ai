package com.faceless.ai.model;

/**
 * Picks the broad shape of a generated job.
 *
 * <ul>
 *   <li>{@link #REELS} — short-form (≤30s, exactly 1 scene). Used for
 *       TikTok / YouTube Shorts / Reels surfaces.</li>
 *   <li>{@link #VIDEO} — long-form (multi-scene, several minutes).
 *       The legacy default.</li>
 * </ul>
 */
public enum VideoFormat {
    REELS,
    VIDEO
}