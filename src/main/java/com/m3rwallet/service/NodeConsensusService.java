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

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class NodeConsensusService {
    public static final String CONSENSUS_TOKEN_HEADER = "X-M3R-Consensus-Token";

    private final WalletService walletService;
    private final RestTemplate restTemplate;
    private final ConsensusProperties consensusProperties;
    private final NodeIdentityService nodeIdentityService;

    @Autowired(required = false)
    private ValidatorService validatorService;

    @Autowired(required = false)
    private ValidatorRepository validatorRepository;

    @Value("${app.blockchain.network:mainnet}")
    private String defaultNetwork;

    public boolean isEnabled() {
        return consensusProperties.isEnabled() && !normalizedPeers().isEmpty();
    }

    public TxResponse submitWithConsensus(String network, TxSubmitRequest request) {
        List<String> peers = normalizedPeers();
        int totalNodes = peers.size() + 1;
        int quorum = quorum(totalNodes);
        request.setBroadcasterAddress(nodeIdentityService.getAddressOrUnknown());

        String txHash;
        try {
            txHash = walletService.validateTransaction(network, request.getRawTxHex(), request.getPubKeyCompressedHex());
        } catch (IllegalArgumentException e) {
            return TxResponse.builder()
                    .status("REJECTED")
                    .message(e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("[CONSENSUS][{}] Local validation failed", network, e);
            return TxResponse.builder()
                    .status("REJECTED")
                    .message("Local validation error")
                    .build();
        }

        int yesVotes = 1;
        List<String> yesVoterAddresses = new ArrayList<>();
        yesVoterAddresses.add(nodeIdentityService.getAddressOrUnknown());
        List<String> rejections = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();

        for (String peer : peers) {
            try {
                TxResponse response = post(peer, network, "validate", request);
                if (response != null && "ACCEPTED".equals(response.getStatus())) {
                    yesVotes++;
                    yesVoterAddresses.add(response.getValidatorAddress());
                } else {
                    String reason = response != null ? response.getMessage() : "empty response";
                    rejections.add(peer + ": " + reason);
                }
            } catch (RestClientException e) {
                unavailable.add(peer + ": " + e.getMessage());
                log.warn("[CONSENSUS][{}] Peer validate unavailable: {} {}", network, peer, e.getMessage());
            }
        }

        double totalWeight = getTotalNetworkWeight(totalNodes, network);
        double approvedWeight = getApprovedWeight(yesVoterAddresses, network);
        double weightRatio = totalWeight > 0.0d ? approvedWeight / totalWeight : 0.0d;
        boolean weightedAvailable = hasActiveValidators(network);
        boolean weightedConsensus = weightedAvailable && weightRatio >= (2.0d / 3.0d);
        boolean countConsensus = yesVotes >= quorum;
        String consensusType = weightedConsensus ? "WEIGHTED" : "COUNT_FALLBACK";

        log.info("[CONSENSUS][{}] approvedWeight={}/{} ({}%) votes={}/{} quorum={} consensusType={} weightedAvailable={}",
                network,
                String.format("%.4f", approvedWeight),
                String.format("%.4f", totalWeight),
                String.format("%.1f", weightRatio * 100.0d),
                yesVotes,
                totalNodes,
                quorum,
                consensusType,
                weightedAvailable);

        if (!weightedConsensus && !countConsensus) {
            return TxResponse.builder()
                    .status("REJECTED")
                    .txHash(txHash)
                    .yesVotes(yesVotes)
                    .totalPeers(totalNodes)
                    .approvedWeight(approvedWeight)
                    .totalWeight(totalWeight)
                    .weightRatio(weightRatio)
                    .consensusType(consensusType)
                    .message("Consensus rejected: yesVotes=" + yesVotes + "/" + totalNodes
                            + ", quorum=" + quorum
                            + ", approvedWeight=" + String.format("%.4f", approvedWeight) + "/" + String.format("%.4f", totalWeight)
                            + ", weightRatio=" + String.format("%.4f", weightRatio)
                            + summarizeFailures(rejections, unavailable))
                    .build();
        }

        int executeFailures = 0;
        for (String peer : peers) {
            try {
                TxResponse response = post(peer, network, "execute", request);
                if (response == null || !"ACCEPTED".equals(response.getStatus())) {
                    executeFailures++;
                    log.warn("[CONSENSUS][{}] Peer execute rejected: {} {}", network, peer,
                            response != null ? response.getMessage() : "empty response");
                }
            } catch (RestClientException e) {
                executeFailures++;
                log.warn("[CONSENSUS][{}] Peer execute unavailable: {} {}", network, peer, e.getMessage());
            }
        }

        try {
            String executedHash = walletService.executeTransaction(
                    network,
                    request.getRawTxHex(),
                    request.getPubKeyCompressedHex(),
                    request.getBroadcasterAddress());
            return TxResponse.builder()
                    .status("ACCEPTED")
                    .txHash(executedHash)
                    .yesVotes(yesVotes)
                    .totalPeers(totalNodes)
                    .approvedWeight(approvedWeight)
                    .totalWeight(totalWeight)
                    .weightRatio(weightRatio)
                    .consensusType(consensusType)
                    .message("Consensus accepted: yesVotes=" + yesVotes + "/" + totalNodes
                            + ", quorum=" + quorum
                            + ", approvedWeight=" + String.format("%.4f", approvedWeight) + "/" + String.format("%.4f", totalWeight)
                            + ", weightRatio=" + String.format("%.4f", weightRatio)
                            + (executeFailures > 0 ? ", peerExecuteFailures=" + executeFailures : ""))
                    .build();
        } catch (IllegalArgumentException e) {
            log.error("[CONSENSUS][{}] Local execute rejected after quorum: {}", network, e.getMessage());
            return TxResponse.builder()
                    .status("REJECTED")
                    .txHash(txHash)
                    .message("Local execute rejected after quorum: " + e.getMessage())
                    .build();
        }
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
}
