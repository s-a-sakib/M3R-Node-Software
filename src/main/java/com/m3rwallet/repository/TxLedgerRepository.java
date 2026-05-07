package com.m3rwallet.repository;

import com.m3rwallet.entity.TxLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TxLedgerRepository extends JpaRepository<TxLedger, Long> {

    /**
     * Fetch all ledger entries for a specific participant on a network,
     * ordered newest-first.
     */
    @Query("SELECT l FROM TxLedger l WHERE l.network = :network AND l.participantAddr = :addr ORDER BY l.createdAt DESC")
    List<TxLedger> findByNetworkAndParticipantAddr(
            @Param("network") String network,
            @Param("addr") String addr);

    /**
     * Check for an existing ledger entry for deduplication.
     * Used to ensure idempotency if the same transaction is ever processed twice.
     */
    @Query("SELECT l FROM TxLedger l WHERE l.network = :network AND l.txHash = :txHash AND l.participantAddr = :addr")
    Optional<TxLedger> findByNetworkAndTxHashAndParticipantAddr(
            @Param("network") String network,
            @Param("txHash") String txHash,
            @Param("addr") String addr);
}
