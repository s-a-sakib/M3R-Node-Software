package com.m3rwallet.service;

import com.m3rwallet.config.ConsensusProperties;
import com.m3rwallet.entity.Block;
import com.m3rwallet.entity.BlockTransaction;
import com.m3rwallet.repository.BlockRepository;
import com.m3rwallet.repository.BlockTransactionRepository;
import com.m3rwallet.scheduler.BlockScheduler;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@Slf4j
public class BlockBroadcastService {

    @Value("${app.consensus.peers:}")
    private String peersConfig;

    @Autowired(required = false)
    private ConsensusProperties consensusProperties;

    @Autowired(required = false)
    private BlockValidationService blockValidationService;

    @Autowired(required = false)
    @Lazy
    private BlockScheduler blockScheduler;

    private final BlockRepository blockRepo;
    private final BlockTransactionRepository blockTxRepo;
    private final MempoolService mempoolService;
    private final RestTemplate restTemplate;

    private List<String> peerUrls = List.of();

    public BlockBroadcastService(BlockRepository blockRepo,
                                 BlockTransactionRepository blockTxRepo,
                                 MempoolService mempoolService,
                                 RestTemplate restTemplate) {
        this.blockRepo = blockRepo;
        this.blockTxRepo = blockTxRepo;
        this.mempoolService = mempoolService;
        this.restTemplate = restTemplate;
    }

    @PostConstruct
    void init() {
        try {
            List<String> configuredPeers = consensusProperties != null
                    ? consensusProperties.getPeers()
                    : List.of();
            if (configuredPeers != null && !configuredPeers.isEmpty()) {
                peerUrls = configuredPeers.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .map(this::stripTrailingSlash)
                        .distinct()
                        .collect(Collectors.toList());
            } else {
                peerUrls = Arrays.stream((peersConfig == null ? "" : peersConfig).split(","))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .map(this::stripTrailingSlash)
                        .distinct()
                        .collect(Collectors.toList());
            }
            log.info("Block broadcast peers: {}", peerUrls);
        } catch (Exception e) {
            peerUrls = List.of();
            log.warn("Block broadcast peer init failed: {}", e.getMessage());
        }
    }

    public void broadcastBlock(Block block, String network) {
        try {
            if (block == null) {
                return;
            }
            Map<String, Object> payload = buildBlockPayload(block);
            for (String peer : peerUrls) {
                try {
                    String url = peer + "/" + network + "/blocks/receive";
                    ResponseEntity<Map> response = restTemplate.postForEntity(url, payload, Map.class);
                    log.info("Block {} broadcast to {}: {}",
                            block.getBlockHeight(), peer, response.getStatusCode());
                } catch (Exception e) {
                    log.warn("Failed to broadcast block {} to {}: {}",
                            block.getBlockHeight(), peer, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Block broadcast failed for block {}: {}",
                    block != null ? block.getBlockHeight() : null, e.getMessage());
        }
    }

    public Map<String, Object> buildBlockPayload(Block block) {
        Map<String, Object> payload = new LinkedHashMap<>();
        try {
            List<String> txHashes = blockTxRepo.findByBlockHeight(block.getBlockHeight()).stream()
                    .map(BlockTransaction::getTxHash)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            payload.put("blockHeight", block.getBlockHeight());
            payload.put("blockHash", block.getBlockHash());
            payload.put("parentBlockHash", block.getParentBlockHash());
            payload.put("slotNumber", block.getSlotNumber());
            payload.put("version", block.getVersion());
            payload.put("proposerAddress", block.getProposerAddress());
            payload.put("proposerWeight", block.getProposerWeight());
            payload.put("proposerSignature", block.getProposerSignature());
            payload.put("merkleRoot", block.getMerkleRoot());
            payload.put("stateRoot", block.getStateRoot());
            payload.put("validatorSetHash", block.getValidatorSetHash());
            payload.put("txCount", block.getTxCount());
            payload.put("timestamp", block.getTimestamp());
            payload.put("nonce", block.getNonce());
            payload.put("network", block.getNetwork());
            payload.put("isFinalized", true);
            payload.put("finalizedAt", block.getFinalizedAt());
            payload.put("transactions", txHashes);
        } catch (Exception e) {
            log.warn("Could not build block payload for {}: {}", block.getBlockHeight(), e.getMessage());
        }
        return payload;
    }

    public void broadcastFinalizedBlock(Block block, String network) {
        try {
            if (block == null) return;
            Map<String, Object> payload = buildBlockPayload(block);
            payload.put("finalized", true);
            for (String peer : peerUrls) {
                try {
                    String url = peer + "/" + network + "/blocks/receive";
                    restTemplate.postForEntity(url, payload, Map.class);
                    log.info("Finalized block {} sent to {}", block.getBlockHeight(), peer);
                } catch (Exception e) {
                    log.warn("Broadcast to {} failed: {}", peer, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("broadcastFinalizedBlock failed: {}", e.getMessage());
        }
    }

    @Transactional
    public Map<String, Object> receiveBlock(Map<String, Object> payload, String network) {
        try {
            if (payload == null) {
                return Map.of("status", "REJECTED", "error", "Missing payload");
            }

            Long blockHeight = toLong(payload.get("blockHeight"));
            String blockHash = toStringValue(payload.get("blockHash"));
            String effectiveNetwork = toStringValue(payload.getOrDefault("network", network));
            if (effectiveNetwork == null || effectiveNetwork.isBlank()) {
                effectiveNetwork = network;
            }

            if (blockHeight == null || blockHash == null || blockHash.isBlank()) {
                return Map.of("status", "REJECTED", "error", "Missing blockHeight or blockHash");
            }

            if (blockRepo.findById(blockHeight).isPresent()) {
                return Map.of("status", "ALREADY_KNOWN", "blockHeight", blockHeight);
            }
            if (blockRepo.findByBlockHashAndNetwork(blockHash, effectiveNetwork).isPresent()) {
                return Map.of("status", "ALREADY_KNOWN", "blockHeight", blockHeight);
            }

            // === BLOCK VALIDATION ===
            if (blockValidationService != null) {
                var result = blockValidationService.validateReceivedBlock(payload, effectiveNetwork);

                if (result.violations().contains("ALREADY_KNOWN")) {
                    return Map.of(
                            "status", "ALREADY_KNOWN",
                            "blockHeight", result.blockHeight()
                    );
                }

                if (!result.valid()) {
                    log.warn("Rejected block from peer. Violations: {}", result.violations());
                    return Map.of(
                            "status", "REJECTED",
                            "violations", result.violations()
                    );
                }
            }
            // === END VALIDATION ===

            Block block = new Block();
            block.setBlockHeight(blockHeight);
            block.setBlockHash(blockHash);
            block.setParentBlockHash(toStringValue(payload.get("parentBlockHash")));
            block.setSlotNumber(defaultLong(toLong(payload.get("slotNumber")), 0L));
            block.setVersion(toByte(payload.get("version"), (byte) 1));
            block.setNetwork(effectiveNetwork);
            block.setTimestamp(defaultLong(toLong(payload.get("timestamp")), System.currentTimeMillis()));
            block.setNonce(defaultLong(toLong(payload.get("nonce")), 0L));
            block.setProposerAddress(toStringValue(payload.get("proposerAddress")));
            block.setProposerWeight(defaultDouble(toDouble(payload.get("proposerWeight")), 0.0d));
            block.setProposerSignature(toStringValue(payload.getOrDefault("proposerSignature", "PEER_BROADCAST")));
            block.setTxCount(defaultInteger(toInteger(payload.get("txCount")), 0));
            block.setMerkleRoot(toStringValue(payload.get("merkleRoot")));
            block.setStateRoot(toStringValue(payload.get("stateRoot")));
            block.setValidatorSetHash(toStringValue(payload.get("validatorSetHash")));
            Boolean isFinalized = Boolean.TRUE.equals(payload.get("finalized")) || Boolean.TRUE.equals(payload.get("isFinalized"));
            if (isFinalized) {
                block.setIsFinalized(true);
                block.setFinalizedAt(defaultLong(toLong(payload.get("finalizedAt")), System.currentTimeMillis()));
                // mark fees as already distributed (proposer will have done distribution)
                try { block.setFeeDistributed(true); } catch (Exception ignored) {}
            } else {
                block.setIsFinalized(false);
            }
            block.setReceivedAt(System.currentTimeMillis());

            blockRepo.save(block);
            try { if (isFinalized) blockRepo.save(block); } catch (Exception ignored) {}
            try {
                if (blockScheduler != null) {
                    blockScheduler.markSlotFilled(block.getSlotNumber());
                }
            } catch (Exception e) {
                log.warn("Could not mark slot filled for received block {}: {}", blockHeight, e.getMessage());
            }

            List<String> confirmedTxHashes = extractTxHashes(payload.get("transactions"));
            saveBlockTransactions(blockHeight, confirmedTxHashes);
            clearConfirmedTransactions(blockHeight, confirmedTxHashes);

            log.info("Received block {} from peer", blockHeight);
            return Map.of("status", "ACCEPTED", "blockHeight", blockHeight);
        } catch (Exception e) {
            log.warn("Receive block failed: {}", e.getMessage());
            return Map.of("status", "REJECTED", "error", e.getMessage());
        }
    }

    private void saveBlockTransactions(Long blockHeight, List<String> txHashes) {
        try {
            if (txHashes == null || txHashes.isEmpty()) {
                return;
            }
            List<BlockTransaction> txs = new ArrayList<>();
            int index = 0;
            for (String txHash : txHashes) {
                if (txHash == null || txHash.isBlank() || blockTxRepo.findByTxHash(txHash) != null) {
                    index++;
                    continue;
                }
                BlockTransaction tx = new BlockTransaction();
                tx.setBlockHeight(blockHeight);
                tx.setTxHash(txHash);
                tx.setTxIndex(index);
                tx.setStatus(BlockTransaction.TxStatus.CONFIRMED);
                txs.add(tx);
                index++;
            }
            if (!txs.isEmpty()) {
                blockTxRepo.saveAll(txs);
            }
        } catch (Exception e) {
            log.warn("Could not save received block transactions for block {}: {}", blockHeight, e.getMessage());
        }
    }

    private void clearConfirmedTransactions(Long blockHeight, List<String> confirmedTxHashes) {
        try {
            if (mempoolService != null && confirmedTxHashes != null && !confirmedTxHashes.isEmpty()) {
                mempoolService.removeTransactions(confirmedTxHashes);
                log.info("Cleared {} confirmed txs from mempool after receiving block {}",
                        confirmedTxHashes.size(), blockHeight);
            }
        } catch (Exception e) {
            log.warn("Could not clear mempool after receiving block {}: {}", blockHeight, e.getMessage());
        }
    }

    private List<String> extractTxHashes(Object rawTransactions) {
        try {
            if (!(rawTransactions instanceof List<?> rawList)) {
                return List.of();
            }
            return rawList.stream()
                    .map(this::toStringValue)
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Could not extract tx hashes: {}", e.getMessage());
            return List.of();
        }
    }

    private String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String toStringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long toLong(Object value) {
        try {
            if (value instanceof Number n) {
                return n.longValue();
            }
            return value == null ? null : Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private Integer toInteger(Object value) {
        try {
            if (value instanceof Number n) {
                return n.intValue();
            }
            return value == null ? null : Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private Double toDouble(Object value) {
        try {
            if (value instanceof Number n) {
                return n.doubleValue();
            }
            return value == null ? null : Double.parseDouble(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private Byte toByte(Object value, byte defaultValue) {
        Integer parsed = toInteger(value);
        return parsed == null ? defaultValue : parsed.byteValue();
    }

    private Long defaultLong(Long value, long defaultValue) {
        return value == null ? defaultValue : value;
    }

    private Integer defaultInteger(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private Double defaultDouble(Double value, double defaultValue) {
        return value == null ? defaultValue : value;
    }
}
