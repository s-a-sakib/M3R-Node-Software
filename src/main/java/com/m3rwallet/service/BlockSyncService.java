package com.m3rwallet.service;

import com.m3rwallet.config.ConsensusProperties;
import com.m3rwallet.entity.Block;
import com.m3rwallet.repository.BlockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class BlockSyncService {

    private final BlockRepository blockRepository;
    private final ConsensusProperties consensusProperties;
    private final RestTemplate restTemplate;
    private final BlockBroadcastService blockBroadcastService; // reuse receive logic
    private final AccountReconciliationService accountReconciliationService;

    @Value("${app.blockchain.network:mainnet}")
    private String network;

    @Scheduled(fixedRateString = "${app.node.block-sync-interval-ms:8000}", initialDelayString = "${app.node.block-sync-initial-delay-ms:8000}")
    public void syncMissingBlocks() {
        if (consensusProperties == null || !consensusProperties.isEnabled()) return;

        String net = network == null ? "mainnet" : network;
        long localHeight = blockRepository.findTopByNetworkOrderByBlockHeightDesc(net)
                .map(Block::getBlockHeight).orElse(0L);

        List<String> peers = consensusProperties.getPeers();
        if (peers == null || peers.isEmpty()) return;

        for (String peer : peers) {
            try {
                Map response = restTemplate.getForObject(peer + "/" + net + "/blocks?size=1", Map.class);
                if (response == null) continue;
                long peerHeight = 0L;
                Object blocksObj = response.get("blocks");
                if (blocksObj instanceof List<?> list && !list.isEmpty()) {
                    Object first = list.get(0);
                    if (first instanceof Map<?, ?> m) {
                        Object h = m.get("blockHeight");
                        if (h instanceof Number) peerHeight = ((Number) h).longValue();
                        else if (h != null) peerHeight = Long.parseLong(String.valueOf(h));
                    }
                } else {
                    Object total = response.get("totalElements");
                    if (total instanceof Number) peerHeight = ((Number) total).longValue();
                    else if (total != null) {
                        try { peerHeight = Long.parseLong(String.valueOf(total)); } catch (Exception ignored) {}
                    }
                }

                if (peerHeight > localHeight) {
                    log.info("[SYNC] Peer {} has higher height {} > local {}", peer, peerHeight, localHeight);
                    for (long h = localHeight + 1; h <= peerHeight; h++) {
                        receiveBlockFromPeer(peer, h, net);
                    }
                    // After catching up blocks, trigger account reconciliation to heal minor divergences
                    try {
                        accountReconciliationService.reconcileAccounts();
                    } catch (Exception e) {
                        log.debug("[SYNC] Account reconciliation failed: {}", e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.debug("[SYNC] Peer {} sync failed: {}", peer, e.getMessage());
            }
        }
    }

    private void receiveBlockFromPeer(String peer, long height, String network) {
        try {
            Map payload = restTemplate.getForObject(peer + "/" + network + "/blocks/" + height, Map.class);
            if (payload != null) {
                blockBroadcastService.receiveBlock(payload, network); // reuse receive logic
            }
        } catch (Exception ignored) {
            log.debug("[SYNC] Failed to fetch block {} from {}: {}", height, peer, ignored.getMessage());
        }
    }
}
