package com.m3rwallet.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.sql.Timestamp;

/**
 * Tracks broadcast fee earnings per node.
 */
@Entity
@Table(name = "broadcaster_earnings", indexes = {
        @Index(name = "idx_broadcaster", columnList = "broadcaster_address"),
        @Index(name = "idx_block_height", columnList = "block_height")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BroadcasterEarning {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "broadcaster_address", length = 64, nullable = false)
    private String broadcasterAddress;

    @Column(name = "network", length = 20, nullable = false)
    private String network;

    @Column(name = "tx_hash", length = 64, nullable = false)
    private String txHash;

    /** earned broadcast fee in smallest units */
    @Column(name = "broadcast_fee")
    private Long broadcastFee;

    @Column(name = "block_height")
    private Long blockHeight;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;
}
