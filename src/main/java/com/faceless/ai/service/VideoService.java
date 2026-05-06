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
                .map(v -> new VideoSummaryDTO(
                        v.getId(),
                        v.getJobId(),
                        v.getTitle(),
                        v.getDescription(),
                        v.getDurationSeconds(),
                        v.getCreatedOn(),
                        "/api/videos/" + v.getId() + "/stream"))
                .toList();
    }

    public Video getVideo(UUID videoId) {
        return videoRepository.findById(videoId)
                .orElseThrow(() -> new IllegalArgumentException("Video not found: " + videoId));
    }
}