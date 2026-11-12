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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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

    @Autowired(required = false)
    private com.m3rwallet.repository.BlockRepository blockRepository;

    @Value("${app.blockchain.network:mainnet}")
    private String network;

    @Value("${app.consensus.allow-solo-finalization:false}")
    private boolean allowSoloFinalization = false;

    public record VoteCollectionResult(
            boolean consensusReached,
            Long blockHeight,
            String blockHash,
            String network,
            Map<String, Object> certificate,
            ConsensusQuorum.TallyResult tally,
            String failureReason
    ) {}

    private final Map<String, Map<String, Object>> certificateCache = new ConcurrentHashMap<>();
    private volatile Map<String, Object> lastCertificate;

    public String certificateKey(String network, Long height, String hash) {
        String net = (network == null || network.isBlank()) ? "mainnet" : network.trim().toLowerCase();
        String h = (hash == null) ? "" : hash.trim().toLowerCase();
        return net + ":" + height + ":" + h;
    }

    public Map<String, Object> getCertificate(String network, Long height, String hash) {
        if (height == null || hash == null) return null;
        return certificateCache.get(certificateKey(network, height, hash));
    }

    public void storeCertificate(String network, Long height, String hash, Map<String, Object> certificate) {
        if (height != null && hash != null && certificate != null) {
            certificateCache.put(certificateKey(network, height, hash), certificate);
            this.lastCertificate = certificate;
        }
    }

    public boolean isAllowSoloFinalization() {
        return allowSoloFinalization;
    }

    public void setAllowSoloFinalization(boolean allowSoloFinalization) {
        this.allowSoloFinalization = allowSoloFinalization;
    }

    @Deprecated
    public Map<String, Object> getLastCertificate() {
        return lastCertificate;
    }

    /**
     * Proposer calls this to collect 2/3 weighted votes from peers.
     * Returns true if consensus reached and records a verifiable consensus certificate.
     */
    public boolean collectVotes(Block block, String network) {
        return collectConsensusVotes(block, network).consensusReached();
    }

    public VoteCollectionResult collectConsensusVotes(Block block, String network) {
        if (block == null) {
            return new VoteCollectionResult(false, null, null, network, null, null, "Block is null");
        }
        try {
            List<String> peers = (consensusProperties != null) ? consensusProperties.getPeers() : List.of();
            String myAddr = nodeIdentityService != null ? nodeIdentityService.getNodeAddress() : "";
            String proposerAddr = block.getProposerAddress() != null ? block.getProposerAddress() : myAddr;

            // Load active validators and their weights
            List<Validator> activeValidators = validatorRepository.findByNetworkAndStatus(network, Validator.ValidatorStatus.ACTIVE);
            Map<String, Double> activeWeights = new HashMap<>();
            if (activeValidators != null) {
                for (Validator v : activeValidators) {
                    if (v.getAddress() != null && !v.getAddress().isBlank()) {
                        activeWeights.put(v.getAddress(), validatorService.calculateWeight(v));
                    }
                }
            }

            if (peers == null || peers.isEmpty() || activeWeights.size() <= 1) {
                if (allowSoloFinalization) {
                    log.warn("[SECURITY][SOLO_MODE] Auto-finalizing block {} under explicit solo-development mode (app.consensus.allow-solo-finalization=true). NO BFT GUARANTEES.", block.getBlockHeight());
                    Map<String, Object> soloCert = buildSoloCertificate(block, proposerAddr, activeWeights);
                    return new VoteCollectionResult(true, block.getBlockHeight(), block.getBlockHash(), network, soloCert, null, null);
                } else {
                    log.error("[SECURITY] Solo finalization attempted for block {} but app.consensus.allow-solo-finalization=false. Failing closed.", block.getBlockHeight());
                    return new VoteCollectionResult(false, block.getBlockHeight(), block.getBlockHash(), network, null, null, "Solo finalization disallowed");
                }
            }

            // Raw votes collected from self and peers
            List<String> rawYesVoters = new ArrayList<>();
            Map<String, Object> voteSignatures = new LinkedHashMap<>();

            // Proposer always votes YES for its own valid proposal
            if (myAddr != null && !myAddr.isBlank() && activeWeights.containsKey(myAddr)) {
                rawYesVoters.add(myAddr);
                if (nodeIdentityService != null && nodeIdentityService.isPrivateKeyBacked()) {
                    try {
                        String canonical = "BLOCK_VOTE|" + network + "|" + block.getBlockHeight() + "|" + block.getBlockHash() + "|" + myAddr;
                        byte[] digest = com.m3rwallet.util.CryptoUtil.keccak256(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        byte[] sig = nodeIdentityService.signData(digest);
                        Map<String, Object> mySig = new LinkedHashMap<>();
                        mySig.put("signature", org.apache.commons.codec.binary.Hex.encodeHexString(sig));
                        mySig.put("publicKey", nodeIdentityService.getNodePublicKey());
                        voteSignatures.put(myAddr, mySig);
                    } catch (Exception ex) {
                        log.warn("[VOTE] Proposer failed to sign own vote: {}", ex.getMessage());
                    }
                }
            }

            // Build vote request
            Map<String, Object> voteRequest = new LinkedHashMap<>();
            voteRequest.put("blockHeight",     block.getBlockHeight());
            voteRequest.put("blockHash",       block.getBlockHash());
            voteRequest.put("proposerAddress", proposerAddr);
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
                    if (vote && voterAddress != null && !voterAddress.isBlank()) {
                        rawYesVoters.add(voterAddress);
                        Map<String, Object> sigInfo = new LinkedHashMap<>();
                        if (response.containsKey("signature")) {
                            sigInfo.put("signature", response.get("signature"));
                        }
                        if (response.containsKey("publicKey")) {
                            sigInfo.put("publicKey", response.get("publicKey"));
                        }
                        if (!sigInfo.isEmpty()) {
                            voteSignatures.put(voterAddress, sigInfo);
                        }
                        log.debug("[VOTE] YES from {} (addr: {})", peer, voterAddress);
                    } else {
                        log.debug("[VOTE] NO from {} reason: {}", peer, response.get("reason"));
                    }
                } catch (Exception e) {
                    log.warn("[VOTE] Peer {} unreachable: {}", peer, e.getMessage());
                }
            }

            // Evaluate quorum using formal conjunctive rule:
            // (W_yes / W_total >= 2/3) AND (Votes_yes >= ceil(2N/3)) AND (PeerConfirmation > 0)
            ConsensusQuorum.TallyResult tally = ConsensusQuorum.evaluateVotes(rawYesVoters, activeWeights, proposerAddr);

            log.info("[BLOCK VOTE] Height={} Votes={}/{} (req={}) Weight={}/{} ({:.1f}%) PeersConf={} Consensus={}",
                    block.getBlockHeight(), tally.yesVotes(), tally.totalValidators(), tally.requiredVotes(),
                    String.format("%.2f", tally.approvedWeight()), String.format("%.2f", tally.totalWeight()),
                    tally.weightRatio() * 100, tally.peerConfirmations(), tally.reached() ? "YES" : "NO");

            if (tally.reached()) {
                Map<String, Object> cert = new LinkedHashMap<>();
                cert.put("blockHeight", block.getBlockHeight());
                cert.put("blockHash", block.getBlockHash());
                cert.put("network", network);
                cert.put("proposerAddress", proposerAddr);
                cert.put("yesVotes", tally.yesVotes());
                cert.put("requiredVotes", tally.requiredVotes());
                cert.put("totalValidators", tally.totalValidators());
                cert.put("approvedWeight", tally.approvedWeight());
                cert.put("totalWeight", tally.totalWeight());
                cert.put("weightRatio", tally.weightRatio());
                cert.put("peerConfirmations", tally.peerConfirmations());
                cert.put("voters", new ArrayList<>(tally.acceptedVoters()));
                cert.put("signatures", voteSignatures);
                cert.put("finalizedAt", System.currentTimeMillis());
                storeCertificate(network, block.getBlockHeight(), block.getBlockHash(), cert);
                try {
                    String certJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(cert);
                    block.setConsensusCertificate(certJson);
                } catch (Exception ignored) {}
                return new VoteCollectionResult(true, block.getBlockHeight(), block.getBlockHash(), network, cert, tally, null);
            } else {
                return new VoteCollectionResult(false, block.getBlockHeight(), block.getBlockHash(), network, null, tally, "Quorum not reached");
            }
        } catch (Exception e) {
            log.warn("collectVotes failed: {}", e.getMessage());
            return new VoteCollectionResult(false, block.getBlockHeight(), block.getBlockHash(), network, null, null, e.getMessage());
        }
    }

    private Map<String, Object> buildSoloCertificate(Block block, String proposerAddr, Map<String, Double> activeWeights) {
        Map<String, Object> cert = new LinkedHashMap<>();
        cert.put("blockHeight", block.getBlockHeight());
        cert.put("blockHash", block.getBlockHash());
        cert.put("network", network);
        cert.put("proposerAddress", proposerAddr);
        cert.put("yesVotes", 1);
        cert.put("requiredVotes", 1);
        cert.put("totalValidators", Math.max(1, activeWeights.size()));
        double w = activeWeights.getOrDefault(proposerAddr, 1.0);
        cert.put("approvedWeight", w);
        cert.put("totalWeight", w);
        cert.put("weightRatio", 1.0);
        cert.put("peerConfirmations", 0);
        cert.put("voters", List.of(proposerAddr));
        Map<String, Object> signatures = new LinkedHashMap<>();
        if (nodeIdentityService != null && nodeIdentityService.isPrivateKeyBacked()) {
            try {
                String canonical = "BLOCK_VOTE|" + network + "|" + block.getBlockHeight() + "|" + block.getBlockHash() + "|" + proposerAddr;
                byte[] digest = com.m3rwallet.util.CryptoUtil.keccak256(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                byte[] sig = nodeIdentityService.signData(digest);
                Map<String, Object> mySig = new LinkedHashMap<>();
                mySig.put("signature", org.apache.commons.codec.binary.Hex.encodeHexString(sig));
                mySig.put("publicKey", nodeIdentityService.getNodePublicKey());
                signatures.put(proposerAddr, mySig);
            } catch (Exception ignored) {}
        }
        cert.put("signatures", signatures);
        cert.put("finalizedAt", System.currentTimeMillis());
        cert.put("isSoloCertificate", true);
        storeCertificate(network, block.getBlockHeight(), block.getBlockHash(), cert);
        try {
            String certJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(cert);
            block.setConsensusCertificate(certJson);
        } catch (Exception ignored) {}
        return cert;
    }
}
