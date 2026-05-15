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

    /**
     * Every asset owned by a user across all their jobs, plus any standalone
     * "library" rows ({@code jobId == null}) they uploaded directly. Used to
     * power the asset library page; ordered so the most recently created
     * appears first.
     */
    List<Asset> findByCreatedByOrderByCreatedOnDesc(String createdBy);

    List<Asset> findByCreatedByAndAssetTypeOrderByCreatedOnDesc(String createdBy, AssetType assetType);
}