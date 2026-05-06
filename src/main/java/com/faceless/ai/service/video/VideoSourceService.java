package com.faceless.ai.service.video;

import com.faceless.ai.service.image.ImageGenerationService;

import java.nio.file.Path;

/**
 * Provider abstraction for fetching <em>source</em> video clips that the
 * renderer will later trim/loop to the per-scene narration. Mirrors
 * {@link ImageGenerationService} but yields a single clip per scene rather
 * than N images.
 *
 * <p>First implementation is {@code PexelsVideoSourceService} (free stock
 * footage, same {@code PEXELS_KEY} as the image provider). A generative
 * provider (Sora / Veo) can be added later behind the same interface.
 */
public interface VideoSourceService {

    String VIDEO_OUTPUT_DIR = "./output/files/source_videos/";

    /**
     * Returns the prompt style this provider expects — short search queries
     * for stock APIs, descriptive prompts for generative ones. Mirrors
     * {@link ImageGenerationService.PromptStyle} so we can route prompts
     * through the same ChatGPT helper.
     */
    ImageGenerationService.PromptStyle preferredPromptStyle();

    /**
     * Fetch one source clip for a scene. Returns the local path of the
     * downloaded / generated MP4. Multiple alternates aren't returned —
     * a scene in video mode plays one clip, looped or trimmed as needed.
     */
    Path generateVideo(String prompt, String jobId, int sceneId) throws Exception;
}