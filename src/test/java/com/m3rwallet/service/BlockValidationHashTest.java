package com.m3rwallet.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class BlockValidationHashTest {
    private final BlockValidationService service = new BlockValidationService();

    @Test
    void blockHashIsDeterministicAndDetectsHeaderTampering() {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("version", 1);
        header.put("blockHeight", 2L);
        header.put("slotNumber", 8L);
        header.put("parentBlockHash", "a".repeat(64));
        header.put("merkleRoot", "b".repeat(64));
        header.put("timestamp", 1_700_000_000_000L);
        header.put("proposerAddress", "validator-1");
        header.put("nonce", 7L);
        String original = service.computeBlockHash(header);
        assertEquals(original, service.computeBlockHash(header));
        header.put("nonce", 8L);
        assertNotEquals(original, service.computeBlockHash(header));
    }
}
