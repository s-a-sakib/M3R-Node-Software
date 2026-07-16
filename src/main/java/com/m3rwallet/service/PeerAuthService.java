package com.m3rwallet.service;

import com.m3rwallet.config.ConsensusProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Shared-secret authorization for peer-to-peer node endpoints. Fails closed:
 * if no shared secret is configured, all peer requests are rejected.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PeerAuthService {

    public static final String CONSENSUS_TOKEN_HEADER = "X-M3R-Consensus-Token";

    private final ConsensusProperties consensusProperties;

    public boolean isAuthorized(String presentedToken) {
        String expected = consensusProperties.getSharedSecret();
        if (expected == null || expected.isBlank()) {
            log.warn("[PEER-AUTH] Rejecting peer request: app.consensus.shared-secret is not configured");
            return false;
        }
        if (presentedToken == null || presentedToken.isBlank()) {
            return false;
        }
        return constantTimeEquals(expected, presentedToken);
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
