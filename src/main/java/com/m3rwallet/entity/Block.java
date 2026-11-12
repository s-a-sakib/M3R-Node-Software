package com.m3rwallet.entity;

import lombok.*;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.apache.commons.codec.binary.Hex;
import com.m3rwallet.util.AddressUtil;
import com.m3rwallet.util.CryptoUtil;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    /** Proposer compressed public key (33-byte secp256k1 hex, 66 chars). */
    @Column(name = "proposer_public_key", length = 66)
    private String proposerPublicKey;

    /** Consensus certificate JSON string validating this block. */
    @Column(name = "consensus_certificate", columnDefinition = "LONGTEXT")
    private String consensusCertificate;

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
    public static final String CANONICAL_DOMAIN_SEPARATOR = "M3R_BLOCK_HEADER_V1";

    /**
     * Serialize canonical header fields for deterministic ECDSA signing and hashing.
     */
    public static byte[] serializeCanonicalHeader(
            Byte version,
            String network,
            Long blockHeight,
            Long slotNumber,
            String parentBlockHash,
            String merkleRoot,
            String stateRoot,
            Long timestamp,
            String proposerAddress,
            Double proposerWeight,
            String validatorSetHash,
            Long nonce,
            Integer txCount
    ) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // 1. Domain separator
            out.write(CANONICAL_DOMAIN_SEPARATOR.getBytes(StandardCharsets.UTF_8));
            out.write((byte) 0x00);

            // 2. version: 1 byte
            if (version == null) {
                throw new IllegalArgumentException("Canonical header version cannot be null");
            }
            out.write(version.byteValue());

            // 3. network: length-prefixed (2 bytes big-endian) + UTF-8
            if (network == null || network.isBlank()) {
                throw new IllegalArgumentException("Canonical header network cannot be null or blank");
            }
            String netStr = network.trim().toLowerCase();
            byte[] netBytes = netStr.getBytes(StandardCharsets.UTF_8);
            ByteBuffer netLen = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN);
            netLen.putShort((short) netBytes.length);
            out.write(netLen.array());
            out.write(netBytes);

            // 4. blockHeight: 8 bytes big-endian
            out.write(toLongBytes(blockHeight, "blockHeight"));

            // 5. slotNumber: 8 bytes big-endian
            out.write(toLongBytes(slotNumber, "slotNumber"));

            // 6. parentBlockHash: 32 bytes
            out.write(hexToFixedBytes(parentBlockHash, 32, "parentBlockHash"));

            // 7. merkleRoot: 32 bytes
            out.write(hexToFixedBytes(merkleRoot, 32, "merkleRoot"));

            // 8. stateRoot: 32 bytes
            out.write(hexToFixedBytes(stateRoot, 32, "stateRoot"));

            // 9. timestamp: 8 bytes big-endian
            out.write(toLongBytes(timestamp, "timestamp"));

            // 10. proposerAddress: 20 bytes payload (Base58 decoded or fixed 20 bytes)
            if (proposerAddress == null || proposerAddress.isBlank()) {
                throw new IllegalArgumentException("Canonical header proposerAddress cannot be null or blank");
            }
            byte[] proposerBytes = new byte[20];
            String hex20 = AddressUtil.decodeBase58ToHex20(proposerAddress.trim());
            if (hex20 != null && hex20.length() == 40) {
                byte[] decoded = Hex.decodeHex(hex20);
                System.arraycopy(decoded, 0, proposerBytes, 0, 20);
            } else {
                String norm = AddressUtil.normalizeAddr(proposerAddress);
                if (norm != null && norm.length() == 40) {
                    byte[] decoded = Hex.decodeHex(norm);
                    System.arraycopy(decoded, 0, proposerBytes, 0, 20);
                } else {
                    throw new IllegalArgumentException("Canonical header proposerAddress must decode to exactly 20 bytes: " + proposerAddress);
                }
            }
            out.write(proposerBytes);

            // 11. proposerWeight: 8 bytes IEEE 754 double (big-endian)
            if (proposerWeight == null || proposerWeight.isNaN() || proposerWeight.isInfinite() || proposerWeight < 0.0d) {
                throw new IllegalArgumentException("Canonical header proposerWeight must be a valid non-negative number");
            }
            ByteBuffer dbuf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
            dbuf.putDouble(proposerWeight);
            out.write(dbuf.array());

            // 12. validatorSetHash: 32 bytes
            out.write(hexToFixedBytes(validatorSetHash, 32, "validatorSetHash"));

            // 13. nonce: 8 bytes big-endian
            out.write(toLongBytes(nonce, "nonce"));

            // 14. txCount: 4 bytes big-endian
            if (txCount == null || txCount < 0) {
                throw new IllegalArgumentException("Canonical header txCount cannot be null or negative");
            }
            ByteBuffer txBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
            txBuf.putInt(txCount);
            out.write(txBuf.array());

            return out.toByteArray();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize canonical block header: " + e.getMessage(), e);
        }
    }

    public static byte[] computeHeaderDigest(
            Byte version,
            String network,
            Long blockHeight,
            Long slotNumber,
            String parentBlockHash,
            String merkleRoot,
            String stateRoot,
            Long timestamp,
            String proposerAddress,
            Double proposerWeight,
            String validatorSetHash,
            Long nonce,
            Integer txCount
    ) {
        byte[] bytes = serializeCanonicalHeader(
                version, network, blockHeight, slotNumber, parentBlockHash,
                merkleRoot, stateRoot, timestamp, proposerAddress, proposerWeight,
                validatorSetHash, nonce, txCount);
        return CryptoUtil.keccak256(bytes);
    }

    public byte[] computeHeaderSigningDigest() {
        return computeHeaderDigest(
                version, network, blockHeight, slotNumber, parentBlockHash,
                merkleRoot, stateRoot, timestamp, proposerAddress, proposerWeight,
                validatorSetHash, nonce, txCount);
    }

    public byte[] serializeForSigning() {
        return serializeCanonicalHeader(
                version, network, blockHeight, slotNumber, parentBlockHash,
                merkleRoot, stateRoot, timestamp, proposerAddress, proposerWeight,
                validatorSetHash, nonce, txCount);
    }

    public static byte[] computePayloadHeaderSigningDigest(Map<String, Object> payload) {
        if (payload == null) {
            throw new IllegalArgumentException("Payload cannot be null");
        }
        Byte version = extractByte(payload.get("version"), (byte) 1);
        String network = extractString(payload.get("network"));
        Long blockHeight = extractLong(payload.get("blockHeight"));
        Long slotNumber = extractLong(payload.get("slotNumber"));
        String parentBlockHash = extractString(payload.get("parentBlockHash"));
        String merkleRoot = extractString(payload.get("merkleRoot"));
        String stateRoot = extractString(payload.get("stateRoot"));
        Long timestamp = extractLong(payload.get("timestamp"));
        String proposerAddress = extractString(payload.get("proposerAddress"));
        Double proposerWeight = extractDouble(payload.get("proposerWeight"), null);
        String validatorSetHash = extractString(payload.get("validatorSetHash"));
        Long nonce = extractLong(payload.get("nonce"));
        Integer txCount = extractInteger(payload.get("txCount"), null);

        return computeHeaderDigest(
                version, network, blockHeight, slotNumber, parentBlockHash,
                merkleRoot, stateRoot, timestamp, proposerAddress, proposerWeight,
                validatorSetHash, nonce, txCount);
    }

    private static byte[] toLongBytes(Long v, String fieldName) {
        if (v == null) {
            throw new IllegalArgumentException("Canonical header " + fieldName + " cannot be null");
        }
        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        buf.putLong(v);
        return buf.array();
    }

    private static byte[] hexToFixedBytes(String hex, int length, String fieldName) {
        if (hex == null || hex.isBlank()) {
            throw new IllegalArgumentException("Canonical header " + fieldName + " cannot be null or blank");
        }
        String clean = hex.trim();
        if (clean.length() != length * 2) {
            throw new IllegalArgumentException("Canonical header " + fieldName + " length must be exactly " + (length * 2) + " hex characters, got " + clean.length());
        }
        if (!clean.matches("^[0-9a-fA-F]+$")) {
            throw new IllegalArgumentException("Canonical header " + fieldName + " contains invalid non-hex characters");
        }
        try {
            return Hex.decodeHex(clean);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to decode hex for " + fieldName + ": " + e.getMessage(), e);
        }
    }

    private static String extractString(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }

    private static Long extractLong(Object v) {
        try {
            if (v instanceof Number n) return n.longValue();
            return v == null ? null : Long.parseLong(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer extractInteger(Object v, Integer defaultValue) {
        try {
            if (v instanceof Number n) return n.intValue();
            return v == null ? defaultValue : Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static Double extractDouble(Object v, Double defaultValue) {
        try {
            if (v instanceof Number n) return n.doubleValue();
            return v == null ? defaultValue : Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static Byte extractByte(Object v, Byte defaultValue) {
        try {
            if (v instanceof Number n) return n.byteValue();
            return v == null ? defaultValue : Byte.parseByte(String.valueOf(v).trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
