package com.faceless.ai.service.consumer;

import com.faceless.ai.entity.SocialPlatform;
import com.faceless.ai.entity.SocialUpload;
import com.faceless.ai.entity.Status;
import com.faceless.ai.entity.Video;
import com.faceless.ai.repository.SocialUploadRepository;
import com.faceless.ai.repository.VideoRepository;
import com.faceless.ai.service.S3StorageService;
import com.faceless.ai.service.VideoUploadRequest;
import com.faceless.ai.service.upload.YouTubeUploadService;
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
public class YouTubeUploadConsumer {

    private final VideoRepository videoRepository;
    private final S3StorageService s3StorageService;
    private final YouTubeUploadService youTubeUploadService;
    private final SocialUploadRepository socialUploadRepository;

    @SqsListener(value = "${chronicleai.queue.youtube-upload}",
                 messageVisibilitySeconds = "900")
    public void consume(String videoIdStr) {
        log.info("Received YouTube upload request for video {}", videoIdStr);
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

            Path localVideo = s3StorageService.downloadToTemp(video.getStorageUrl(), ".mp4");
            String youtubeVideoId = youTubeUploadService.uploadVideo(new VideoUploadRequest(
                    userId, localVideo, video.getTitle(), video.getDescription()));

            upload.setProviderPostId(youtubeVideoId);
            upload.setStatus(Status.COMPLETED);
            upload.setUploadedAt(Instant.now());
            upload.setLastModifiedOn(Instant.now());
            socialUploadRepository.save(upload);

            log.info("YouTube upload complete for video {} (user {}). YouTube ID: {}",
                    videoId, userId, youtubeVideoId);
        } catch (Exception e) {
            log.error("YouTube upload failed for video {}: {}", videoId, e.getMessage(), e);
            upload.setStatus(Status.FAILED);
            upload.setLastModifiedOn(Instant.now());
            socialUploadRepository.save(upload);
            throw new RuntimeException(e);
        }
    }

    private SocialUpload claimUploadOrSkip(UUID videoId) {
        SocialUpload existing = socialUploadRepository
                .findFirstByVideoIdAndPlatform(videoId, SocialPlatform.YOUTUBE)
                .orElse(null);
        if (existing != null) {
            existing.setStatus(Status.PROCESSING);
            existing.setLastModifiedOn(Instant.now());
            return socialUploadRepository.save(existing);
        }
        SocialUpload claim = SocialUpload.builder()
                .id(UUID.randomUUID())
                .videoId(videoId)
                .platform(SocialPlatform.YOUTUBE)
                .status(Status.PROCESSING)
                .createdOn(Instant.now())
                .lastModifiedOn(Instant.now())
                .build();
        try {
            return socialUploadRepository.save(claim);
        } catch (DataIntegrityViolationException dup) {
            log.info("Skipping duplicate YouTube upload delivery for video {}", videoId);
            return null;
        }
    }
}