// repository/ScriptRepository.java
package com.faceless.ai.repository;
import com.faceless.ai.entity.Script;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ScriptRepository extends BaseRepository<Script, UUID> {

    Optional<Script> findByJobId(UUID jobId);
}