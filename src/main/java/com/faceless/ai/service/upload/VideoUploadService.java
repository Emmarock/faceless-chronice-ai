package com.faceless.ai.service.upload;

import com.faceless.ai.entity.SocialPlatform;
import com.faceless.ai.service.VideoUploadRequest;

import java.util.concurrent.CompletableFuture;

/**
 * Uploads a rendered video to a social platform on behalf of a user.
 *
 * <p>Implementations are responsible for resolving the user's
 * {@code SocialConnection}, refreshing tokens when needed, and translating
 * {@link VideoUploadRequest} into the platform's native upload flow.
 */
public interface VideoUploadService {

    SocialPlatform platform();

    /**
     * @return platform-specific upload handle:
     * - YouTube video id
     * - TikTok publish_id
     * - Twitter tweet id
     * - Facebook video id
     */
    CompletableFuture<String> uploadVideo(VideoUploadRequest request);
}