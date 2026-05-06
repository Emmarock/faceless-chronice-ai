package com.faceless.ai.service.consumer;

import com.faceless.ai.entity.Video;
import com.faceless.ai.repository.VideoRepository;
import com.faceless.ai.service.upload.FacebookUploadService;
import com.faceless.ai.service.S3StorageService;
import com.faceless.ai.service.VideoUploadRequest;
import io.awspring.cloud.sqs.annotation.SqsListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class FacebookUploadConsumer {

    private final VideoRepository videoRepository;
    private final S3StorageService s3StorageService;
    private final FacebookUploadService facebookUploadService;

    @SqsListener(value = "${chronicleai.queue.facebook-upload}",
                 messageVisibilitySeconds = "900")
    public void consume(String videoIdStr) {
        log.info("Received Facebook upload request for video {}", videoIdStr);
        try {
            UUID videoId = UUID.fromString(videoIdStr);
            Video video = videoRepository.findById(videoId)
                    .orElseThrow(() -> new RuntimeException("Video not found: " + videoId));
            String userId = video.getCreatedBy();
            if (userId == null || userId.isBlank()) {
                throw new IllegalStateException(
                        "Video " + videoId + " has no createdBy — cannot resolve uploader.");
            }

            Path local = s3StorageService.downloadToTemp(video.getStorageUrl(), ".mp4");
            String fbVideoId = facebookUploadService.uploadVideo(new VideoUploadRequest(
                    userId, local, video.getTitle(), video.getDescription()));
            log.info("Facebook upload complete for video {} (user {}) — fb video id {}", videoId, userId, fbVideoId);
        } catch (Exception e) {
            log.error("Facebook upload failed for video {}: {}", videoIdStr, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }
}