package com.m3rwallet.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.sql.Timestamp;

/**
 * Tracks consensus fee earnings per proposer per block.
 */
@Entity
@Table(name = "proposer_earnings", indexes = {
        @Index(name = "idx_proposer", columnList = "proposer_address"),
        @Index(name = "idx_block_height", columnList = "block_height")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProposerEarning {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "proposer_address", length = 64, nullable = false)
    private String proposerAddress;

    @Column(name = "network", length = 20, nullable = false)
    private String network;

    @Column(name = "block_height", nullable = false)
    private Long blockHeight;

    @Column(name = "tx_count")
    private Integer txCount;

    /** total consensus fee in smallest units */
    @Column(name = "total_consensus_fee")
    private Long totalConsensusFee;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;
}
