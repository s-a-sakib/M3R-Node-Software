package com.m3rwallet.entity;

import lombok.*;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.apache.commons.codec.binary.Hex;
import com.m3rwallet.util.AddressUtil;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * JPA Entity representing a blockchain block header and metadata.
 * <p>
 * This entity stores all header fields required for consensus, indexing and
 * cross-references to transactions and receipts included in the block.
 */
@Entity
@Table(name = "blocks", indexes = {
        @Index(name = "idx_parent", columnList = "parent_block_hash"),
        @Index(name = "idx_slot", columnList = "slot_number"),
        @Index(name = "idx_proposer", columnList = "proposer_address"),
        @Index(name = "idx_finalized", columnList = "is_finalized")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Block {

    /** Sequential block number; assigned by consensus. Primary key. */
    @Id
    @Column(name = "block_height", nullable = false)
    private Long blockHeight;

    /** Keccak256 hex of block header (64 hex chars). Unique. */
    @Column(name = "block_hash", length = 64, nullable = false, unique = true)
    private String blockHash;

    /** Parent block hash (64 hex chars). */
    @Column(name = "parent_block_hash", length = 64)
    private String parentBlockHash;

    /** Slot number (15s slot window). */
    @Column(name = "slot_number", nullable = false)
    private Long slotNumber;

    /** Block header version. Small integer; defaults to 1. */
    @Column(name = "version", nullable = false)
    private Byte version = 1;

    @Column(length = 20, nullable = false)
    private String network = "mainnet";

    /** Wall-clock timestamp in milliseconds (Unix ms). */
    @Column(name = "timestamp", nullable = false)
    private Long timestamp;

    /** Nonce used for additional entropy (node-local). */
    @Column(name = "nonce", nullable = false)
    private Long nonce;

    /** Proposer (validator) address in Base58Check encoding. */
    @Column(name = "proposer_address", length = 64)
    private String proposerAddress;

    /** Proposer weight (double precision) at time of proposal. */
    @Column(name = "proposer_weight", nullable = false)
    private Double proposerWeight;

    /** ECDSA signature of the proposer over the serialized header (r||s hex). */
    @Column(name = "proposer_signature", length = 128)
    private String proposerSignature;

    /** Number of transactions included in this block. */
    @Column(name = "tx_count", nullable = false)
    private Integer txCount;

    /** Merkle root of transactions (Keccak256 hex, 64 chars). */
    @Column(name = "merkle_root", length = 64)
    private String merkleRoot;

    /** State root hash (Keccak256 hex, 64 chars). */
    @Column(name = "state_root", length = 64)
    private String stateRoot;

    /** Hash representing the validator set at this block (Keccak256 hex). */
    @Column(name = "validator_set_hash", length = 64)
    private String validatorSetHash;

    /** Whether the block has been finalized by consensus. */
    @Column(name = "is_finalized", nullable = false)
    private Boolean isFinalized = Boolean.FALSE;

    /** When the block was finalized (unix ms) */
    @Column(name = "finalized_at")
    private Long finalizedAt;

    /** When this node received the block (unix ms) */
    @Column(name = "received_at")
    private Long receivedAt;

    /** Whether consensus fees have been distributed for this block */
    @Column(name = "fee_distributed", nullable = false)
    private Boolean feeDistributed = Boolean.FALSE;

    /** Database insertion time; managed by Hibernate. */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;

    /** Transactions that belong to this block. */
    @OneToMany(mappedBy = "block", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BlockTransaction> transactions = new ArrayList<>();

    /** Receipts produced by execution of transactions in this block. */
    @OneToMany(mappedBy = "block", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Receipt> receipts = new ArrayList<>();

    /**
     * Serialize canonical header fields for ECDSA signing.
     * <p>
     * Field ordering and encoding is deterministic and uses big-endian for numbers.
     * The proposer address is decoded from Base58Check into its 20-byte payload
     * (if available) and appended as raw bytes.
     *
     * @return byte[] deterministic serialized header ready for hashing/signing
     */
    public byte[] serializeForSigning() {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // version: 1 byte
            out.write((version == null) ? 0 : version.byteValue());

            // blockHeight: 8 bytes big-endian
            out.write(longToBytes(blockHeight));

            // slotNumber: 8 bytes big-endian
            out.write(longToBytes(slotNumber));

            // parentBlockHash: 32 bytes raw (hex -> bytes). pad/trim to 32 bytes.
            out.write(hexToFixedBytes(parentBlockHash, 32));

            // merkleRoot: 32 bytes
            out.write(hexToFixedBytes(merkleRoot, 32));

            // stateRoot: 32 bytes
            out.write(hexToFixedBytes(stateRoot, 32));

            // timestamp: 8 bytes
            out.write(longToBytes(timestamp));

            // proposerAddress: decoded Base58 -> 20 bytes payload (if present)
            if (proposerAddress != null) {
                String hex20 = AddressUtil.decodeBase58ToHex20(proposerAddress);
                if (hex20 != null) {
                    out.write(Hex.decodeHex(hex20));
                }
            }

            // proposerWeight: 8 bytes IEEE 754 double
            ByteBuffer dbuf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
            dbuf.putDouble((proposerWeight == null) ? 0.0d : proposerWeight);
            out.write(dbuf.array());

            // validatorSetHash: 32 bytes
            out.write(hexToFixedBytes(validatorSetHash, 32));

            // nonce: 8 bytes
            out.write(longToBytes(nonce));

            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize block for signing: " + e.getMessage(), e);
        }
    }

    private byte[] longToBytes(Long v) {
        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        buf.putLong((v == null) ? 0L : v);
        return buf.array();
    }

    private byte[] hexToFixedBytes(String hex, int length) {
        try {
            if (hex == null) {
                return new byte[length];
            }
            byte[] b = Hex.decodeHex(hex);
            if (b.length == length) return b;
            byte[] out = new byte[length];
            if (b.length > length) {
                // trim leading bytes if longer
                System.arraycopy(b, b.length - length, out, 0, length);
            } else {
                // pad on the left with zeros
                System.arraycopy(b, 0, out, length - b.length, b.length);
            }
            return out;
        } catch (Exception e) {
            return new byte[length];
        }
    }
}
