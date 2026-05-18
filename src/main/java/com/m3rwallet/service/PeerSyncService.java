package com.m3rwallet.service;

import com.m3rwallet.entity.Block;
import com.m3rwallet.entity.Peer;
import com.m3rwallet.repository.BlockRepository;
import com.m3rwallet.repository.PeerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.*;

@Service
@Slf4j
public class PeerSyncService {

    private final PeerRepository peerRepository;
    private final BlockRepository blockRepository;
    private final RestTemplate restTemplate;
    private final String selfUrl;
    private final String bootstrapPeersConfig;
    private final String network;

    private static final int MAX_FAIL_COUNT = 5;
    private static final long HEALTH_CHECK_INTERVAL = 30_000L;

    public PeerSyncService(PeerRepository peerRepository,
                           BlockRepository blockRepository,
                           RestTemplate restTemplate,
                           @Value("${app.node.self-url}") String selfUrl,
                           @Value("${app.node.bootstrap-peers:}") String bootstrapPeersConfig,
                           @Value("${app.blockchain.network:mainnet}") String network) {
        this.peerRepository = peerRepository;
        this.blockRepository = blockRepository;
        this.restTemplate = restTemplate;
        this.selfUrl = selfUrl;
        this.bootstrapPeersConfig = bootstrapPeersConfig;
        this.network = network;
    }

    @PostConstruct
    public void init() {
        List<String> seeds = new ArrayList<>();
        if (bootstrapPeersConfig != null && !bootstrapPeersConfig.isBlank()) {
            String[] parts = bootstrapPeersConfig.split(",");
            for (String p : parts) {
                String t = p.trim();
                if (!t.isBlank() && !t.equals(selfUrl)) seeds.add(t);
            }
        }
        long now = Instant.now().toEpochMilli();
        for (String seed : seeds) {
            try {
                Optional<Peer> existing = peerRepository.findByPeerUrlAndNetwork(seed, network);
                if (existing.isPresent()) {
                    Peer p = existing.get();
                    p.setIsAlive(true);
                    p.setFirstSeenAt(p.getFirstSeenAt() == null ? now : p.getFirstSeenAt());
                    peerRepository.save(p);
                } else {
                    Peer p = new Peer();
                    p.setPeerUrl(seed);
                    p.setNetwork(network);
                    p.setIsAlive(true);
                    p.setFirstSeenAt(now);
                    peerRepository.save(p);
                }
            } catch (Exception e) {
                log.warn("Failed to upsert bootstrap peer {}: {}", seed, e.getMessage());
            }
        }
        log.info("Bootstrapped with {} seed peers", seeds.size());
        try {
            discoverPeersFromNetwork();
        } catch (Exception e) {
            log.warn("Initial peer discovery failed: {}", e.getMessage());
        }
    }

    @Transactional
    public void announceSelf(String peerUrl) {
        if (peerUrl == null || peerUrl.isBlank()) return;
        if (peerUrl.equals(selfUrl)) return;
        long now = Instant.now().toEpochMilli();
        try {
            Optional<Peer> opt = peerRepository.findByPeerUrlAndNetwork(peerUrl, network);
            Peer p;
            if (opt.isPresent()) {
                p = opt.get();
                p.setLastSeenAt(now);
                p.setIsAlive(true);
                p.setFailCount(0);
            } else {
                p = new Peer();
                p.setPeerUrl(peerUrl);
                p.setNetwork(network);
                p.setIsAlive(true);
                p.setFirstSeenAt(now);
                p.setLastSeenAt(now);
                p.setFailCount(0);
            }
            peerRepository.save(p);
            log.info("Peer announced: {}", peerUrl);
            // best-effort: fetch their peer list
            try {
                String url = peerUrl + "/api/node/peer/list?network=" + network;
                ResponseEntity<Object> resp = restTemplate.getForEntity(url, Object.class);
                Object body = resp.getBody();
                // tolerant: accept either raw array response or { peers: [...] } object
                List<String> urls = new ArrayList<>();
                if (body instanceof List<?> listBody) {
                    for (Object o : listBody) if (o != null) urls.add(String.valueOf(o).trim());
                } else if (body instanceof Map<?, ?> mapBody) {
                    Object peersObj = mapBody.get("peers");
                    if (peersObj instanceof List<?> pList) {
                        for (Object o : pList) if (o != null) urls.add(String.valueOf(o).trim());
                    }
                }
                // ignore response content; discovery will pick them up later
            } catch (Exception ex) {
                log.debug("Ignoring error while fetching peers from {}: {}", peerUrl, ex.getMessage());
            }
        } catch (Exception e) {
            log.warn("Failed to announce peer {}: {}", peerUrl, e.getMessage());
        }
    }

    public void discoverPeersFromNetwork() {
        List<Peer> alive = peerRepository.findByNetworkAndIsAlive(network, true);
        int discovered = 0;
        long now = Instant.now().toEpochMilli();
        for (Peer peer : alive) {
            String base = peer.getPeerUrl();
                try {
                    String url = base + "/api/node/peer/list?network=" + network;
                    ResponseEntity<Object> resp = restTemplate.getForEntity(url, Object.class);
                    Object body = resp.getBody();
                    List<String> urls = new ArrayList<>();
                    if (body instanceof List<?> listBody) {
                        for (Object o : listBody) if (o != null) urls.add(String.valueOf(o).trim());
                    } else if (body instanceof Map<?, ?> mapBody) {
                        Object peersObj = mapBody.get("peers");
                        if (peersObj instanceof List<?> pList) {
                            for (Object o : pList) if (o != null) urls.add(String.valueOf(o).trim());
                        }
                    }
                    if (urls.isEmpty()) continue;
                    for (String u : urls) {
                        if (u == null) continue;
                        String trimmed = u.trim();
                        if (trimmed.isBlank() || trimmed.equals(selfUrl)) continue;
                        Optional<Peer> exists = peerRepository.findByPeerUrlAndNetwork(trimmed, network);
                        if (exists.isEmpty()) {
                            Peer np = new Peer();
                            np.setPeerUrl(trimmed);
                            np.setNetwork(network);
                            np.setIsAlive(true);
                            np.setFirstSeenAt(now);
                            peerRepository.save(np);
                            discovered++;
                        }
                    }
                    log.info("Discovered {} new peers from {}", discovered, base);
                } catch (Exception e) {
                    log.warn("Peer discovery failed from {}: {}", base, e.getMessage());
                }
        }
    }

    @Transactional
    public void broadcastBlock(Block block) {
        List<Peer> peers = peerRepository.findByNetworkAndIsAlive(network, true);
        long now = Instant.now().toEpochMilli();
        for (Peer peer : peers) {
            try {
                String url = peer.getPeerUrl() + "/api/node/block/receive";
                HttpEntity<Block> req = new HttpEntity<>(block);
                restTemplate.postForEntity(url, req, String.class);
                peer.setLastSeenAt(now);
                peer.setFailCount(0);
                peerRepository.save(peer);
            } catch (Exception e) {
                int fails = Optional.ofNullable(peer.getFailCount()).orElse(0) + 1;
                peer.setFailCount(fails);
                if (fails >= MAX_FAIL_COUNT) {
                    peer.setIsAlive(false);
                    log.warn("Peer {} marked dead after {} failures", peer.getPeerUrl(), fails);
                } else {
                    log.warn("Block broadcast failed to {}: {}", peer.getPeerUrl(), e.getMessage());
                }
                peerRepository.save(peer);
            }
        }
    }

    @Transactional
    public void syncMissingBlocks() {
        Optional<Block> latest = blockRepository.findTopByNetworkOrderByBlockHeightDesc(network);
        long localHeight = latest.map(Block::getBlockHeight).orElse(0L);
        List<Peer> peers = peerRepository.findByNetworkAndIsAlive(network, true);
        for (Peer peer : peers) {
            try {
                String url = peer.getPeerUrl() + "/api/node/block/latest?network=" + network;
                ResponseEntity<Map> resp = restTemplate.getForEntity(url, Map.class);
                Map body = resp.getBody();
                if (body == null) continue;
                Object h = body.get("latestBlockHeight");
                long peerHeight = 0L;
                if (h instanceof Number) peerHeight = ((Number) h).longValue();
                else if (h instanceof String) peerHeight = Long.parseLong((String) h);
                if (peerHeight > localHeight) {
                    for (long ht = localHeight + 1; ht <= peerHeight; ht++) {
                        try {
                            String g = peer.getPeerUrl() + "/api/node/block/" + ht;
                            ResponseEntity<Block> be = restTemplate.getForEntity(g, Block.class);
                            Block fetched = be.getBody();
                            if (fetched != null) {
                                Optional<Block> already = blockRepository.findByBlockHashAndNetwork(fetched.getBlockHash(), network);
                                if (already.isEmpty()) {
                                    blockRepository.save(fetched);
                                    log.info("Synced missing block {} from peer {}", ht, peer.getPeerUrl());
                                }
                            }
                        } catch (Exception ex) {
                            log.warn("Failed to fetch block {} from {}: {}", ht, peer.getPeerUrl(), ex.getMessage());
                        }
                    }
                    break;
                }
            } catch (Exception e) {
                log.warn("Failed to query latest block from {}: {}", peer.getPeerUrl(), e.getMessage());
            }
        }
    }

    @Scheduled(fixedRateString = "${app.node.peer-health-check-ms:30000}")
    public void healthCheckPeers() {
        try {
            List<Peer> all = peerRepository.findByNetwork(network);
            int alive = 0, dead = 0;
            for (Peer p : all) {
                try {
                    String url = p.getPeerUrl() + "/api/node/health";
                    ResponseEntity<Map> resp = restTemplate.getForEntity(url, Map.class);
                    if (resp.getStatusCode().is2xxSuccessful()) {
                        p.setIsAlive(true);
                        p.setFailCount(0);
                        p.setLastSeenAt(Instant.now().toEpochMilli());
                        alive++;
                    } else {
                        p.setFailCount(Optional.ofNullable(p.getFailCount()).orElse(0) + 1);
                        if (p.getFailCount() >= MAX_FAIL_COUNT) p.setIsAlive(false);
                        dead++;
                    }
                } catch (Exception e) {
                    p.setFailCount(Optional.ofNullable(p.getFailCount()).orElse(0) + 1);
                    if (p.getFailCount() >= MAX_FAIL_COUNT) p.setIsAlive(false);
                    dead++;
                }
                peerRepository.save(p);
            }
            log.info("Health check done. Alive: {}, Dead: {}", alive, dead);
        } catch (Exception e) {
            log.warn("Peer health check failed: {}", e.getMessage());
        }
        try {
            discoverPeersFromNetwork();
        } catch (Exception ex) {
            log.warn("Discover after health-check failed: {}", ex.getMessage());
        }
    }

    public List<String> getAlivePeerUrls() {
        return peerRepository.findByNetworkAndIsAlive(network, true).stream().map(Peer::getPeerUrl).toList();
    }
}
