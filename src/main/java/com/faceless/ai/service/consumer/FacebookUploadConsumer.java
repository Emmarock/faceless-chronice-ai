package com.faceless.ai.service.consumer;

import com.faceless.ai.entity.SocialPlatform;
import com.faceless.ai.entity.SocialUpload;
import com.faceless.ai.entity.Status;
import com.faceless.ai.entity.Video;
import com.faceless.ai.repository.SocialUploadRepository;
import com.faceless.ai.repository.VideoRepository;
import com.faceless.ai.service.S3StorageService;
import com.faceless.ai.service.VideoUploadRequest;
import com.faceless.ai.service.upload.FacebookUploadService;
import io.awspring.cloud.sqs.annotation.SqsListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class FacebookUploadConsumer {

    private final VideoRepository videoRepository;
    private final S3StorageService s3StorageService;
    private final FacebookUploadService facebookUploadService;
    private final SocialUploadRepository socialUploadRepository;

    @SqsListener(value = "${chronicleai.queue.facebook-upload}",
                 messageVisibilitySeconds = "900")
    public void consume(String videoIdStr) {
        log.info("Received Facebook upload request for video {}", videoIdStr);
        UUID videoId = UUID.fromString(videoIdStr);

        SocialUpload upload = claimUploadOrSkip(videoId);
        if (upload == null) return;

        try {
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

            upload.setProviderPostId(fbVideoId);
            upload.setStatus(Status.COMPLETED);
            upload.setUploadedAt(Instant.now());
            upload.setLastModifiedOn(Instant.now());
            socialUploadRepository.save(upload);

            log.info("Facebook upload complete for video {} (user {}) — fb video id {}", videoId, userId, fbVideoId);
        } catch (Exception e) {
            log.error("Facebook upload failed for video {}: {}", videoIdStr, e.getMessage(), e);
            upload.setStatus(Status.FAILED);
            upload.setLastModifiedOn(Instant.now());
            socialUploadRepository.save(upload);
            throw new RuntimeException(e);
        }
    }

    private SocialUpload claimUploadOrSkip(UUID videoId) {
        SocialUpload existing = socialUploadRepository
                .findFirstByVideoIdAndPlatform(videoId, SocialPlatform.FACEBOOK)
                .orElse(null);
        if (existing != null) {
            existing.setStatus(Status.PROCESSING);
            existing.setLastModifiedOn(Instant.now());
            return socialUploadRepository.save(existing);
        }
        SocialUpload claim = SocialUpload.builder()
                .id(UUID.randomUUID())
                .videoId(videoId)
                .platform(SocialPlatform.FACEBOOK)
                .status(Status.PROCESSING)
                .createdOn(Instant.now())
                .lastModifiedOn(Instant.now())
                .build();
        try {
            return socialUploadRepository.save(claim);
        } catch (DataIntegrityViolationException dup) {
            log.info("Skipping duplicate Facebook upload delivery for video {}", videoId);
            return null;
        }
    }
}