package com.m3rwallet.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.sql.Timestamp;

/**
 * Cached validator weight snapshot.
 */
@Entity
@Table(name = "validator_weights", indexes = {
        @Index(name = "idx_validator_network", columnList = "validator_address,network"),
        @Index(name = "idx_stale", columnList = "is_stale")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ValidatorWeight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "validator_address", length = 64, nullable = false)
    private String validatorAddress;

    @Column(name = "network", length = 20, nullable = false)
    private String network;

    /** Weight scaled by 1_000_000 (e.g. 1.818 -> 1818000) */
    @Column(name = "weight_scaled", nullable = false)
    private Long weightScaled;

    /** Reliability snapshot scaled by 1_000_000 */
    @Column(name = "reliability_score_scaled", nullable = false)
    private Long reliabilityScoreScaled;

    /** Staked amount snapshot at calculation time */
    @Column(name = "staked_amount_snapshot", precision = 38, scale = 0)
    private BigDecimal stakedAmountSnapshot;

    @Column(name = "calculated_at_block")
    private Long calculatedAtBlock;

    @Column(name = "calculated_at")
    private Long calculatedAt;

    @Builder.Default
    @Column(name = "is_stale", nullable = false)
    private Boolean isStale = Boolean.FALSE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;

    public double getWeight() {
        return (weightScaled == null) ? 0.0d : (weightScaled / 1_000_000.0d);
    }
}
