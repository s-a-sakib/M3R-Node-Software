package com.m3rwallet;

import com.m3rwallet.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableTransactionManagement
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class M3RWalletServerApplication {
    private final WalletService walletService;

    public static void main(String[] args) {
        SpringApplication.run(M3RWalletServerApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeNetworks() {
        log.info("========================================");
        log.info("M3R Wallet Server Starting");
        log.info("========================================");
        
        try {
            walletService.initializeNetworks();
            log.info("========================================");
            log.info("Genesis initialization complete!");
            log.info("Node Dashboard: http://localhost:3000/");
            log.info("Admin Dashboard: http://localhost:3000/admin");
            log.info("========================================");
        } catch (Exception e) {
            log.error("Failed to initialize networks", e);
        }
    }
}
