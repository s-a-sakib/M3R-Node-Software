package com.m3rwallet.service;

import com.m3rwallet.config.ConsensusProperties;
import com.m3rwallet.entity.Block;
import com.m3rwallet.entity.Validator;
import com.m3rwallet.repository.ValidatorRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class BlockConsensusVoteService {

    @Autowired(required = false)
    private ConsensusProperties consensusProperties;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ValidatorService validatorService;

    @Autowired
    private ValidatorRepository validatorRepository;

    @Autowired(required = false)
    private NodeIdentityService nodeIdentityService;

    @Value("${app.blockchain.network:mainnet}")
    private String network;

    /**
     * Proposer calls this to collect 2/3 weighted votes from peers.
     * Returns true if consensus reached.
     */
    public boolean collectVotes(Block block, String network) {
        try {
            List<String> peers = (consensusProperties != null) ? consensusProperties.getPeers() : List.of();
            if (peers == null || peers.isEmpty()) {
                // Solo mode: auto-finalize
                log.info("No peers configured. Auto-finalizing (solo mode).");
                return true;
            }

            // This node's vote (proposer always votes yes)
            double approvedWeight = getMyWeight(network);
            double totalWeight = getTotalWeight(network);
            int yesVotes = 1;

            // Build vote request
            Map<String, Object> voteRequest = new LinkedHashMap<>();
            voteRequest.put("blockHeight",     block.getBlockHeight());
            voteRequest.put("blockHash",       block.getBlockHash());
            voteRequest.put("proposerAddress", block.getProposerAddress());
            voteRequest.put("merkleRoot",      block.getMerkleRoot());
            voteRequest.put("slotNumber",      block.getSlotNumber());
            voteRequest.put("txCount",         block.getTxCount());
            voteRequest.put("network",         network);
            voteRequest.put("timestamp",       block.getTimestamp());

            // Collect votes from peers
            for (String peer : peers) {
                try {
                    String url = peer + "/" + network + "/blocks/vote";
                    Map response = restTemplate.postForObject(url, voteRequest, Map.class);
                    if (response == null) continue;

                    boolean vote = Boolean.TRUE.equals(response.get("vote"));
                    String voterAddress = (String) response.getOrDefault("voterAddress", "");
                    double voterWeight = getValidatorWeight(voterAddress, network);

                    if (vote) {
                        approvedWeight += voterWeight;
                        yesVotes++;
                        log.debug("[VOTE] YES from {} (weight: {})", peer, voterWeight);
                    } else {
                        log.debug("[VOTE] NO from {} reason: {}", peer, response.get("reason"));
                    }
                } catch (Exception e) {
                    log.warn("[VOTE] Peer {} unreachable: {}", peer, e.getMessage());
                }
            }

            // Require at least one peer confirmation — never self-certify
            if (yesVotes <= 1) {
                log.warn("[BLOCK VOTE] Insufficient peer confirmations (only self). Rejecting.");
                return false;
            }

            double weightRatio = totalWeight > 0 ? approvedWeight / totalWeight : 0;
            int totalPeers = peers.size() + 1;
            boolean reached = weightRatio >= (2.0 / 3.0) && yesVotes >= Math.ceil(totalPeers * 2.0 / 3.0);

            log.info("[BLOCK VOTE] Height={} Votes={}/{} Weight={}/{} ({}%) Consensus={}",
                    block.getBlockHeight(), yesVotes, totalPeers,
                    String.format("%.2f", approvedWeight), String.format("%.2f", totalWeight),
                    String.format("%.1f", weightRatio * 100), reached ? "YES" : "NO");

            return reached;
        } catch (Exception e) {
            log.warn("collectVotes failed: {}", e.getMessage());
            return false;
        }
    }

    private double getMyWeight(String network) {
        try {
            String myAddr = nodeIdentityService != null ? nodeIdentityService.getNodeAddress() : "";
            return validatorRepository.findByAddressAndNetwork(myAddr, network)
                    .map(v -> validatorService.calculateWeight(v))
                    .orElse(0.0);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private double getValidatorWeight(String address, String network) {
        try {
            return validatorRepository.findByAddressAndNetwork(address, network)
                    .map(v -> validatorService.calculateWeight(v))
                    .orElse(0.0);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private double getTotalWeight(String network) {
        try {
            return validatorRepository.findByNetworkAndStatus(network, Validator.ValidatorStatus.ACTIVE)
                    .stream()
                    .mapToDouble(v -> validatorService.calculateWeight(v))
                    .sum();
        } catch (Exception e) {
            return 0.0;
        }
    }
}
