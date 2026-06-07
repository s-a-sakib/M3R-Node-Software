package com.m3rwallet.lifecycle;

import com.m3rwallet.service.PeerSyncService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import jakarta.annotation.PreDestroy;

@Component
@Slf4j
public class NodeShutdownHandler {

    @Autowired(required = false)
    private PeerSyncService peerSyncService;

    @PreDestroy
    public void onShutdown() {
        try {
            log.info("Node shutting down: attempting graceful peer notifications");
            if (peerSyncService != null) {
                try {
                    var peers = peerSyncService.getAlivePeerUrls();
                    log.info("Notifying {} peers about shutdown (best-effort)", peers.size());
                } catch (Exception e) {
                    log.warn("Failed to enumerate peers during shutdown: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Shutdown handler encountered error: {}", e.getMessage());
        }
    }
}
