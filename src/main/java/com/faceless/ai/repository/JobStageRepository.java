package com.faceless.ai.repository;

import com.faceless.ai.entity.JobStage;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface JobStageRepository extends BaseRepository<JobStage, UUID> {

    Optional<JobStage> findByJobIdAndStageName(UUID jobId, String stageName);
}