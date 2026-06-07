package com.m3rwallet.lifecycle;

import com.m3rwallet.service.PeerSyncService;
import com.m3rwallet.service.MempoolService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class NodeStartupRecovery {

    @Autowired(required = false)
    private PeerSyncService peerSyncService;

    @Autowired(required = false)
    private MempoolService mempoolService;

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
        } catch (Exception e) {
            log.warn("Startup recovery encountered an error: {}", e.getMessage());
        }
    }
}
