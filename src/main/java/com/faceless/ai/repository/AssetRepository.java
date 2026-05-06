// repository/AssetRepository.java
package com.faceless.ai.repository;
import com.faceless.ai.entity.Asset;
import com.faceless.ai.entity.AssetType;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AssetRepository extends BaseRepository<Asset, UUID> {

    List<Asset> findByJobId(UUID jobId);

    List<Asset> findByJobIdAndAssetType(UUID jobId, AssetType assetType);

    Optional<Asset> findFirstByJobIdAndAssetTypeAndMetadata(UUID jobId, AssetType assetType, String metadata);
}