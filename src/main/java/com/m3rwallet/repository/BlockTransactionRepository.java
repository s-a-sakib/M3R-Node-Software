package com.m3rwallet.repository;

import com.m3rwallet.entity.BlockTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BlockTransactionRepository extends JpaRepository<BlockTransaction, Long> {
    BlockTransaction findByTxHash(String txHash);

    List<BlockTransaction> findByBlockHeight(Long blockHeight);

    List<BlockTransaction> findBySenderAddress(String senderAddress);

    List<BlockTransaction> findByBroadcasterAddress(String broadcasterAddress);

    void deleteByBlock(com.m3rwallet.entity.Block block);
}
