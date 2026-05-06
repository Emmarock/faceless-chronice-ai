package com.faceless.ai.service.consumer;

import com.faceless.ai.entity.Video;
import com.faceless.ai.repository.VideoRepository;
import com.faceless.ai.service.S3StorageService;
import com.faceless.ai.service.upload.TikTokUploadService;
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
public class TikTokUploadConsumer {

    private final VideoRepository videoRepository;
    private final S3StorageService s3StorageService;
    private final TikTokUploadService tikTokUploadService;

    @SqsListener(value = "${chronicleai.queue.tiktok-upload}",
                 messageVisibilitySeconds = "900")
    public void consume(String videoIdStr) {
        log.info("Received TikTok upload request for video {}", videoIdStr);
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
            String publishId = tikTokUploadService.uploadVideo(new VideoUploadRequest(
                    userId, local, video.getTitle(), video.getDescription()));
            log.info("TikTok upload complete for video {} (user {}) — publish id {}", videoId, userId, publishId);
        } catch (Exception e) {
            log.error("TikTok upload failed for video {}: {}", videoIdStr, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }
}