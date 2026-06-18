package com.m3rwallet.lifecycle;

import com.m3rwallet.entity.Block;
import com.m3rwallet.repository.BlockRepository;
import com.m3rwallet.service.FeeDistributionService;
import com.m3rwallet.service.PeerSyncService;
import com.m3rwallet.service.MempoolService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Component
@Slf4j
public class NodeStartupRecovery {

    @Autowired(required = false)
    private PeerSyncService peerSyncService;

    @Autowired(required = false)
    private MempoolService mempoolService;

    @Autowired(required = false)
    private BlockRepository blockRepository;

    @Autowired(required = false)
    private FeeDistributionService feeDistributionService;

    @Value("${app.blockchain.network:mainnet}")
    private String network;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            log.info("Application ready: performing startup recovery tasks");
            if (peerSyncService != null) {
                try {
                    peerSyncService.discoverPeersFromNetwork();
                } catch (Exception e) {
                    log.warn("Peer discovery on startup failed: {}", e.getMessage());
                }
                try {
                    peerSyncService.syncMissingBlocks();
                } catch (Exception e) {
                    log.warn("Sync missing blocks on startup failed: {}", e.getMessage());
                }
            }

            if (mempoolService != null) {
                try {
                    int size = mempoolService.size(null);
                    log.info("Mempool contains {} entries at startup", size);
                } catch (Exception e) {
                    log.warn("Mempool check on startup failed: {}", e.getMessage());
                }
            }

            if (blockRepository != null && feeDistributionService != null) {
                try {
                    List<Block> finalized = blockRepository.findByNetworkAndIsFinalized(network, true);
                    int checked = 0;
                    for (Block block : finalized) {
                        feeDistributionService.reconcileFinalizedBlockFees(block, network);
                        blockRepository.save(block);
                        checked++;
                    }
                    log.info("Reconciled fee rewards for {} finalized blocks on {}", checked, network);
                } catch (Exception e) {
                    log.warn("Fee reward reconciliation on startup failed: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Startup recovery encountered an error: {}", e.getMessage());
        }
    }
}
