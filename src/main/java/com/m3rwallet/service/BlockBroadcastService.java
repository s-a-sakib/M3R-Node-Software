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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import com.m3rwallet.entity.Validator;
import com.m3rwallet.util.CryptoUtil;
import com.m3rwallet.util.AddressUtil;

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
    private com.m3rwallet.repository.ValidatorRepository validatorRepo;

    @Autowired(required = false)
    private ValidatorService validatorService;

    @Autowired(required = false)
    private BlockConsensusVoteService blockConsensusVoteService;

    @Autowired(required = false)
    private NodeIdentityService nodeIdentityService;

    @Autowired(required = false)
    @Lazy
    private BlockScheduler blockScheduler;

    private final BlockRepository blockRepo;
    private final BlockTransactionRepository blockTxRepo;
    private final MempoolService mempoolService;
    private final FeeDistributionService feeDistributionService;
    private final RestTemplate restTemplate;

    private List<String> peerUrls = List.of();
    private final Map<String, Map<String, Object>> unfinalizedProposals = new java.util.concurrent.ConcurrentHashMap<>();

    private String proposalKey(String network, Long height, String hash) {
        String net = (network == null || network.isBlank()) ? "mainnet" : network.trim().toLowerCase();
        String h = (hash == null) ? "" : hash.trim().toLowerCase();
        return net + ":" + height + ":" + h;
    }

    public Map<String, Map<String, Object>> getUnfinalizedProposals() {
        return java.util.Collections.unmodifiableMap(unfinalizedProposals);
    }

    public BlockBroadcastService(BlockRepository blockRepo,
                                 BlockTransactionRepository blockTxRepo,
                                 MempoolService mempoolService,
                                 FeeDistributionService feeDistributionService,
                                 RestTemplate restTemplate) {
        this.blockRepo = blockRepo;
        this.blockTxRepo = blockTxRepo;
        this.mempoolService = mempoolService;
        this.feeDistributionService = feeDistributionService;
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
            List<Map<String, Object>> txs = blockTxRepo.findByBlockHeight(block.getBlockHeight()).stream()
                    .filter(tx -> tx.getTxHash() != null)
                    .map(this::toPayloadTransaction)
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
            payload.put("feeDistributed", Boolean.TRUE.equals(block.getFeeDistributed()));
            payload.put("transactions", txs);

            if (nodeIdentityService != null && nodeIdentityService.getNodePublicKey() != null) {
                payload.put("proposerPublicKey", nodeIdentityService.getNodePublicKey());
            }
            if (blockConsensusVoteService != null) {
                Map<String, Object> cert = blockConsensusVoteService.getCertificate(
                        block.getNetwork(), block.getBlockHeight(), block.getBlockHash());
                if (cert == null && blockConsensusVoteService.getLastCertificate() != null) {
                    Map<String, Object> last = blockConsensusVoteService.getLastCertificate();
                    if (Objects.equals(last.get("blockHeight"), block.getBlockHeight())
                            && Objects.equals(last.get("blockHash"), block.getBlockHash())) {
                        cert = last;
                    }
                }
                if (cert != null) {
                    payload.put("consensusCertificate", cert);
                }
            }
        } catch (Exception e) {
            log.warn("Could not build block payload for {}: {}", block.getBlockHeight(), e.getMessage());
        }
        return payload;
    }

    public void broadcastFinalizedBlock(Block block, String network) {
        if (block == null) return;
        try {
            Map<String, Object> payload = buildBlockPayload(block);
            payload.put("finalized", true);
            payload.put("isFinalized", true);
            if (blockConsensusVoteService != null) {
                Map<String, Object> cert = blockConsensusVoteService.getCertificate(
                        network, block.getBlockHeight(), block.getBlockHash());
                if (cert != null) {
                    payload.put("consensusCertificate", cert);
                }
            }

            log.info("[BROADCAST] Sending finalized block {} (hash={}) to {} peers", 
                     block.getBlockHeight(), block.getBlockHash(), peerUrls.size());

            String self = getSelfUrl();
            for (String peer : peerUrls) {
                if (peer.equals(self)) continue; // avoid self
                try {
                    String url = peer + "/" + network + "/blocks/receive";
                    ResponseEntity<Map> response = restTemplate.postForEntity(url, payload, Map.class);
                    log.info("[BROADCAST] Block {} → {} : {}", block.getBlockHeight(), peer, response.getStatusCode());
                } catch (Exception e) {
                    log.warn("[BROADCAST] Failed to send block {} to {}: {}", block.getBlockHeight(), peer, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[BROADCAST] Finalized block broadcast failed", e);
        }
    }

    @Value("${app.node.self-url:}")
    private String selfUrlProp;

    private String getSelfUrl() {
        try {
            String s = (selfUrlProp == null || selfUrlProp.isBlank())
                    ? "http://localhost:" + System.getProperty("server.port", "3000")
                    : selfUrlProp;
            return stripTrailingSlash(s);
        } catch (Exception e) {
            return "http://localhost:" + System.getProperty("server.port", "3000");
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

            Optional<Block> existingAtHeight = blockRepo.findById(blockHeight);
            if (existingAtHeight.isPresent()) {
                if (Objects.equals(existingAtHeight.get().getBlockHash(), blockHash)) {
                    return Map.of("status", "ALREADY_KNOWN", "blockHeight", blockHeight);
                }
                // Different hash at same height — report fork conflict and let sync resolve it
                log.warn("[RECEIVE] Fork conflict at height {} — local={} incoming={}",
                        blockHeight, existingAtHeight.get().getBlockHash(), blockHash);
                return Map.of("status", "FORK_CONFLICT", "blockHeight", blockHeight,
                        "localHash", existingAtHeight.get().getBlockHash());
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

            Boolean claimedFinalized = Boolean.TRUE.equals(payload.get("finalized")) || Boolean.TRUE.equals(payload.get("isFinalized"));
            boolean certificateValid = verifyConsensusCertificate(payload, effectiveNetwork);

            if (claimedFinalized && !certificateValid) {
                log.warn("[SECURITY] Block {} claimed finalized=true without verifiable consensus certificate. Failing closed (REJECTED).", blockHeight);
                return Map.of("status", "REJECTED", "error", "Invalid or missing consensus certificate for claimed finalized block");
            }

            if (!claimedFinalized) {
                // Pre-consensus proposal propagation: stage in memory, NEVER touch canonical chain, DB, or mempool
                unfinalizedProposals.put(proposalKey(effectiveNetwork, blockHeight, blockHash), payload);
                log.info("[PROPOSAL] Staged unfinalized block proposal {} (hash={}) in memory. Canonical state untouched.", blockHeight, blockHash);
                return Map.of("status", "STAGED_UNFINALIZED", "blockHeight", blockHeight, "blockHash", blockHash);
            }

            // At this point, claimedFinalized is true AND certificateValid is true!
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
            block.setProposerSignature(toStringValue(payload.getOrDefault("proposerSignature", "UNSIGNED")));
            block.setProposerPublicKey(toStringValue(payload.get("proposerPublicKey")));
            block.setTxCount(defaultInteger(toInteger(payload.get("txCount")), 0));
            block.setMerkleRoot(toStringValue(payload.get("merkleRoot")));
            block.setStateRoot(toStringValue(payload.get("stateRoot")));
            block.setValidatorSetHash(toStringValue(payload.get("validatorSetHash")));
            block.setIsFinalized(true);
            block.setFinalizedAt(defaultLong(toLong(payload.get("finalizedAt")), System.currentTimeMillis()));
            block.setReceivedAt(System.currentTimeMillis());

            Object certObj = payload.get("consensusCertificate");
            if (certObj instanceof Map<?, ?> certMap) {
                try {
                    String certJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(certMap);
                    block.setConsensusCertificate(certJson);
                    if (blockConsensusVoteService != null) {
                        blockConsensusVoteService.storeCertificate(effectiveNetwork, blockHeight, blockHash, (Map<String, Object>) certMap);
                    }
                } catch (Exception ignored) {}
            }

            blockRepo.save(block);
            try {
                if (blockScheduler != null) {
                    blockScheduler.markSlotFilled(block.getSlotNumber());
                }
            } catch (Exception e) {
                log.warn("Could not mark slot filled for received block {}: {}", blockHeight, e.getMessage());
            }

            List<BlockTransaction> confirmedTxs = extractBlockTransactions(payload.get("transactions"), blockHeight);
            List<String> confirmedTxHashes = confirmedTxs.stream()
                    .map(BlockTransaction::getTxHash)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            saveBlockTransactions(confirmedTxs);

            if (feeDistributionService != null) {
                try {
                    feeDistributionService.distributeBlockFees(block, effectiveNetwork);
                    blockRepo.save(block);
                } catch (Exception e) {
                    log.warn("Could not distribute received block {} fees: {}", blockHeight, e.getMessage());
                }
            }
            if (validatorService != null && block.getProposerAddress() != null && !block.getProposerAddress().isBlank()) {
                try {
                    validatorService.recordSuccessfulProposal(block.getProposerAddress(), effectiveNetwork);
                } catch (Exception e) {
                    log.warn("Could not record successful proposal for received block {}: {}", blockHeight, e.getMessage());
                }
            }
            clearConfirmedTransactions(blockHeight, confirmedTxHashes);
            unfinalizedProposals.remove(proposalKey(effectiveNetwork, blockHeight, blockHash));

            log.info("Received and finalized block {} from peer", blockHeight);
            return Map.of("status", "ACCEPTED", "blockHeight", blockHeight, "finalized", true);
        } catch (Exception e) {
            log.warn("Receive block failed: {}", e.getMessage());
            return Map.of("status", "REJECTED", "error", e.getMessage());
        }
    }

    private void saveBlockTransactions(List<BlockTransaction> receivedTxs) {
        try {
            if (receivedTxs == null || receivedTxs.isEmpty()) {
                return;
            }
            List<BlockTransaction> txs = new ArrayList<>();
            for (BlockTransaction tx : receivedTxs) {
                String txHash = tx.getTxHash();
                if (txHash == null || txHash.isBlank() || blockTxRepo.findByTxHash(txHash) != null) {
                    continue;
                }
                if (tx.getStatus() == null) {
                    tx.setStatus(BlockTransaction.TxStatus.CONFIRMED);
                }
                txs.add(tx);
            }
            if (!txs.isEmpty()) {
                blockTxRepo.saveAll(txs);
            }
        } catch (Exception e) {
            log.warn("Could not save received block transactions: {}", e.getMessage());
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
                    .map(item -> {
                        if (item instanceof Map<?, ?> txMap) {
                            return toStringValue(txMap.get("txHash"));
                        }
                        return toStringValue(item);
                    })
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Could not extract tx hashes: {}", e.getMessage());
            return List.of();
        }
    }

    private List<BlockTransaction> extractBlockTransactions(Object rawTransactions, Long blockHeight) {
        try {
            if (!(rawTransactions instanceof List<?> rawList)) {
                return List.of();
            }
            List<BlockTransaction> txs = new ArrayList<>();
            int index = 0;
            for (Object item : rawList) {
                BlockTransaction tx = new BlockTransaction();
                tx.setBlockHeight(blockHeight);
                tx.setTxIndex(index);
                tx.setStatus(BlockTransaction.TxStatus.CONFIRMED);
                if (item instanceof Map<?, ?> txMap) {
                    tx.setTxHash(toStringValue(txMap.get("txHash")));
                    tx.setSenderAddress(toStringValue(txMap.get("sender")));
                    tx.setRecipientAddress(toStringValue(txMap.get("recipient")));
                    tx.setValue(toBigDecimal(txMap.get("value")));
                    tx.setTotalFee(toLong(txMap.get("totalFee")));
                    tx.setBroadcastFee(toLong(txMap.get("broadcastFee")));
                    tx.setConsensusFee(toLong(txMap.get("consensusFee")));
                    tx.setBroadcasterAddress(toStringValue(txMap.get("broadcasterAddress")));
                    tx.setNonce(toLong(txMap.get("nonce")));
                    tx.setTimestamp(toLong(txMap.get("timestamp")));
                    tx.setTxSignature(toStringValue(txMap.get("txSignature")));
                    tx.setPubKeyCompressed(toStringValue(txMap.get("pubKeyCompressed")));
                } else {
                    tx.setTxHash(toStringValue(item));
                }
                if (tx.getTxHash() != null && !tx.getTxHash().isBlank()) {
                    txs.add(tx);
                }
                index++;
            }
            return txs;
        } catch (Exception e) {
            log.warn("Could not extract block transactions: {}", e.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> toPayloadTransaction(BlockTransaction tx) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("txHash", tx.getTxHash());
        item.put("sender", tx.getSenderAddress());
        item.put("recipient", tx.getRecipientAddress());
        item.put("value", tx.getValue());
        item.put("totalFee", tx.getTotalFee());
        item.put("broadcastFee", tx.getBroadcastFee());
        item.put("consensusFee", tx.getConsensusFee());
        item.put("broadcasterAddress", tx.getBroadcasterAddress());
        item.put("nonce", tx.getNonce());
        item.put("timestamp", tx.getTimestamp());
        item.put("txSignature", tx.getTxSignature());
        item.put("pubKeyCompressed", tx.getPubKeyCompressed());
        item.put("status", tx.getStatus());
        return item;
    }

    private BigDecimal toBigDecimal(Object value) {
        try {
            if (value == null) {
                return null;
            }
            if (value instanceof BigDecimal bd) {
                return bd;
            }
            return new BigDecimal(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    public void broadcastTransactionFinalized(String network, String txHash, String from, String to, java.math.BigDecimal amount) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("txHash", txHash);
            payload.put("network", network);
            payload.put("from", from);
            payload.put("to", to);
            payload.put("amount", amount);

            String self = getSelfUrl();
            for (String peer : peerUrls) {
                if (peer.equals(self)) continue;
                try {
                    restTemplate.postForEntity(peer + "/" + network + "/node/tx/finalized", payload, String.class);
                } catch (Exception e) {
                    log.debug("Failed to broadcast finalized tx to {}: {}", peer, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("broadcastTransactionFinalized failed: {}", e.getMessage());
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

    @Value("${app.consensus.allow-solo-finalization:false}")
    private boolean allowSoloFinalization = false;

    public boolean isAllowSoloFinalization() {
        return allowSoloFinalization;
    }

    public void setAllowSoloFinalization(boolean allowSoloFinalization) {
        this.allowSoloFinalization = allowSoloFinalization;
    }

    public boolean verifyConsensusCertificate(Map<String, Object> payload, String network) {
        if (payload == null) return false;
        if (allowSoloFinalization && (peerUrls == null || peerUrls.isEmpty())) {
            log.warn("[SECURITY][SOLO_MODE] Solo finalization permitted (allow-solo-finalization=true, no peers). Bypassing distributed certificate verification for local development only. NO BFT GUARANTEES.");
            return true;
        }

        Object certObj = payload.get("consensusCertificate");
        if (!(certObj instanceof Map<?, ?> cert)) {
            log.warn("[SECURITY] Block {} finalization rejected: missing or invalid consensus certificate structure", payload.get("blockHeight"));
            return false;
        }

        boolean isSolo = Boolean.TRUE.equals(cert.get("isSoloCertificate"));
        if (isSolo) {
            log.warn("[SECURITY] Solo certificate presented but allow-solo-finalization=false or peers exist. Rejected.");
            return false;
        }

        try {
            Long certHeight = toLong(cert.get("blockHeight"));
            String certHash = toStringValue(cert.get("blockHash"));
            Long blockHeight = toLong(payload.get("blockHeight"));
            String blockHash = toStringValue(payload.get("blockHash"));
            String certNetwork = toStringValue(cert.get("network"));

            if (certHeight == null || blockHeight == null || !certHeight.equals(blockHeight)) {
                log.warn("[SECURITY] Certificate blockHeight ({}) != payload blockHeight ({})", certHeight, blockHeight);
                return false;
            }
            if (certHash == null || blockHash == null || !certHash.equalsIgnoreCase(blockHash)) {
                log.warn("[SECURITY] Certificate blockHash ({}) != payload blockHash ({})", certHash, blockHash);
                return false;
            }
            if (certNetwork != null && !certNetwork.isBlank() && !certNetwork.equalsIgnoreCase(network)) {
                log.warn("[SECURITY] Certificate network ({}) != node network ({})", certNetwork, network);
                return false;
            }

            Object votersObj = cert.get("voters");
            if (!(votersObj instanceof List<?> voterList) || voterList.isEmpty()) {
                log.warn("[SECURITY] Certificate has empty or invalid voters list");
                return false;
            }
            Object sigsObj = cert.get("signatures");
            if (!(sigsObj instanceof Map<?, ?> sigsMap) || sigsMap.isEmpty()) {
                log.warn("[SECURITY] Certificate has empty or invalid signatures map");
                return false;
            }

            Set<String> uniqueVoters = new LinkedHashSet<>();
            for (Object v : voterList) {
                if (v == null) return false;
                String voterStr = String.valueOf(v).trim();
                if (voterStr.isBlank()) return false;
                if (!uniqueVoters.add(voterStr)) {
                    log.warn("[SECURITY] Certificate rejected: duplicate voter address {}", voterStr);
                    return false;
                }
            }

            if (sigsMap.size() != uniqueVoters.size()) {
                log.warn("[SECURITY] Certificate rejected: voter list size ({}) != signatures map size ({})",
                        uniqueVoters.size(), sigsMap.size());
                return false;
            }

            String claimedValSetHash = toStringValue(payload.get("validatorSetHash"));
            Map<String, Double> activeWeights = new LinkedHashMap<>();

            List<Validator> active = (validatorRepo != null)
                    ? validatorRepo.findByNetworkAndStatus(network, Validator.ValidatorStatus.ACTIVE)
                    : List.of();
            String currentHash = BlockValidationService.computeValidatorSetHash(active);

            if (claimedValSetHash != null && !claimedValSetHash.isBlank() && !claimedValSetHash.equalsIgnoreCase(currentHash)) {
                // Historical block or validator set evolved: check historical snapshot
                if (validatorService != null && validatorService.getSnapshotByHash(claimedValSetHash).isPresent()) {
                    var snap = validatorService.getSnapshotByHash(claimedValSetHash).get();
                    activeWeights.putAll(snap.weights());
                    log.info("[SECURITY] Historical block {} using validator snapshot {}", blockHeight, claimedValSetHash);
                } else if (fetchHistoricalSnapshotFromPeers(claimedValSetHash, blockHeight, network) && validatorService != null && validatorService.getSnapshotByHash(claimedValSetHash).isPresent()) {
                    var snap = validatorService.getSnapshotByHash(claimedValSetHash).get();
                    activeWeights.putAll(snap.weights());
                    log.info("[SECURITY] Historical block {} using retrieved validator snapshot {}", blockHeight, claimedValSetHash);
                } else {
                    log.warn("[SECURITY] Block {} references historical validator set hash {} but no snapshot exists. Failing closed.",
                            blockHeight, claimedValSetHash);
                    return false;
                }
            } else {
                // Current validator set
                if (active != null) {
                    for (Validator val : active) {
                        if (val.getAddress() != null && !val.getAddress().isBlank() && val.isActive()) {
                            double w = validatorService != null ? validatorService.calculateWeight(val) : 1.0;
                            if (w > 0.0) {
                                activeWeights.put(val.getAddress(), w);
                            }
                        }
                    }
                }
                if (validatorService != null && active != null && !active.isEmpty() && claimedValSetHash != null) {
                    validatorService.recordSnapshot(network, blockHeight, active);
                }
            }

            if (activeWeights.isEmpty()) {
                log.warn("[SECURITY] Certificate rejected: no active validators found for network {}", network);
                return false;
            }

            Set<String> authenticatedVoters = new LinkedHashSet<>();
            for (String voterAddr : uniqueVoters) {
                if (!activeWeights.containsKey(voterAddr)) {
                    log.warn("[SECURITY] Certificate rejected: voter {} is not an active validator on {}", voterAddr, network);
                    return false;
                }
                Object sigVal = sigsMap.get(voterAddr);
                if (sigVal == null) {
                    log.warn("[SECURITY] Certificate rejected: missing signature for voter {}", voterAddr);
                    return false;
                }

                String sigHex = null;
                String pubKeyHex = null;
                if (sigVal instanceof Map<?, ?> m) {
                    sigHex = toStringValue(m.get("signature"));
                    pubKeyHex = toStringValue(m.get("publicKey"));
                } else if (sigVal instanceof String s) {
                    sigHex = s;
                }

                if (sigHex == null || sigHex.isBlank()) {
                    log.warn("[SECURITY] Certificate rejected: blank signature for voter {}", voterAddr);
                    return false;
                }
                if (pubKeyHex == null || pubKeyHex.isBlank()) {
                    log.warn("[SECURITY] Certificate rejected: missing public key for voter {}", voterAddr);
                    return false;
                }

                byte[] pubKeyBytes;
                byte[] sigBytes;
                try {
                    pubKeyBytes = org.apache.commons.codec.binary.Hex.decodeHex(pubKeyHex.trim());
                    sigBytes = org.apache.commons.codec.binary.Hex.decodeHex(sigHex.trim());
                } catch (Exception e) {
                    log.warn("[SECURITY] Certificate rejected: hex decode error for voter {}: {}", voterAddr, e.getMessage());
                    return false;
                }

                if (!CryptoUtil.isValidSecp256k1PublicKey(pubKeyBytes)) {
                    log.warn("[SECURITY] Certificate rejected: invalid secp256k1 public key for voter {}", voterAddr);
                    return false;
                }

                String derivedAddr = AddressUtil.deriveAddressFromPublicKey(pubKeyBytes);
                if (derivedAddr == null || !derivedAddr.equalsIgnoreCase(voterAddr)) {
                    log.warn("[SECURITY] Certificate rejected: public key derives address {} which does not match voter {}", derivedAddr, voterAddr);
                    return false;
                }

                String canonicalPayload = "BLOCK_VOTE|" + network + "|" + blockHeight + "|" + blockHash + "|" + voterAddr;
                byte[] digest = CryptoUtil.keccak256(canonicalPayload.getBytes(StandardCharsets.UTF_8));
                boolean valid = CryptoUtil.verifyVoteSignature(digest, sigBytes, pubKeyBytes);
                if (!valid) {
                    log.warn("[SECURITY] Certificate rejected: cryptographic signature verification failed for voter {}", voterAddr);
                    return false;
                }

                authenticatedVoters.add(voterAddr);
            }

            String proposer = toStringValue(payload.get("proposerAddress"));
            ConsensusQuorum.TallyResult tally = ConsensusQuorum.evaluateVotes(authenticatedVoters, activeWeights, proposer);
            if (!tally.reached()) {
                log.warn("[SECURITY] Certificate rejected: quorum not reached (yesVotes={}/{}, approvedWeight={}/{}, peerConfirmations={})",
                        tally.yesVotes(), tally.totalValidators(), tally.approvedWeight(), tally.totalWeight(), tally.peerConfirmations());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("[SECURITY] Failed to verify consensus certificate: {}", e.getMessage());
            return false;
        }
    }

    private boolean fetchHistoricalSnapshotFromPeers(String claimedValSetHash, Long blockHeight, String network) {
        if (peerUrls == null || peerUrls.isEmpty() || validatorService == null || restTemplate == null) {
            return false;
        }
        for (String peer : peerUrls) {
            try {
                String cleanPeer = peer.trim().replaceAll("/+$", "");
                String url = cleanPeer + "/" + network + "/validators/snapshot/" + claimedValSetHash;
                Map<?, ?> resp = restTemplate.getForObject(url, Map.class);
                if (resp == null) continue;
                Object rawValidators = resp.get("validators");
                if (!(rawValidators instanceof List<?> valList) || valList.isEmpty()) continue;

                List<Validator> reconstructed = new ArrayList<>();
                for (Object itemObj : valList) {
                    if (itemObj instanceof Map<?, ?> m) {
                        String addr = toStringValue(m.get("address"));
                        Object stakeObj = m.get("stake");
                        BigDecimal stake = stakeObj == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(stakeObj));
                        Long rel = toLong(m.get("reliabilityScoreScaled"));
                        Validator val = Validator.builder()
                                .address(addr)
                                .network(network)
                                .stakedAmount(stake)
                                .reliabilityScoreScaled(rel == null ? 0L : rel)
                                .status(Validator.ValidatorStatus.ACTIVE)
                                .build();
                        reconstructed.add(val);
                    }
                }
                String computedHash = BlockValidationService.computeValidatorSetHash(reconstructed);
                if (computedHash.equalsIgnoreCase(claimedValSetHash)) {
                    validatorService.recordSnapshot(network, blockHeight, reconstructed);
                    log.info("[SECURITY] Successfully retrieved and verified historical validator snapshot {} from {}", claimedValSetHash, peer);
                    return true;
                } else {
                    log.warn("[SECURITY] Peer {} returned historical snapshot that failed hash verification. Expected {}, computed {}",
                            peer, claimedValSetHash, computedHash);
                }
            } catch (Exception e) {
                log.debug("Failed to fetch snapshot {} from {}: {}", claimedValSetHash, peer, e.getMessage());
            }
        }
        return false;
    }

    private Double defaultDouble(Double value, double defaultValue) {
        return value == null ? defaultValue : value;
    }
}
