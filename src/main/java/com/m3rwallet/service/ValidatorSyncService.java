package com.m3rwallet.service;

import com.m3rwallet.config.ConsensusProperties;
import com.m3rwallet.entity.Validator;
import com.m3rwallet.repository.ValidatorRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class ValidatorSyncService {

    @Autowired
    private ConsensusProperties consensusProperties;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ValidatorRepository validatorRepository;

    @Value("${app.blockchain.network:mainnet}")
    private String network;

    @PostConstruct
    public void startupValidatorSync() {
        try {
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(5000L);
                    syncValidatorSet();
                } catch (Exception e) {
                    log.warn("[VALIDATOR SYNC] Startup reconcile failed: {}", e.getMessage());
                }
            }, "validator-sync-startup");
            t.setDaemon(true);
            t.start();
        } catch (Exception e) {
            log.warn("[VALIDATOR SYNC] Could not schedule startup reconcile: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${app.validator.sync-interval-ms:30000}", initialDelayString = "${app.validator.sync-initial-delay-ms:10000}")
    public void scheduledValidatorSync() {
        syncValidatorSet();
    }

    public void broadcastValidatorRegistration(Validator v) {
        try {
            long now = System.currentTimeMillis();
            if (v == null) return;
            List<String> peers = consensusProperties.getPeers();
            if (peers == null || peers.isEmpty() || v == null) {
                return;
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("address", v.getAddress());
            payload.put("network", v.getNetwork());
            payload.put("stakeAmount", v.getStakedAmount().toString());
            payload.put("reliabilityScoreScaled", v.getReliabilityScoreScaled());
            payload.put("publicKey", "");
            payload.put("source", "peer-sync");

            for (String peer : peers) {
                try {
                    String url = stripTrailingSlash(peer) + "/" + network + "/validator/receive";
                    restTemplate.postForEntity(url, payload, Map.class);
                    log.info("[VALIDATOR SYNC] Broadcast to {}: {}", peer, v.getAddress());
                } catch (Exception e) {
                    log.warn("[VALIDATOR SYNC] Failed to sync to {}: {}", peer, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[VALIDATOR SYNC] Broadcast failed: {}", e.getMessage());
        }
    }

    public void syncValidatorSet() {
        try {
            broadcastLocalValidators();
            pullValidatorsFromPeers();
        } catch (Exception e) {
            log.warn("[VALIDATOR SYNC] Reconcile failed: {}", e.getMessage());
        }
    }

    private void broadcastLocalValidators() {
        try {
            List<Validator> active = validatorRepository.findByNetworkAndStatus(network, Validator.ValidatorStatus.ACTIVE);
            if (active == null || active.isEmpty()) {
                return;
            }
            for (Validator validator : active) {
                try {
                    broadcastValidatorRegistration(validator);
                } catch (Exception e) {
                    log.warn("[VALIDATOR SYNC] Could not broadcast local validator {}: {}",
                            validator.getAddress(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[VALIDATOR SYNC] Local broadcast pass failed: {}", e.getMessage());
        }
    }

    private void pullValidatorsFromPeers() {
        try {
            List<String> peers = consensusProperties.getPeers();
            if (peers == null || peers.isEmpty()) {
                return;
            }
            for (String peer : peers) {
                try {
                    String url = stripTrailingSlash(peer) + "/api/validator/list?network=" + network;
                    Map<?, ?> response = restTemplate.getForObject(url, Map.class);
                    Object rawValidators = response == null ? null : response.get("validators");
                    if (!(rawValidators instanceof List<?> validators)) {
                        continue;
                    }
                    int imported = 0;
                    for (Object raw : validators) {
                        if (raw instanceof Map<?, ?> validatorMap && savePeerValidator(validatorMap)) {
                            imported++;
                        }
                    }
                    if (imported > 0) {
                        log.info("[VALIDATOR SYNC] Imported {} validators from {}", imported, peer);
                    }
                } catch (Exception e) {
                    log.warn("[VALIDATOR SYNC] Failed to pull validators from {}: {}", peer, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[VALIDATOR SYNC] Peer pull pass failed: {}", e.getMessage());
        }
    }

    private boolean savePeerValidator(Map<?, ?> validatorMap) {
        try {
            String address = stringValue(validatorMap.get("address"));
            String validatorNetwork = stringValue(validatorMap.get("network"));
            if (address == null || address.isBlank()) {
                return false;
            }
            if (validatorNetwork == null || validatorNetwork.isBlank()) {
                validatorNetwork = network;
            }
            if (!network.equals(validatorNetwork)) {
                return false;
            }
            var existing = validatorRepository.findByAddressAndNetwork(address, validatorNetwork);
            Validator v;
            if (existing.isPresent()) {
                v = existing.get();
                // Update stake if provided
                try {
                    var parsedStake = parseStake(validatorMap.get("stakedAmount"));
                    if (parsedStake != null) v.setStakedAmount(parsedStake);
                } catch (Exception ignored) {
                }
                // Update reliability score to the maximum observed
                try {
                    String rvRaw = stringValue(validatorMap.get("reliabilityScoreScaled"));
                    if (rvRaw != null && !rvRaw.isBlank()) {
                        long peerRv = Long.parseLong(rvRaw);
                        Long localRv = v.getReliabilityScoreScaled() == null ? 0L : v.getReliabilityScoreScaled();
                        if (peerRv > localRv) {
                            v.setReliabilityScoreScaled(peerRv);
                        }
                    }
                } catch (Exception ignored) {
                }
            } else {
                v = new Validator();
                v.setAddress(address);
                v.setNetwork(validatorNetwork);
                v.setStakedAmount(parseStake(validatorMap.get("stakedAmount")));
                try {
                    String rvRaw = stringValue(validatorMap.get("reliabilityScoreScaled"));
                    long peerRv = (rvRaw == null || rvRaw.isBlank()) ? 0L : Long.parseLong(rvRaw);
                    v.setReliabilityScoreScaled(peerRv);
                } catch (Exception ignored) {
                    v.setReliabilityScoreScaled(0L);
                }
                v.setStatus(Validator.ValidatorStatus.ACTIVE);
                v.setRegisteredAt(System.currentTimeMillis());
                v.setTotalProposals(0L);
                v.setSuccessfulProposals(0L);
                v.setCorruptedProposals(0L);
            }

            validatorRepository.save(v);
            log.info("[VALIDATOR SYNC] Saved/Updated peer validator: {} on {}", address, validatorNetwork);
            return true;
        } catch (Exception e) {
            log.warn("[VALIDATOR SYNC] Could not save peer validator: {}", e.getMessage());
            return false;
        }
    }

    private BigDecimal parseStake(Object value) {
        try {
            if (value == null) {
                return BigDecimal.valueOf(10000L);
            }
            return new BigDecimal(String.valueOf(value));
        } catch (Exception e) {
            return BigDecimal.valueOf(10000L);
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
