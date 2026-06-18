package com.m3rwallet.util;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Normalizes peer URLs and filters out the local node to avoid duplicate
 * consensus/reconciliation calls against the same JVM.
 */
public final class PeerUrlUtil {

    private PeerUrlUtil() {
    }

    public static String normalize(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    public static boolean isSameOrigin(String left, String right) {
        return normalize(left).equalsIgnoreCase(normalize(right));
    }

    public static List<String> remotePeers(List<String> configuredPeers, String selfUrl) {
        if (configuredPeers == null || configuredPeers.isEmpty()) {
            return List.of();
        }
        String self = normalize(selfUrl);
        return configuredPeers.stream()
                .filter(peer -> peer != null && !peer.isBlank())
                .map(PeerUrlUtil::normalize)
                .filter(peer -> self.isEmpty() || !peer.equalsIgnoreCase(self))
                .distinct()
                .collect(Collectors.toList());
    }
}
