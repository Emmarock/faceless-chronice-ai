package com.faceless.ai.repository;

import com.faceless.ai.entity.Plan;
import com.faceless.ai.entity.PlanCode;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PlanRepository extends BaseRepository<Plan, UUID> {

    Optional<Plan> findByCode(PlanCode code);

    List<Plan> findAllByOrderByMonthlyPriceCentsAsc();
}