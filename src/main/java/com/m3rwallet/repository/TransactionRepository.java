package com.m3rwallet.repository;

import com.m3rwallet.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    @Query("SELECT t FROM Transaction t WHERE t.network = :network AND t.hash = :hash")
    Optional<Transaction> findByNetworkAndHash(@Param("network") String network, @Param("hash") String hash);

    @Query("SELECT t FROM Transaction t WHERE t.network = :network ORDER BY t.createdAt DESC")
    List<Transaction> findByNetwork(@Param("network") String network);
}
