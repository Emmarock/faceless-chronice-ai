// repository/AssetRepository.java
package com.faceless.ai.repository;
import com.faceless.ai.entity.Asset;
import com.faceless.ai.entity.AssetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.Collection;
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

    /**
     * Paginated variants used by the asset library page. Sort is driven by
     * the {@link Pageable} the caller supplies (typically {@code createdOn
     * DESC}). Spring Data emits a separate {@code count(*)} so the response
     * carries the total — useful for showing "page X of N" without a second
     * round-trip.
     */
    Page<Asset> findByCreatedBy(String createdBy, Pageable pageable);

    Page<Asset> findByCreatedByAndAssetType(String createdBy, AssetType assetType, Pageable pageable);

    /**
     * Paginated listing with a denylist of asset types — used by the user
     * library so internal/auxiliary types (voice, music, thumbnail) are
     * never exposed even on the unfiltered "All" view.
     */
    Page<Asset> findByCreatedByAndAssetTypeNotIn(String createdBy, Collection<AssetType> excluded, Pageable pageable);
}