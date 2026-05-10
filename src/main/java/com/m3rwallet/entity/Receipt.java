package com.m3rwallet.entity;

import lombok.*;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.sql.Timestamp;

/**
 * Execution receipt for a transaction included in a block.
 */
@Entity
@Table(name = "receipts", indexes = {
        @Index(name = "idx_block_tx", columnList = "block_height,tx_index"),
        @Index(name = "idx_tx_hash", columnList = "tx_hash")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Receipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Block height this receipt belongs to. */
    @Column(name = "block_height", nullable = false)
    private Long blockHeight;

    /** Transaction hash (for diagnostics) */
    @Column(name = "tx_hash", length = 64, nullable = false)
    private String txHash;

    /** Position of the transaction inside the block. */
    @Column(name = "tx_index", nullable = false)
    private Integer txIndex;

    /** Receipt status (SUCCESS / FAILED). */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ReceiptStatus status;

    /** Gas consumed by execution (optional). */
    @Column(name = "gas_used")
    private Long gasUsed;

    /** Any output payload (optional). */
    @Column(name = "output", length = 512)
    private String output;

    /** Error message when execution failed (optional). */
    @Column(name = "error_message", length = 512)
    private String errorMessage;

    /** Auto-managed creation timestamp. */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;

    /** Many-to-one link to the block (read-only). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "block_height", referencedColumnName = "block_height", insertable = false, updatable = false)
    private Block block;

    public enum ReceiptStatus {
        SUCCESS,
        FAILED
    }
}
