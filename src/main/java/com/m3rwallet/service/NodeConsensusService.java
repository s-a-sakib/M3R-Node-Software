package com.m3rwallet.service;

import com.m3rwallet.config.ConsensusProperties;
import com.m3rwallet.dto.TxResponse;
import com.m3rwallet.dto.TxSubmitRequest;
import com.m3rwallet.entity.Validator;
import com.m3rwallet.repository.ValidatorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class NodeConsensusService {
    public static final String CONSENSUS_TOKEN_HEADER = "X-M3R-Consensus-Token";
    private static final int SENDER_LOCK_STRIPES = 1024;
    @Value("${app.consensus.future-nonce-wait-ms:15000}")
    private long futureNonceWaitMs;

    @Value("${app.consensus.future-nonce-retry-sleep-ms:25}")
    private long futureNonceRetrySleepMs;

    private final WalletService walletService;
    private final RestTemplate restTemplate;
    private final ConsensusProperties consensusProperties;
    private final NodeIdentityService nodeIdentityService;
    private final Object[] senderLocks = createSenderLocks();

    @Autowired(required = false)
    private ValidatorService validatorService;

    @Autowired(required = false)
    private ValidatorRepository validatorRepository;

    @Autowired(required = false)
    private AccountReconciliationService accountReconciliationService;

    @Value("${app.blockchain.network:mainnet}")
    private String defaultNetwork;

    public boolean isEnabled() {
        return consensusProperties.isEnabled() && !normalizedPeers().isEmpty();
    }

    public TxResponse submitWithConsensus(String network, TxSubmitRequest request) {
        WalletService.VerifiedTxInfo txInfo;
        try {
            txInfo = walletService.getVerifiedTransactionInfo(
                    request.getRawTxHex(),
                    request.getPubKeyCompressedHex());
        } catch (IllegalArgumentException e) {
            return TxResponse.builder()
                    .status("REJECTED")
                    .message(e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("[CONSENSUS][{}] Could not identify sender", network, e);
            return TxResponse.builder()
                    .status("REJECTED")
                    .message("Sender verification error")
                    .build();
        }

        String senderAddress = txInfo.senderAddress();
        synchronized (lockForSender(network, senderAddress)) {
            BigInteger expectedNonce = BigInteger.valueOf(walletService.getCurrentNonce(network, senderAddress)).add(BigInteger.ONE);
            if (txInfo.nonce().compareTo(expectedNonce) <= 0) {
                return submitWithConsensusLocked(network, request);
            }
        }

        long deadline = System.currentTimeMillis() + futureNonceWaitMs;
        while (System.currentTimeMillis() < deadline) {
            sleepBeforeNonceRetry();
            synchronized (lockForSender(network, senderAddress)) {
                BigInteger expectedNonce = BigInteger.valueOf(walletService.getCurrentNonce(network, senderAddress)).add(BigInteger.ONE);
                if (txInfo.nonce().compareTo(expectedNonce) <= 0) {
                    return submitWithConsensusLocked(network, request);
                }
            }
        }

        long ledgerNonce = walletService.getCurrentNonce(network, senderAddress);
        BigInteger expectedNonce = BigInteger.valueOf(ledgerNonce).add(BigInteger.ONE);
        return TxResponse.builder()
                .status("REJECTED")
                .message("Invalid nonce (tx=" + txInfo.nonce() + ", expected=" + expectedNonce
                        + ", ledger=" + ledgerNonce + ")")
                .build();
    }

    private TxResponse submitWithConsensusLocked(String network, TxSubmitRequest request) {
        List<String> peers = normalizedPeers();
        int totalNodes = peers.size() + 1;
        int quorum = quorum(totalNodes);
        request.setBroadcasterAddress(nodeIdentityService.getAddressOrUnknown());

        String txHash;
        try {
            txHash = walletService.validateTransaction(network, request.getRawTxHex(), request.getPubKeyCompressedHex());
        } catch (IllegalArgumentException e) {
            return TxResponse.builder().status("REJECTED").message(e.getMessage()).build();
        } catch (Exception e) {
            log.error("[CONSENSUS][{}] Local validation failed", network, e);
            return TxResponse.builder().status("REJECTED").message("Local validation error: " + e.getMessage()).build();
        }

        // VALIDATE PHASE
        int yesVotes = 1;
        List<String> yesVoterAddresses = new ArrayList<>(List.of(nodeIdentityService.getAddressOrUnknown()));
        List<String> rejections = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();

        for (String peer : peers) {
            try {
                TxResponse response = post(peer, network, "validate", request);
                if (response != null && "ACCEPTED".equals(response.getStatus())) {
                    yesVotes++;
                    if (response.getValidatorAddress() != null) yesVoterAddresses.add(response.getValidatorAddress());
                } else {
                    rejections.add(peer + ": " + (response != null ? response.getMessage() : "empty"));
                }
            } catch (Exception e) {
                unavailable.add(peer + ": " + e.getMessage());
                log.warn("[CONSENSUS][{}] Peer validate failed: {}", network, peer, e);
            }
        }

        // === Consensus check ===
        double totalWeight = getTotalNetworkWeight(totalNodes, network);
        double approvedWeight = getApprovedWeight(yesVoterAddresses, network);
        double weightRatio = totalWeight > 0 ? approvedWeight / totalWeight : 0;
        boolean weightedConsensus = hasActiveValidators(network) && weightRatio >= (2.0 / 3.0);
        boolean countConsensus = yesVotes >= quorum;

        if (!weightedConsensus && !countConsensus) {
            return TxResponse.builder()
                    .status("REJECTED")
                    .txHash(txHash)
                    .yesVotes(yesVotes)
                    .totalPeers(totalNodes)
                    .approvedWeight(approvedWeight)
                    .totalWeight(totalWeight)
                    .weightRatio(weightRatio)
                    .consensusType(weightedConsensus ? "WEIGHTED" : "COUNT_FALLBACK")
                    .message("Consensus rejected: yesVotes=" + yesVotes + "/" + totalNodes
                            + ", quorum=" + quorum
                            + ", approvedWeight=" + String.format("%.4f", approvedWeight) + "/" + String.format("%.4f", totalWeight)
                            + ", weightRatio=" + String.format("%.4f", weightRatio)
                            + summarizeFailures(rejections, unavailable))
                    .build();
        }

        // === EXECUTE PHASE - TOLERANT ===
        List<String> executeFailures = new ArrayList<>();
        for (String peer : peers) {
            try {
                TxResponse resp = post(peer, network, "execute", request);
                if (resp == null || !"ACCEPTED".equals(resp.getStatus())) {
                    executeFailures.add(peer + ": " + (resp != null ? resp.getMessage() : "null"));
                }
            } catch (Exception e) {
                executeFailures.add(peer + ": " + e.getMessage());
                log.warn("[CONSENSUS][{}] Peer execute failed: {} : {}", network, peer, e.getMessage());
            }
        }

        log.info("[CONSENSUS][{}] Execute phase: {} failures out of {} peers",
                network, executeFailures.size(), peers.size());

        // === ALWAYS EXECUTE LOCALLY (this node is the submitter's source of truth) ===
        String executedHash;
        try {
            executedHash = walletService.executeTransaction(
                    network, request.getRawTxHex(), request.getPubKeyCompressedHex(), request.getBroadcasterAddress());
            log.info("[CONSENSUS][{}] Local execution SUCCESS: {}", network, executedHash);
        } catch (Exception e) {
            log.error("[CONSENSUS][{}] Local execute FAILED after quorum", network, e);
            return TxResponse.builder().status("REJECTED").message("Local execute failed: " + e.getMessage()).build();
        }

        // Optional final broadcast
        try { broadcastFinalState(network, executedHash); } catch (Exception ignored) {}
        try {
            if (accountReconciliationService != null) {
                WalletService.VerifiedTxInfo txInfo = walletService.getVerifiedTransactionInfo(
                        request.getRawTxHex(), request.getPubKeyCompressedHex());
                accountReconciliationService.reconcileAccount(network, txInfo.senderAddress());
            }
        } catch (Exception e) {
            log.debug("[CONSENSUS][{}] Post-execute reconciliation skipped: {}", network, e.getMessage());
        }

        return TxResponse.builder()
                .status("ACCEPTED")
                .txHash(executedHash)
                .yesVotes(yesVotes)
                .totalPeers(totalNodes)
                .approvedWeight(approvedWeight)
                .totalWeight(totalWeight)
                .weightRatio(weightRatio)
                .consensusType(weightedConsensus ? "WEIGHTED" : "COUNT_FALLBACK")
                .message("Consensus accepted: yesVotes=" + yesVotes + "/" + totalNodes
                        + ", quorum=" + quorum
                        + ", approvedWeight=" + String.format("%.4f", approvedWeight) + "/" + String.format("%.4f", totalWeight)
                        + ", weightRatio=" + String.format("%.4f", weightRatio)
                        + (executeFailures.isEmpty() ? "" : ", peerExecuteFailures=" + executeFailures.size()))
                .build();
    }

    private TxResponse post(String peer, String network, String action, TxSubmitRequest request) {
        String url = peer + "/" + network + "/node/tx/" + action;
        ResponseEntity<TxResponse> response = restTemplate.postForEntity(url, requestEntity(request), TxResponse.class);
        return response.getBody();
    }

    private HttpEntity<TxSubmitRequest> requestEntity(TxSubmitRequest request) {
        HttpHeaders headers = new HttpHeaders();
        if (consensusProperties.getSharedSecret() != null && !consensusProperties.getSharedSecret().isBlank()) {
            headers.set(CONSENSUS_TOKEN_HEADER, consensusProperties.getSharedSecret());
        }
        return new HttpEntity<>(request, headers);
    }

    private List<String> normalizedPeers() {
        return consensusProperties.getPeers().stream()
                .filter(peer -> peer != null && !peer.isBlank())
                .map(String::trim)
                .map(peer -> peer.endsWith("/") ? peer.substring(0, peer.length() - 1) : peer)
                .distinct()
                .toList();
    }

    private int quorum(int totalNodes) {
        return (2 * totalNodes + 2) / 3;
    }

    private Object lockForSender(String network, String senderAddress) {
        int index = Math.floorMod(Objects.hash(network, senderAddress), senderLocks.length);
        return senderLocks[index];
    }

    private static Object[] createSenderLocks() {
        Object[] locks = new Object[SENDER_LOCK_STRIPES];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    private void sleepBeforeNonceRetry() {
        try {
            Thread.sleep(futureNonceRetrySleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Returns W_v weight for a validator.
     * Falls back to 1.0 (equal weight) if validator not found.
     */
    private double getVoteWeight(String voterAddress, String network) {
        try {
            if (validatorService == null
                    || validatorRepository == null
                    || voterAddress == null
                    || voterAddress.isBlank()) {
                return 1.0d;
            }
            String effectiveNetwork = (network == null || network.isBlank()) ? defaultNetwork : network;
            return validatorRepository
                    .findByAddressAndNetwork(voterAddress, effectiveNetwork)
                    .map(v -> validatorService.calculateWeight(v))
                    .orElse(1.0d);
        } catch (Exception e) {
            log.warn("Could not get weight for {}: {}", voterAddress, e.getMessage());
            return 1.0d;
        }
    }

    /**
     * Sum of W_v for all ACTIVE validators on this network.
     * Falls back to peerCount if validator system unavailable.
     */
    private double getTotalNetworkWeight(int peerCount, String network) {
        try {
            if (validatorService == null || validatorRepository == null) {
                return peerCount;
            }
            String effectiveNetwork = (network == null || network.isBlank()) ? defaultNetwork : network;
            List<Validator> active = validatorRepository
                    .findByNetworkAndStatus(effectiveNetwork, Validator.ValidatorStatus.ACTIVE);
            if (active == null || active.isEmpty()) {
                return peerCount;
            }
            double total = active.stream()
                    .mapToDouble(v -> validatorService.calculateWeight(v))
                    .sum();
            return total > 0.0d ? total : peerCount;
        } catch (Exception e) {
            log.warn("Could not get total weight: {}", e.getMessage());
            return peerCount;
        }
    }

    private double getApprovedWeight(List<String> yesVoterAddresses, String network) {
        try {
            if (yesVoterAddresses == null || yesVoterAddresses.isEmpty()) {
                return 0.0d;
            }
            return yesVoterAddresses.stream()
                    .mapToDouble(address -> getVoteWeight(address, network))
                    .sum();
        } catch (Exception e) {
            log.warn("Could not get approved weight: {}", e.getMessage());
            return yesVoterAddresses == null ? 0.0d : yesVoterAddresses.size();
        }
    }

    private boolean hasActiveValidators(String network) {
        try {
            if (validatorRepository == null) {
                return false;
            }
            String effectiveNetwork = (network == null || network.isBlank()) ? defaultNetwork : network;
            List<Validator> active = validatorRepository
                    .findByNetworkAndStatus(effectiveNetwork, Validator.ValidatorStatus.ACTIVE);
            return active != null && !active.isEmpty();
        } catch (Exception e) {
            log.warn("Could not check active validators: {}", e.getMessage());
            return false;
        }
    }

    private String summarizeFailures(List<String> rejections, List<String> unavailable) {
        List<String> parts = new ArrayList<>();
        if (!rejections.isEmpty()) {
            parts.add("rejections=" + rejections);
        }
        if (!unavailable.isEmpty()) {
            parts.add("unavailable=" + unavailable);
        }
        return parts.isEmpty() ? "" : ", " + String.join(", ", parts);
    }

    // Lightweight final state broadcast hook
    private void broadcastFinalState(String network, String txHash) {
        try {
            log.info("[STATE-BROADCAST][{}] Transaction finalized: {}", network, txHash);
        } catch (Exception ignored) {}
    }
}
