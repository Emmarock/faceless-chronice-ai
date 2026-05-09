package com.faceless.ai.service.consumer;
import com.faceless.ai.entity.SocialPlatform;
import com.faceless.ai.entity.SocialUpload;
import com.faceless.ai.entity.Status;
import com.faceless.ai.entity.Video;
import com.faceless.ai.model.SocialUploadEvent;
import com.faceless.ai.repository.SocialUploadRepository;
import com.faceless.ai.repository.VideoRepository;
import com.faceless.ai.service.S3StorageService;
import com.faceless.ai.service.VideoUploadRequest;
import com.faceless.ai.service.upload.VideoUploadService;
import io.awspring.cloud.sqs.annotation.SqsListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Component
@Slf4j
@RequiredArgsConstructor
public class SocialUploadConsumer {

    private final VideoRepository videoRepository;

    private final S3StorageService s3StorageService;

    private final List<VideoUploadService> services;

    private final SocialUploadRepository socialUploadRepository;

    @SqsListener(value = "${chronicleai.queue.social-upload}", messageVisibilitySeconds = "900")
    public void consume(SocialUploadEvent event) {
        UUID videoId = event.videoId();
        Set<SocialPlatform> requestedPlatforms = event.platforms();
        Video video = videoRepository.findById(videoId).orElseThrow();
        Path local = null;
        try {
            local = s3StorageService.downloadToTemp(video.getStorageUrl(), ".mp4");
        } catch (IOException e) {
            log.error("Unable to download mp4 file at {}", video.getStorageUrl(), e);
            return;
        }
        VideoUploadRequest request = new VideoUploadRequest(video.getCreatedBy(), local, video.getTitle(), video.getDescription());
        List<CompletableFuture<Void>> uploads =
                services.stream()
                        .filter(service -> requestedPlatforms.contains(service.platform()))
                        .map(service -> uploadToPlatform(service, videoId, request))
                        .toList();
        CompletableFuture.allOf(uploads.toArray(new CompletableFuture[0])).join();
    }

    private CompletableFuture<Void> uploadToPlatform(VideoUploadService service, UUID videoId, VideoUploadRequest request) {
        SocialPlatform platform = service.platform();

        SocialUpload upload =claimUploadOrSkip(videoId, platform);

        if (upload == null) {
            return CompletableFuture.completedFuture(null);
        }

        return service.uploadVideo(request)
                .thenAccept(providerPostId -> {
                    upload.setProviderPostId(providerPostId);
                    upload.setStatus(Status.COMPLETED);
                    upload.setUploadedAt(Instant.now());
                    upload.setLastModifiedOn(Instant.now());
                    socialUploadRepository.save(upload);
                    log.info("{} upload completed for video {}", platform, videoId);
                })
                .exceptionally(ex -> {
                    log.error("{} upload failed for video {}", platform, videoId, ex);
                    upload.setStatus(Status.FAILED);
                    upload.setLastModifiedOn(Instant.now());
                    socialUploadRepository.save(upload);
                    return null;
                });
    }

    private SocialUpload claimUploadOrSkip(UUID videoId, SocialPlatform platform) {
        SocialUpload existing =
                socialUploadRepository
                        .findFirstByVideoIdAndPlatform(videoId, platform)
                        .orElse(null);
        if (existing != null) {
            existing.setStatus(Status.PROCESSING);
            existing.setLastModifiedOn(Instant.now());
            return socialUploadRepository.save(existing);
        }
        SocialUpload claim =
                SocialUpload.builder()
                        .id(UUID.randomUUID())
                        .videoId(videoId)
                        .platform(platform)
                        .status(Status.PROCESSING)
                        .createdOn(Instant.now())
                        .lastModifiedOn(Instant.now())
                        .build();
        try {
            return socialUploadRepository.save(claim);
        } catch (DataIntegrityViolationException dup) {
            log.info("Skipping duplicate {} upload delivery for video {}", platform, videoId);
            return null;
        }
    }
}