package com.m3rwallet.service;

import com.m3rwallet.config.ConsensusProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerAuthServiceTest {

    @Test
    void rejectsRequestsWhenSharedSecretIsBlank() {
        ConsensusProperties properties = new ConsensusProperties();
        properties.setSharedSecret("");
        PeerAuthService service = new PeerAuthService(properties);

        assertFalse(service.isAuthorized("anything"));
    }

    @Test
    void rejectsMissingOrWrongToken() {
        ConsensusProperties properties = new ConsensusProperties();
        properties.setSharedSecret("local-test-secret");
        PeerAuthService service = new PeerAuthService(properties);

        assertFalse(service.isAuthorized(null));
        assertFalse(service.isAuthorized(""));
        assertFalse(service.isAuthorized("wrong-secret"));
    }

    @Test
    void acceptsMatchingToken() {
        ConsensusProperties properties = new ConsensusProperties();
        properties.setSharedSecret("local-test-secret");
        PeerAuthService service = new PeerAuthService(properties);

        assertTrue(service.isAuthorized("local-test-secret"));
    }
}
