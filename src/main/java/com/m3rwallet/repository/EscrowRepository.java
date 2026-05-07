package com.m3rwallet.repository;

import com.m3rwallet.entity.Escrow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EscrowRepository extends JpaRepository<Escrow, Long> {
    @Query("SELECT e FROM Escrow e WHERE e.network = :network AND e.escrowId = :escrowId")
    Optional<Escrow> findByNetworkAndEscrowId(@Param("network") String network, @Param("escrowId") String escrowId);

    @Query("SELECT e FROM Escrow e WHERE e.network = :network ORDER BY e.createdAt DESC")
    List<Escrow> findByNetwork(@Param("network") String network);

    @Query(value = "SELECT * FROM escrows WHERE network = :network AND escrow_id = :escrowId FOR UPDATE", nativeQuery = true)
    Optional<Escrow> findForUpdate(@Param("network") String network, @Param("escrowId") String escrowId);
}
