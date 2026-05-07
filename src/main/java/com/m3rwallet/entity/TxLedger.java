package com.m3rwallet.entity;

import lombok.*;
import jakarta.persistence.*;

/**
 * Participant-indexed transaction ledger.
 * One row is written per participant per transaction so any wallet can query
 * "what transactions involve me?" via the /tx/history endpoint.
 */
@Entity
@Table(name = "tx_ledger", indexes = {
    @Index(name = "idx_ledger_network_addr", columnList = "network,participant_addr"),
    @Index(name = "idx_ledger_network_hash_addr", columnList = "network,tx_hash,participant_addr", unique = true)
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TxLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** "mainnet", "testnet", or "legacy" */
    @Column(length = 20, nullable = false)
    private String network;

    /** SHA-256 of raw transaction bytes */
    @Column(name = "tx_hash", length = 64, nullable = false)
    private String txHash;

    /**
     * Hex-20 address of the participant this row describes.
     * Indexed so the /tx/history endpoint can do an efficient lookup.
     */
    @Column(name = "participant_addr", length = 100, nullable = false)
    private String participantAddr;

    /**
     * Role of this participant in the transaction:
     * SEND, RECEIVE,
     * ESCROW_CREATE, ESCROW_RECEIVE, ESCROW_ARBITER,
     * ESCROW_RELEASE, ESCROW_RELEASE_RECEIVED,
     * ESCROW_REFUND, ESCROW_REFUND_RECEIVED
     */
    @Column(length = 40, nullable = false)
    private String type;

    /** Transfer/escrow amount as decimal string (preserves precision) */
    @Column(length = 64, nullable = false)
    private String amount;

    /** Transaction fee paid (as decimal string) */
    @Column(length = 64, nullable = false)
    private String fee;

    /** Hex-20 sender address */
    @Column(name = "from_addr", length = 100, nullable = false)
    private String fromAddr;

    /** Hex-20 recipient / beneficiary address */
    @Column(name = "to_addr", length = 100, nullable = false)
    private String toAddr;

    /** Hex-64 escrow ID – null for plain transfers */
    @Column(name = "escrow_id", length = 64)
    private String escrowId;

    /** Always "CONFIRMED" (we only write ledger entries on success) */
    @Column(length = 20, nullable = false)
    private String status;

    /** Unix milliseconds when this entry was created */
    @Column(name = "created_at", nullable = false)
    private Long createdAt;
}
