package com.m3rwallet.service;

import com.m3rwallet.config.ConsensusProperties;
import com.m3rwallet.dto.TxResponse;
import com.m3rwallet.dto.TxSubmitRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    public boolean isEnabled() {
        return consensusProperties.isEnabled() && !normalizedPeers().isEmpty();
    }

    public TxResponse submitWithConsensus(String network, TxSubmitRequest request) {
        List<String> peers = normalizedPeers();
        int totalNodes = peers.size() + 1;
        int quorum = quorum(totalNodes);

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
        List<String> rejections = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();

        for (String peer : peers) {
            try {
                TxResponse response = post(peer, network, "validate", request);
                if (response != null && "ACCEPTED".equals(response.getStatus())) {
                    yesVotes++;
                } else {
                    String reason = response != null ? response.getMessage() : "empty response";
                    rejections.add(peer + ": " + reason);
                }
            } catch (RestClientException e) {
                unavailable.add(peer + ": " + e.getMessage());
                log.warn("[CONSENSUS][{}] Peer validate unavailable: {} {}", network, peer, e.getMessage());
            }
        }

        if (yesVotes < quorum) {
            return TxResponse.builder()
                    .status("REJECTED")
                    .txHash(txHash)
                    .message("Consensus rejected: yesVotes=" + yesVotes + "/" + totalNodes
                            + ", quorum=" + quorum
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
            String executedHash = walletService.executeTransaction(network, request.getRawTxHex(), request.getPubKeyCompressedHex());
            return TxResponse.builder()
                    .status("ACCEPTED")
                    .txHash(executedHash)
                    .message("Consensus accepted: yesVotes=" + yesVotes + "/" + totalNodes
                            + ", quorum=" + quorum
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
