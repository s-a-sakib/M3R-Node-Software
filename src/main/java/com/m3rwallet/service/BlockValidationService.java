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
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

        // CHECK 1 - Required fields
        if (blockHeight == null) violations.add("MISSING_BLOCK_HEIGHT");
        if (blockHash == null || blockHash.isBlank()) violations.add("MISSING_BLOCK_HASH");
        if (proposerAddress == null || proposerAddress.isBlank()) violations.add("MISSING_PROPOSER");
        if (merkleRoot == null || merkleRoot.isBlank()) violations.add("MISSING_MERKLE_ROOT");
        if (slotNumber == null) violations.add("MISSING_SLOT");

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
        if (txHashes != null && !txHashes.isEmpty()) {
            try {
                String computed = computeMerkleRoot(txHashes);
                if (!computed.equals(merkleRoot)) {
                    violations.add("MERKLE_ROOT_MISMATCH");
                }
            } catch (Exception e) {
                log.warn("Merkle verification failed: {}", e.getMessage());
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

    @SuppressWarnings("unchecked")
    private List<String> getList(Map<String, Object> m, String key) {
        try {
            Object v = m.get(key);
            if (v instanceof List<?>) return (List<String>) v;
            return List.of();
        } catch (Exception e) {
            return List.of();
        }
    }
}
