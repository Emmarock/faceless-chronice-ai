package com.faceless.ai.service.consumer;

import com.faceless.ai.entity.Status;
import com.faceless.ai.entity.Video;
import com.faceless.ai.entity.YouTubeUpload;
import com.faceless.ai.repository.VideoRepository;
import com.faceless.ai.repository.YouTubeUploadRepository;
import com.faceless.ai.service.S3StorageService;
import com.faceless.ai.service.VideoUploadRequest;
import com.faceless.ai.service.upload.YouTubeUploadService;
import io.awspring.cloud.sqs.annotation.SqsListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class YouTubeUploadConsumer {

    private final VideoRepository videoRepository;
    private final YouTubeUploadRepository youTubeUploadRepository;
    private final S3StorageService s3StorageService;
    private final YouTubeUploadService youTubeUploadService;

    /**
     * Persists progress in the YouTubeUpload row and lets the message be
     * acked via the auto-ack ON_SUCCESS policy. Failures are recorded as
     * FAILED in the DB then re-thrown so SQS retries via visibility timeout
     * up to maxReceiveCount before the message lands in the DLQ.
     */
    @SqsListener(value = "${chronicleai.queue.youtube-upload}",
                 messageVisibilitySeconds = "900")
    public void consume(String videoIdStr) {
        log.info("Received YouTube upload request for video {}", videoIdStr);

        UUID videoId = UUID.fromString(videoIdStr);

        YouTubeUpload upload = youTubeUploadRepository.findFirstByVideoId(videoId)
                .orElseGet(() -> YouTubeUpload.builder()
                        .id(UUID.randomUUID())
                        .videoId(videoId)
                        .status(Status.QUEUED)
                        .createdOn(Instant.now())
                        .lastModifiedOn(Instant.now())
                        .build());

        upload.setStatus(Status.PROCESSING);
        upload.setLastModifiedOn(Instant.now());
        youTubeUploadRepository.save(upload);

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

            upload.setYoutubeVideoId(youtubeVideoId);
            upload.setStatus(Status.COMPLETED);
            upload.setUploadedAt(Instant.now());
            upload.setLastModifiedOn(Instant.now());
            youTubeUploadRepository.save(upload);

            log.info("YouTube upload complete for video {} (user {}). YouTube ID: {}",
                    videoId, userId, youtubeVideoId);
        } catch (Exception e) {
            log.error("YouTube upload failed for video {}: {}", videoId, e.getMessage(), e);
            upload.setStatus(Status.FAILED);
            upload.setLastModifiedOn(Instant.now());
            youTubeUploadRepository.save(upload);
            throw new RuntimeException(e);
        }
    }
}