package com.m3rwallet.repository;

import com.m3rwallet.entity.Block;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface BlockRepository extends JpaRepository<Block, Long> {
    Block findByBlockHash(String blockHash);

    Block findBySlotNumber(Long slotNumber);

    List<Block> findByProposerAddress(String proposerAddress);

    List<Block> findAllByIsFinalized(Boolean isFinalized);

    @Override
    Optional<Block> findById(Long blockHeight);

    @Transactional(readOnly = true)
    Optional<Block> findTopByOrderByBlockHeightDesc();

    @Transactional(readOnly = true)
    List<Block> findByIsFinalized(Boolean isFinalized);

    @Transactional(readOnly = true)
    long countByIsFinalized(Boolean isFinalized);
}
