package com.m3rwallet.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.sql.Timestamp;

@Entity
@Table(name = "validator_snapshots", indexes = {
        @Index(name = "idx_snapshot_hash", columnList = "snapshot_hash", unique = true),
        @Index(name = "idx_snap_net_height", columnList = "network, block_height")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistoricalValidatorSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snapshot_hash", length = 64, nullable = false, unique = true)
    private String snapshotHash;

    @Column(name = "network", length = 20, nullable = false)
    private String network;

    @Column(name = "block_height", nullable = false)
    private Long blockHeight;

    @Column(name = "captured_at", nullable = false)
    private Long capturedAt;

    @Column(name = "snapshot_data_json", columnDefinition = "LONGTEXT", nullable = false)
    private String snapshotDataJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;
}
