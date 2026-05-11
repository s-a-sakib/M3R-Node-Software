package com.m3rwallet.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.sql.Timestamp;

/**
 * Entity representing a Validator in the M3R network.
 */
@Entity
@Table(name = "validators", indexes = {
        @Index(name = "idx_status", columnList = "status"),
        @Index(name = "idx_network_status", columnList = "network,status")
}, uniqueConstraints = {
        @UniqueConstraint(name = "idx_network_address", columnNames = {"network", "address"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Validator {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "address", length = 64, nullable = false)
    private String address;

    @Column(name = "network", length = 20, nullable = false)
    private String network;

    /** Locked stake in smallest units. DECIMAL(38,0) */
    @Column(name = "staked_amount", precision = 38, scale = 0, nullable = false)
    private BigDecimal stakedAmount;

    /** Reliability score scaled by 1_000_000 (e.g. 0.9231 -> 923100) */
    @Column(name = "reliability_score_scaled", nullable = false)
    private Long reliabilityScoreScaled = 0L;

    @Column(name = "total_proposals", nullable = false)
    private Long totalProposals = 0L;

    @Column(name = "successful_proposals", nullable = false)
    private Long successfulProposals = 0L;

    @Column(name = "corrupted_proposals", nullable = false)
    private Long corruptedProposals = 0L;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ValidatorStatus status = ValidatorStatus.INACTIVE;

    /** Unix ms when registered */
    @Column(name = "registered_at", nullable = false)
    private Long registeredAt;

    /** Unix ms when last proposed a block */
    @Column(name = "last_proposal_at")
    private Long lastProposalAt;

    /** Epoch ms when jail ends (nullable) */
    @Column(name = "jailed_until")
    private Long jailedUntil;

    /** When unbonding started (nullable) */
    @Column(name = "unbonding_start_at")
    private Long unbondingStartAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Timestamp updatedAt;

    /**
     * Helper: returns reliability score as double (reliabilityScoreScaled / 1_000_000.0)
     */
    public double getReliabilityScore() {
        return (reliabilityScoreScaled == null) ? 0.0d : (reliabilityScoreScaled / 1_000_000.0d);
    }

    /**
     * Helper: sets reliability score (stores as scaled integer × 1_000_000)
     */
    public void setReliabilityScore(double r) {
        if (r < 0.0d) r = 0.0d;
        if (r > 1.0d) r = 1.0d;
        this.reliabilityScoreScaled = (long) (r * 1_000_000L);
    }

    /**
     * Helper: whether validator is ACTIVE
     */
    public boolean isActive() {
        return this.status == ValidatorStatus.ACTIVE;
    }

    /**
     * Helper: validator age in days
     */
    public long getValidatorAgeDays() {
        long now = System.currentTimeMillis();
        return (registeredAt == null) ? 0L : ((now - registeredAt) / 86_400_000L);
    }

    public enum ValidatorStatus {
        ACTIVE,
        JAILED,
        SLASHED,
        UNBONDING,
        INACTIVE
    }
}
