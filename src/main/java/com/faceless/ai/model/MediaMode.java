package com.faceless.ai.model;

/**
 * How a {@link Scene}'s visual is sourced and rendered.
 *
 * <ul>
 *   <li>{@link #IMAGES} — N stills (existing behaviour). The scene's
 *       {@code imageFiles} list holds the per-shot images; the renderer
 *       splits the voice duration evenly across them.</li>
 *   <li>{@link #VIDEO_CLIP} — one source video clip per scene. The scene's
 *       {@code sourceVideoFiles} list holds the clip URL(s); the renderer
 *       trims/loops the first clip to the voice duration and replaces its
 *       audio with the narration.</li>
 * </ul>
 *
 * <p>Defaults to {@link #IMAGES} so every existing job stays on the legacy
 * path with no migration.
 */
public enum MediaMode {
    IMAGES,
    VIDEO_CLIP
}