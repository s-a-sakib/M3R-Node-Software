package com.m3rwallet.service;

import com.m3rwallet.entity.Block;
import com.m3rwallet.entity.Validator;
import com.m3rwallet.repository.BlockRepository;
import com.m3rwallet.repository.ValidatorRepository;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.m3rwallet.util.CryptoUtil;
import com.m3rwallet.util.AddressUtil;

@Service
public class BlockValidationService {
    private static final Logger log = LoggerFactory.getLogger(BlockValidationService.class);

    @Autowired
    private BlockRepository blockRepo;

    @Autowired
    private ValidatorRepository validatorRepo;

    @Autowired(required = false)
    private ValidatorService validatorService;

    @Value("${app.blockchain.network:mainnet}")
    private String defaultNetwork;

    public record BlockValidationResult(boolean valid, String blockHash, Long blockHeight, List<String> violations) {}

    public BlockValidationResult validateReceivedBlock(Map<String, Object> payload, String network) {
        String effectiveNetwork = (network == null || network.isBlank()) ? defaultNetwork : network;
        List<String> violations = new ArrayList<>();

        Long blockHeight = getLong(payload, "blockHeight");
        String blockHash = getString(payload, "blockHash");
        String parentBlockHash = getString(payload, "parentBlockHash");
        String proposerAddress = getString(payload, "proposerAddress");
        String merkleRoot = getString(payload, "merkleRoot");
        Long slotNumber = getLong(payload, "slotNumber");
        Long timestamp = getLong(payload, "timestamp");
        List<String> txHashes = getList(payload, "transactions");
        Integer txCount = getInteger(payload, "txCount");

        // CHECK 1 - Required fields and format
        if (blockHeight == null) violations.add("MISSING_BLOCK_HEIGHT");
        if (blockHash == null || blockHash.isBlank()) {
            violations.add("MISSING_BLOCK_HASH");
        } else if (!isValidHex(blockHash, 32)) {
            violations.add("MALFORMED_BLOCK_HASH");
        }
        if (proposerAddress == null || proposerAddress.isBlank()) violations.add("MISSING_PROPOSER");
        if (merkleRoot == null || merkleRoot.isBlank()) {
            violations.add("MISSING_MERKLE_ROOT");
        } else if (!isValidHex(merkleRoot, 32)) {
            violations.add("MALFORMED_MERKLE_ROOT");
        }
        if (slotNumber == null) violations.add("MISSING_SLOT");

        String stateRoot = getString(payload, "stateRoot");
        if (stateRoot == null || stateRoot.isBlank()) {
            violations.add("MISSING_STATE_ROOT");
        } else if (!isValidHex(stateRoot, 32)) {
            violations.add("MALFORMED_STATE_ROOT");
        }

        String claimedValSetHash = getString(payload, "validatorSetHash");
        if (claimedValSetHash == null || claimedValSetHash.isBlank()) {
            violations.add("MISSING_VALIDATOR_SET_HASH");
        } else if (!isValidHex(claimedValSetHash, 32)) {
            violations.add("MALFORMED_VALIDATOR_SET_HASH");
        }

        if (parentBlockHash != null && !parentBlockHash.isBlank() && !isValidHex(parentBlockHash, 32)) {
            violations.add("MALFORMED_PARENT_BLOCK_HASH");
        }

        Double proposerWeight = getDouble(payload, "proposerWeight");
        if (proposerWeight == null || proposerWeight.isNaN() || proposerWeight.isInfinite() || proposerWeight < 0.0) {
            violations.add("INVALID_PROPOSER_WEIGHT");
        }

        // The block hash commits to the canonical header fields used by BlockProposalService.
        if (blockHash != null && blockHeight != null && slotNumber != null) {
            String recomputed = computeBlockHash(payload);
            if (recomputed.isBlank() || !recomputed.equalsIgnoreCase(blockHash)) {
                violations.add("BLOCK_HASH_MISMATCH");
            }
        }

        // CHECK 2 - Already known
        try {
            if (blockHeight != null && blockRepo.existsById(blockHeight)) {
                violations.add("ALREADY_KNOWN");
            }
        } catch (Exception e) {
            log.warn("Could not check existing block: {}", e.getMessage());
        }

        // CHECK 3 - Parent block exists / match
        if (blockHeight != null && blockHeight > 1) {
            try {
                Optional<Block> parent = blockRepo.findById(blockHeight - 1);
                if (parent.isEmpty()) {
                    violations.add("PARENT_NOT_FOUND");
                } else {
                    String knownParentHash = parent.get().getBlockHash();
                    if (parentBlockHash != null && !parentBlockHash.equals(knownParentHash)) {
                        violations.add("PARENT_HASH_MISMATCH");
                    }
                }
            } catch (Exception e) {
                log.warn("Parent check failed: {}", e.getMessage());
            }
        }

        // CHECK 4 - Proposer registered and active
        if (proposerAddress != null && !proposerAddress.isBlank()) {
            try {
                Optional<Validator> proposer = validatorRepo.findByAddressAndNetwork(proposerAddress, effectiveNetwork);
                if (proposer.isEmpty()) {
                    violations.add("PROPOSER_NOT_REGISTERED");
                } else if (proposer.get().getStatus() != Validator.ValidatorStatus.ACTIVE) {
                    violations.add("PROPOSER_NOT_ACTIVE");
                } else if (validatorService != null && proposerWeight != null) {
                    try {
                        double expectedWeight = validatorService.calculateWeight(proposer.get());
                        if (Math.abs(expectedWeight - proposerWeight) > 1e-6) {
                            violations.add("PROPOSER_WEIGHT_MISMATCH");
                        }
                    } catch (Exception e) {
                        log.warn("Proposer weight calculation failed: {}", e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("Proposer check failed: {}", e.getMessage());
            }
        }

        // CHECK 5 - Timestamp sanity
        long now = System.currentTimeMillis();
        if (timestamp != null) {
            if (timestamp > now + 30_000L) violations.add("TIMESTAMP_FUTURE");
            if (timestamp < now - 300_000L) violations.add("TIMESTAMP_TOO_OLD");
        }

        // CHECK 6 - Merkle root verify
        String expectedMerkle = computeMerkleRoot(txHashes == null ? List.of() : txHashes);
        if (merkleRoot != null && !expectedMerkle.equalsIgnoreCase(merkleRoot)) {
            violations.add("MERKLE_ROOT_MISMATCH");
        }
        int expectedTxCount = (txHashes == null) ? 0 : txHashes.size();
        if (txCount != null && txCount < 0) {
            violations.add("INVALID_TX_COUNT");
        } else if (txCount != null && txCount != expectedTxCount) {
            violations.add("TX_COUNT_MISMATCH");
        }

        // CHECK 7 - Slot Leader Check
        if (slotNumber != null && validatorService != null) {
            try {
                Validator leader = validatorService.selectValidatorForSlot(slotNumber, effectiveNetwork);
                if (leader == null) {
                    violations.add("SLOT_LEADER_LOOKUP_FAILED");
                } else if (proposerAddress != null && !leader.getAddress().equalsIgnoreCase(proposerAddress)) {
                    violations.add("PROPOSER_NOT_SLOT_LEADER");
                }
            } catch (Exception e) {
                log.warn("[SECURITY] Slot leader lookup failed for slot {}: {}", slotNumber, e.getMessage());
                violations.add("SLOT_LEADER_LOOKUP_FAILED");
            }
        }

        // CHECK 8 - Proposer Cryptographic Signature Verification
        String proposerSignature = getString(payload, "proposerSignature");
        String proposerPublicKey = getString(payload, "proposerPublicKey");
        if (proposerSignature == null || proposerSignature.isBlank()) {
            violations.add("MISSING_PROPOSER_SIGNATURE");
        } else if ("UNSIGNED".equalsIgnoreCase(proposerSignature)) {
            violations.add("PROPOSER_SIGNATURE_UNSIGNED");
        } else if ("PEER_BROADCAST".equalsIgnoreCase(proposerSignature)) {
            violations.add("PROPOSER_SIGNATURE_PEER_BROADCAST");
        } else if (proposerPublicKey == null || proposerPublicKey.isBlank()) {
            violations.add("MISSING_PROPOSER_PUBKEY");
        } else {
            try {
                byte[] pubKeyBytes = hexStringToBytes(proposerPublicKey);
                if (!CryptoUtil.isValidSecp256k1PublicKey(pubKeyBytes)) {
                    violations.add("INVALID_PROPOSER_PUBKEY");
                } else {
                    String derivedAddr = AddressUtil.deriveAddressFromPublicKey(pubKeyBytes);
                    if (proposerAddress == null || !proposerAddress.equals(derivedAddr)) {
                        violations.add("PROPOSER_PUBKEY_MISMATCH");
                    } else {
                        byte[] digest = Block.computePayloadHeaderSigningDigest(payload);
                        byte[] sigBytes = hexStringToBytes(proposerSignature);
                        boolean sigOk = CryptoUtil.verifyVoteSignature(digest, sigBytes, pubKeyBytes);
                        if (!sigOk) {
                            violations.add("PROPOSER_SIGNATURE_INVALID");
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[SECURITY] Proposer signature verification failed: {}", e.getMessage());
                violations.add("PROPOSER_SIGNATURE_VERIFY_ERROR");
            }
        }

        // CHECK 9 - Validator Set Commitment Hash
        if (claimedValSetHash != null && !claimedValSetHash.isBlank()) {
            boolean hashMatched = false;
            if (validatorRepo != null) {
                try {
                    List<Validator> active = validatorRepo.findByNetworkAndStatus(effectiveNetwork, Validator.ValidatorStatus.ACTIVE);
                    if (active != null && !active.isEmpty()) {
                        String expectedValSetHash = computeValidatorSetHash(active);
                        if (expectedValSetHash.equalsIgnoreCase(claimedValSetHash)) {
                            hashMatched = true;
                        }
                    }
                } catch (Exception e) {
                    log.warn("Validator set hash check error: {}", e.getMessage());
                }
            }
            if (!hashMatched && validatorService != null) {
                try {
                    if (validatorService.getSnapshotByHash(claimedValSetHash).isPresent()) {
                        hashMatched = true;
                        log.info("[VALIDATION] Validator set commitment matched historical snapshot {}", claimedValSetHash);
                    }
                } catch (Exception e) {
                    log.warn("Historical validator set snapshot lookup error: {}", e.getMessage());
                }
            }
            if (!hashMatched) {
                violations.add("VALIDATOR_SET_HASH_MISMATCH");
            }
        }

        // Determine validity: ignore ALREADY_KNOWN as non-fatal. Missing parent is fatal;
        // the periodic peer sync fetches blocks sequentially instead of storing orphans.
        boolean valid = violations.stream()
                .filter(v -> !"ALREADY_KNOWN".equals(v))
                .findAny()
                .isEmpty();

        if (valid) {
            log.info("Block {} validation PASSED", blockHeight);
        } else {
            log.warn("Block {} validation FAILED: {}", blockHeight, violations);
        }

        return new BlockValidationResult(valid, blockHash, blockHeight, violations);
    }

    public String computeMerkleRoot(List<String> txHashes) {
        try {
            if (txHashes == null || txHashes.isEmpty()) {
                Keccak.Digest256 k = new Keccak.Digest256();
                byte[] d = k.digest("empty".getBytes(StandardCharsets.UTF_8));
                return bytesToHex(d);
            }

            List<byte[]> level = new ArrayList<>();
            for (String h : txHashes) level.add(hexStringToBytes(h));
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            while (level.size() > 1) {
                List<byte[]> next = new ArrayList<>();
                for (int i = 0; i < level.size(); i += 2) {
                    byte[] left = level.get(i);
                    byte[] right = (i + 1 < level.size()) ? level.get(i + 1) : level.get(i);
                    byte[] combined = new byte[left.length + right.length];
                    System.arraycopy(left, 0, combined, 0, left.length);
                    System.arraycopy(right, 0, combined, left.length, right.length);
                    next.add(sha256.digest(combined));
                }
                level = next;
            }
            return bytesToHex(level.get(0));
        } catch (Exception e) {
            log.error("computeMerkleRoot failed", e);
            return "";
        }
    }

    public static String computeValidatorSetHash(List<Validator> activeValidators) {
        if (activeValidators == null || activeValidators.isEmpty()) {
            return "0".repeat(64);
        }
        List<Validator> sorted = new ArrayList<>(activeValidators);
        sorted.sort(Comparator.comparing(v -> v == null || v.getAddress() == null ? "" : v.getAddress().trim().toLowerCase()));
        StringBuilder sb = new StringBuilder();
        for (Validator v : sorted) {
            if (v == null || v.getAddress() == null || v.getAddress().isBlank()) continue;
            String addr = v.getAddress().trim();
            String stake = v.getStakedAmount() != null ? v.getStakedAmount().stripTrailingZeros().toPlainString() : "0";
            String rel = v.getReliabilityScoreScaled() != null ? v.getReliabilityScoreScaled().toString() : "0";
            sb.append(addr)
              .append(":")
              .append(stake)
              .append(":")
              .append(rel)
              .append(";");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            return "0".repeat(64);
        }
    }

    public String computeBlockHash(Map<String, Object> payload) {
        try {
            String base = getString(payload, "version") + "|" + getLong(payload, "blockHeight") + "|"
                    + getLong(payload, "slotNumber") + "|" + getString(payload, "parentBlockHash") + "|"
                    + getString(payload, "merkleRoot") + "|" + getLong(payload, "timestamp") + "|"
                    + getString(payload, "proposerAddress") + "|" + getLong(payload, "nonce");
            return bytesToHex(MessageDigest.getInstance("SHA-256")
                    .digest(base.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private static byte[] hexStringToBytes(String s) {
        if (s == null) return new byte[0];
        String hex = s.length() % 2 == 0 ? s : "0" + s;
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                + Character.digit(hex.charAt(i + 1), 16));
        return data;
    }

    private Long getLong(Map<String, Object> m, String key) {
        try {
            Object v = m.get(key);
            if (v == null) return null;
            if (v instanceof Number) return ((Number) v).longValue();
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            return null;
        }
    }

    private String getString(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public static boolean isValidHex(String s, int byteLength) {
        if (s == null) return false;
        String clean = s.trim();
        return clean.length() == byteLength * 2 && clean.matches("^[0-9a-fA-F]+$");
    }

    private Double getDouble(Map<String, Object> m, String key) {
        try {
            Object v = m.get(key);
            if (v == null) return null;
            if (v instanceof Number n) return n.doubleValue();
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private Integer getInteger(Map<String, Object> m, String key) {
        try {
            Object v = m.get(key);
            if (v == null) return null;
            if (v instanceof Number) return ((Number) v).intValue();
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return null;
        }
    }

    public byte[] serializePayloadForSigning(Map<String, Object> payload) {
        if (payload == null) {
            return new byte[0];
        }
        Byte version = toByte(payload.get("version"), (byte) 1);
        String network = getString(payload, "network");
        Long blockHeight = getLong(payload, "blockHeight");
        Long slotNumber = getLong(payload, "slotNumber");
        String parentBlockHash = getString(payload, "parentBlockHash");
        String merkleRoot = getString(payload, "merkleRoot");
        String stateRoot = getString(payload, "stateRoot");
        Long timestamp = getLong(payload, "timestamp");
        String proposerAddress = getString(payload, "proposerAddress");
        Double proposerWeight = toDouble(payload.get("proposerWeight"));
        String validatorSetHash = getString(payload, "validatorSetHash");
        Long nonce = getLong(payload, "nonce");
        Integer txCount = getInteger(payload, "txCount");

        return Block.serializeCanonicalHeader(
                version, network, blockHeight, slotNumber, parentBlockHash,
                merkleRoot, stateRoot, timestamp, proposerAddress, proposerWeight,
                validatorSetHash, nonce, txCount);
    }

    private Double toDouble(Object value) {
        try {
            if (value instanceof Number n) return n.doubleValue();
            return value == null ? 0.0d : Double.parseDouble(String.valueOf(value).trim());
        } catch (Exception e) {
            return 0.0d;
        }
    }

    private static byte[] longToBytes(Long val) {
        long v = (val == null) ? 0L : val;
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(8);
        buffer.putLong(v);
        return buffer.array();
    }

    private static byte[] hexToFixedBytes(String hex, int length) {
        byte[] result = new byte[length];
        if (hex == null || hex.isBlank()) return result;
        byte[] parsed = hexStringToBytes(hex);
        int copyLen = Math.min(parsed.length, length);
        System.arraycopy(parsed, 0, result, 0, copyLen);
        return result;
    }

    private static Byte toByte(Object value, byte defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.byteValue();
        try {
            return Byte.parseByte(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> getList(Map<String, Object> m, String key) {
        try {
            Object v = m.get(key);
            if (v instanceof List<?> rawList) {
                List<String> hashes = new ArrayList<>();
                for (Object item : rawList) {
                    if (item instanceof Map<?, ?> txMap) {
                        Object txHash = txMap.get("txHash");
                        if (txHash != null && !String.valueOf(txHash).isBlank()) {
                            hashes.add(String.valueOf(txHash));
                        }
                    } else if (item != null && !String.valueOf(item).isBlank()) {
                        hashes.add(String.valueOf(item));
                    }
                }
                return hashes;
            }
            return List.of();
        } catch (Exception e) {
            return List.of();
        }
    }
}
