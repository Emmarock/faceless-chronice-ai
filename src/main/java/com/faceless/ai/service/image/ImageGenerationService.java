package com.faceless.ai.service.image;

import java.nio.file.Path;
import java.util.List;

public interface ImageGenerationService {

    String IMAGE_OUTPUT_DIR = "./output/files/images/";

    enum PromptStyle {
        /** Short keyword query suitable for stock-photo search APIs (Pexels, Unsplash). */
        SEARCH_QUERY,
        /** Rich descriptive prompt suitable for generative models (OpenAI, Stable Diffusion). */
        DESCRIPTIVE
    }

    PromptStyle preferredPromptStyle();

    /**
     * Legacy single-prompt generation: produce {@code count} images from one
     * prompt (for stock-photo APIs the variation comes from the search
     * results themselves; for generative APIs every image is from the same
     * prompt and tends to look similar).
     *
     * <p>Prefer {@link #generateImagesForPrompts} when you want each image
     * to be a different shot.
     */
    List<Path> generateImages(String prompt, String jobId, int sceneId, int count) throws Exception;

    /**
     * Generate one image per prompt, in order. The output index in the
     * filename matches the prompt index in {@code prompts}, so callers can
     * align each returned {@link Path} with its source prompt.
     *
     * <p>Implementations should make one upstream call per prompt to
     * maximise visual diversity (don't reuse a single result set).
     */
    List<Path> generateImagesForPrompts(List<String> prompts, String jobId, int sceneId) throws Exception;
}