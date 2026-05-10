package com.m3rwallet.repository;

import com.m3rwallet.entity.Receipt;
import com.m3rwallet.entity.Receipt.ReceiptStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReceiptRepository extends JpaRepository<Receipt, Long> {
    List<Receipt> findByBlockHeight(Long blockHeight);

    Receipt findByTxHash(String txHash);

    List<Receipt> findByStatus(ReceiptStatus status);
}
