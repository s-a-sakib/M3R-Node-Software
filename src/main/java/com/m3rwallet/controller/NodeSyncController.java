package com.m3rwallet.controller;

import com.m3rwallet.entity.Block;
import com.m3rwallet.service.PeerAuthService;
import com.m3rwallet.service.PeerSyncService;
import com.m3rwallet.repository.BlockRepository;
import lombok.extern.slf4j.Slf4j;
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
    private final PeerAuthService peerAuthService;

    public NodeSyncController(PeerSyncService peerSyncService,
                              BlockRepository blockRepository,
                              @Value("${app.blockchain.network:mainnet}") String network,
                              @Value("${app.node.self-url}") String selfUrl,
                              PeerAuthService peerAuthService) {
        this.peerSyncService = peerSyncService;
        this.blockRepository = blockRepository;
        this.network = network;
        this.selfUrl = selfUrl;
        this.peerAuthService = peerAuthService;
    }

    @PostMapping("/block/receive")
    @Transactional
    public ResponseEntity<?> receiveBlock(@RequestBody Block block,
            @RequestHeader(value = PeerAuthService.CONSENSUS_TOKEN_HEADER, required = false) String token) {
        if (!peerAuthService.isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "invalid or missing consensus token"));
        }
        if (block == null || block.getBlockHash() == null || block.getBlockHash().isBlank() || block.getBlockHeight() == null || block.getBlockHeight() <= 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "invalid block"));
        }
        Optional<Block> exists = blockRepository.findByBlockHashAndNetwork(block.getBlockHash(), block.getNetwork());
        if (exists.isPresent()) {
            return ResponseEntity.ok(Map.of("message", "Already have block"));
        }
        Optional<Block> sameHeight = blockRepository.findById(block.getBlockHeight());
        if (sameHeight.isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "message", "height already exists",
                    "blockHeight", block.getBlockHeight()));
        }
        if (block.getBlockHeight() > 1) {
            Optional<Block> parent = blockRepository.findById(block.getBlockHeight() - 1);
            if (parent.isEmpty() || !java.util.Objects.equals(parent.get().getBlockHash(), block.getParentBlockHash())) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                        "message", "parent missing or mismatched",
                        "blockHeight", block.getBlockHeight()));
            }
        }
        block.setIsFinalized(true);
        blockRepository.save(block);
        log.info("Received block height={} from peer network={}", block.getBlockHeight(), block.getNetwork());
        return ResponseEntity.status(HttpStatus.CREATED).body(block);
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
        return ResponseEntity.ok(opt.get());
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
    public ResponseEntity<?> announce(@RequestBody Map<String, String> body,
            @RequestHeader(value = PeerAuthService.CONSENSUS_TOKEN_HEADER, required = false) String token) {
        if (!peerAuthService.isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("accepted", false, "error", "invalid or missing consensus token"));
        }
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
