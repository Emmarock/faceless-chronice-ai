package com.faceless.ai.repository;

import com.faceless.ai.entity.Job;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface JobRepository extends BaseRepository<Job, UUID> {
    List<Job> findByCreatedByOrderByCreatedOnDesc(String createdBy);
}