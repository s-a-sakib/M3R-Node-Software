package com.m3rwallet.repository;

import com.m3rwallet.entity.HistoricalValidatorSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface ValidatorSnapshotRepository extends JpaRepository<HistoricalValidatorSnapshot, Long> {

    @Transactional(readOnly = true)
    Optional<HistoricalValidatorSnapshot> findBySnapshotHash(String snapshotHash);

    @Transactional(readOnly = true)
    Optional<HistoricalValidatorSnapshot> findByNetworkAndSnapshotHash(String network, String snapshotHash);

    @Transactional(readOnly = true)
    Optional<HistoricalValidatorSnapshot> findByNetworkAndBlockHeight(String network, Long blockHeight);

    @Transactional(readOnly = true)
    boolean existsBySnapshotHash(String snapshotHash);
}
