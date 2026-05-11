package com.m3rwallet.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.sql.Timestamp;

/**
 * Slash/punishment history for validators.
 */
@Entity
@Table(name = "slash_events", indexes = {
        @Index(name = "idx_validator", columnList = "validator_address"),
        @Index(name = "idx_block", columnList = "block_height")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SlashEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "validator_address", length = 64, nullable = false)
    private String validatorAddress;

    @Column(name = "network", length = 20, nullable = false)
    private String network;

    @Enumerated(EnumType.STRING)
    @Column(name = "slash_reason", length = 32, nullable = false)
    private SlashReason slashReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 16, nullable = false)
    private SlashSeverity severity;

    @Column(name = "stake_slashed", precision = 38, scale = 0)
    private BigDecimal stakeSlashed;

    /** Reliability before slash scaled by 1_000_000 */
    @Column(name = "reputation_before")
    private Long reputationBefore;

    @Column(name = "block_height")
    private Long blockHeight;

    @Column(name = "evidence", columnDefinition = "TEXT")
    private String evidence;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;

    public enum SlashReason {
        DOUBLE_SIGN,
        INVALID_PROPOSAL,
        MALFORMED_BLOCK,
        EQUIVOCATION
    }

    public enum SlashSeverity {
        MINOR,
        MEDIUM,
        SEVERE
    }
}
