package com.faceless.ai.service;

import com.faceless.ai.entity.Video;
import com.faceless.ai.model.VideoSummaryDTO;
import com.faceless.ai.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VideoService {

    private final VideoRepository videoRepository;

    public List<VideoSummaryDTO> listForUser(String userId) {
        return videoRepository.findAllByCreatedByOrderByCreatedOnDesc(userId).stream()
                .map(VideoService::toSummary)
                .toList();
    }

    /**
     * Look up the rendered video for a single job, scoped to the requesting
     * user. Used by the "open completed job" flow on the frontend so we don't
     * have to ship the entire video list just to display one entry.
     */
    public VideoSummaryDTO getForUserByJobId(String userId, UUID jobId) {
        return videoRepository.findByJobIdAndCreatedBy(jobId, userId)
                .map(VideoService::toSummary)
                .orElseThrow(() -> new IllegalArgumentException("Video not found for job: " + jobId));
    }

    public Video getVideo(UUID videoId) {
        return videoRepository.findById(videoId)
                .orElseThrow(() -> new IllegalArgumentException("Video not found: " + videoId));
    }

    private static VideoSummaryDTO toSummary(Video v) {
        return new VideoSummaryDTO(
                v.getId(),
                v.getJobId(),
                v.getTitle(),
                v.getDescription(),
                v.getDurationSeconds(),
                v.getCreatedOn(),
                "/api/videos/" + v.getId() + "/stream");
    }
}