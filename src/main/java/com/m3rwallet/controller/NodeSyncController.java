package com.m3rwallet.controller;

import com.m3rwallet.entity.Block;
import com.m3rwallet.service.PeerSyncService;
import com.m3rwallet.service.BlockBroadcastService;
import com.m3rwallet.repository.BlockRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/node")
@Slf4j
public class NodeSyncController {

    private final PeerSyncService peerSyncService;
    private final BlockRepository blockRepository;
    private final String network;
    private final String selfUrl;

    @Autowired(required = false)
    private BlockBroadcastService blockBroadcastService;

    @Autowired(required = false)
    private com.m3rwallet.service.BlockConsensusVoteService blockConsensusVoteService;

    public NodeSyncController(PeerSyncService peerSyncService,
                              BlockRepository blockRepository,
                              @Value("${app.blockchain.network:mainnet}") String network,
                              @Value("${app.node.self-url}") String selfUrl) {
        this.peerSyncService = peerSyncService;
        this.blockRepository = blockRepository;
        this.network = network;
        this.selfUrl = selfUrl;
    }

    public void setBlockBroadcastService(BlockBroadcastService blockBroadcastService) {
        this.blockBroadcastService = blockBroadcastService;
    }

    @PostMapping("/block/receive")
    @ResponseBody
    public ResponseEntity<?> receiveBlock(@RequestBody Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "invalid block", "status", "REJECTED"));
        }
        if (blockBroadcastService == null) {
            log.error("[RECEIVE] BlockBroadcastService unavailable to validate block reception");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "Consensus validation service unavailable"));
        }
        String effectiveNet = (String) payload.getOrDefault("network", this.network);
        Map<String, Object> result = blockBroadcastService.receiveBlock(payload, effectiveNet);
        String status = String.valueOf(result.getOrDefault("status", "REJECTED"));
        if ("ACCEPTED".equalsIgnoreCase(status)) {
            return ResponseEntity.status(HttpStatus.CREATED).body(result);
        } else if ("ALREADY_KNOWN".equalsIgnoreCase(status)) {
            return ResponseEntity.ok(result);
        } else if ("FORK_CONFLICT".equalsIgnoreCase(status)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(result);
        } else if ("STAGED_UNFINALIZED".equalsIgnoreCase(status)) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
        } else {
            return ResponseEntity.badRequest().body(result);
        }
    }

    @GetMapping("/block/latest")
    public ResponseEntity<?> latestBlocks(@RequestParam(required = false) String network,
                                          @RequestParam(required = false, defaultValue = "10") int limit) {
        String net = network == null ? this.network : network;
        if (limit <= 0 || limit > 50) limit = 50;
        List<Block> blocks = blockRepository.findByNetworkAndIsFinalized(net, true);
        int count = Math.min(limit, blocks.size());
        List<Block> slice = blocks.stream()
                .sorted((a, b) -> Long.compare(b.getBlockHeight(), a.getBlockHeight()))
                .limit(count)
                .toList();
        long latestHeight = slice.stream().mapToLong(Block::getBlockHeight).max().orElse(0L);
        // NOTE: `blocks` field omitted intentionally — serializing full Block entities with
        // their @OneToMany relations causes Jackson circular-reference overflow.
        // Callers that need block details should use GET /api/node/block/{height}.
        Map<String, Object> resp = new HashMap<>(Map.of(
                "network", net,
                "count", slice.size(),
                "latestBlockHeight", latestHeight
        ));
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/block/{height}")
    public ResponseEntity<?> blockByHeight(@PathVariable Long height) {
        Optional<Block> opt = blockRepository.findById(height);
        if (opt.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "not found"));
        Block b = opt.get();
        if ((b.getConsensusCertificate() == null || b.getConsensusCertificate().isBlank()) && blockConsensusVoteService != null) {
            Map<String, Object> cert = blockConsensusVoteService.getCertificate(b.getNetwork(), b.getBlockHeight(), b.getBlockHash());
            if (cert != null) {
                try {
                    b.setConsensusCertificate(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(cert));
                } catch (Exception ignored) {}
            }
        }
        return ResponseEntity.ok(b);
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        Optional<Block> latest = blockRepository.findTopByNetworkOrderByBlockHeightDesc(network);
        long latestHeight = latest.map(Block::getBlockHeight).orElse(0L);
        List<String> alive = peerSyncService.getAlivePeerUrls();
        List<?> allPeers = peerSyncService == null ? List.of() : peerSyncService.getAlivePeerUrls();
        Map<String, Object> resp = new HashMap<>();
        resp.put("status", "UP");
        resp.put("network", network);
        resp.put("selfUrl", selfUrl);
        resp.put("latestBlockHeight", latestHeight);
        resp.put("alivePeers", alive);
        resp.put("totalKnownPeers", allPeers.size());
        resp.put("timestamp", Instant.now().toEpochMilli());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/peer/announce")
    public ResponseEntity<?> announce(@RequestBody Map<String, String> body) {
        String peerUrl = body.get("peerUrl");
        peerSyncService.announceSelf(peerUrl);
        return ResponseEntity.ok(Map.of("accepted", true));
    }

    @GetMapping("/peer/list")
    public ResponseEntity<?> peerList(@RequestParam(required = false) String network) {
        String net = network == null ? this.network : network;
        List<String> list = peerSyncService.getAlivePeerUrls();
        return ResponseEntity.ok(Map.of("network", net, "peers", list));
    }
}