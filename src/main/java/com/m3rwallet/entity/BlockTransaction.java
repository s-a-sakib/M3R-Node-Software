package com.m3rwallet.entity;

import lombok.*;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.sql.Timestamp;

/**
 * Linking entity representing a transaction included in a specific block.
 */
@Entity
@Table(name = "block_transactions", indexes = {
        @Index(name = "idx_tx_hash", columnList = "tx_hash"),
        @Index(name = "idx_sender", columnList = "sender_address"),
        @Index(name = "idx_recipient", columnList = "recipient_address"),
        @Index(name = "idx_block", columnList = "block_height")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_block_txindex", columnNames = {"block_height", "tx_index"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BlockTransaction {

    /** Primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Block height this transaction belongs to (foreign key column). */
    @Column(name = "block_height", nullable = false)
    private Long blockHeight;

    /** Keccak256 hash of the transaction (64 hex chars). */
    @Column(name = "tx_hash", length = 64, nullable = false, unique = true)
    private String txHash;

    /** Position inside the block (0-based). */
    @Column(name = "tx_index", nullable = false)
    private Integer txIndex;

    /** Sender address (Base58 or hex) */
    @Column(name = "sender_address", length = 64)
    private String senderAddress;

    /** Recipient address (Base58 or hex) */
    @Column(name = "recipient_address", length = 64)
    private String recipientAddress;

    /** Transaction value in smallest units. Use high-precision decimal for safety. */
    @Column(name = "value", precision = 38, scale = 0)
    private BigDecimal value;

    /** Total fee paid for the transaction (broadcast + consensus). */
    @Column(name = "total_fee")
    private Long totalFee;

    /** Fee allocated to the broadcasting node (20% by protocol). */
    @Column(name = "broadcast_fee")
    private Long broadcastFee;

    /** Fee allocated to the proposer/consensus (80%). */
    @Column(name = "consensus_fee")
    private Long consensusFee;

    /** Node address which originally broadcast this transaction. */
    @Column(name = "broadcaster_address", length = 64)
    private String broadcasterAddress;

    /** Sender nonce (sequence number). */
    @Column(name = "nonce")
    private Long nonce;

    /** When the transaction was created (unix ms). */
    @Column(name = "timestamp")
    private Long timestamp;

    /** ECDSA signature of the transaction (r||s hex). */
    @Column(name = "tx_signature", length = 128)
    private String txSignature;

    /** Sender compressed public key (66 hex chars). */
    @Column(name = "pub_key_compressed", length = 66)
    private String pubKeyCompressed;

    /** Transaction status. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private TxStatus status = TxStatus.PENDING;

    /** Auto-managed creation timestamp. */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;

    /** Many-to-one relationship to Block. The column `block_height` is the owning column. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "block_height", referencedColumnName = "block_height", insertable = false, updatable = false)
    private Block block;

    /** Transaction statuses for `BlockTransaction`. */
    public enum TxStatus {
        PENDING,
        CONFIRMED,
        FAILED
    }
}
