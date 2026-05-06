package com.faceless.ai.entity;

public enum AssetType {
    IMAGE,
    /**
     * Raw input video clip (Pexels stock footage, AI-generated, or user upload)
     * used by the renderer when a scene's {@code mediaMode == VIDEO_CLIP}.
     * Distinct from {@link #VIDEO_CLIP}, which is the per-scene <em>rendered</em>
     * output (source video trimmed/looped to voice length, narration mixed in).
     */
    SOURCE_VIDEO,
    VOICE,
    VIDEO_CLIP,
    MUSIC,
    THUMBNAIL
}