package com.m3rwallet.util;

import org.bitcoinj.core.Base58;
import org.apache.commons.codec.binary.Hex;
import java.security.MessageDigest;
import java.util.Arrays;

public class AddressUtil {
    private static final byte M3R_VERSION = (byte) 0x35;

    /**
     * Normalize address - lowercase and remove 0x prefix
     */
    public static String normalizeAddr(String addr) {
        if (addr == null || addr.isEmpty()) {
            return null;
        }
        String trimmed = addr.trim();
        if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
            return trimmed.substring(2).toLowerCase();
        }
        return trimmed.toLowerCase();
    }

    /**
     * Resolve an external address into the canonical hex-20 value used by the database.
     * Base58 is case-sensitive, so callers must not lowercase before decoding.
     */
    public static String resolveToHex20(String addr) {
        if (addr == null || addr.isBlank()) {
            return null;
        }

        String trimmed = addr.trim();
        String noPrefix = (trimmed.startsWith("0x") || trimmed.startsWith("0X"))
                ? trimmed.substring(2)
                : trimmed;

        if (noPrefix.matches("(?i)^[0-9a-f]{40}$")) {
            return noPrefix.toLowerCase();
        }

        return decodeBase58ToHex20(trimmed);
    }

    /**
     * Decode Base58 address to Hex20 (20 byte address)
     */
    public static String decodeBase58ToHex20(String pubAddr) {
        try {
            byte[] decoded = Base58.decode(pubAddr);
            if (decoded.length == 25) {
                byte[] data = Arrays.copyOfRange(decoded, 0, 21);
                byte[] checksum = Arrays.copyOfRange(decoded, 21, 25);
                byte[] expectedChecksum = checksum(data);
                if (decoded[0] != M3R_VERSION || !Arrays.equals(checksum, expectedChecksum)) {
                    return null;
                }

                byte[] addr20 = new byte[20];
                System.arraycopy(decoded, 1, addr20, 0, 20);
                return Hex.encodeHexString(addr20);
            }
        } catch (Exception e) {
            System.err.println("[Base58 Decode Error] " + e.getMessage());
        }
        return null;
    }

    /**
     * Encode Hex20 to Base58
     */
    public static String encodeHex20ToBase58(String hex20) {
        try {
            String normalized = normalizeAddr(hex20);
            if (normalized == null || !normalized.matches("^[0-9a-f]{40}$")) {
                return hex20;
            }

            byte[] addressBytes = Hex.decodeHex(hex20);
            byte[] data = new byte[21];
            data[0] = M3R_VERSION;
            System.arraycopy(addressBytes, 0, data, 1, 20);

            byte[] checksum = checksum(data);
            byte[] full = new byte[25];
            System.arraycopy(data, 0, full, 0, data.length);
            System.arraycopy(checksum, 0, full, data.length, checksum.length);
            return Base58.encode(full);
        } catch (Exception e) {
            System.err.println("[Base58 Encode Error] " + e.getMessage());
        }
        return null;
    }

    private static byte[] checksum(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] first = digest.digest(data);
        byte[] second = digest.digest(first);
        return Arrays.copyOfRange(second, 0, 4);
    }

    /**
     * Compute Keccak256 hash of input bytes using SHA3-256
     * Note: Using SHA-256 as placeholder - for production use Keccak-256 library
     */
    public static String keccak256(byte[] input) {
        try {
            // Use SHA-256 for now (production should use Web3j or similar for Keccak-256)
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(input);
            return Hex.encodeHexString(digest.digest());
        } catch (Exception e) {
            System.err.println("[Keccak256 Error] " + e.getMessage());
            return null;
        }
    }

    /**
     * Compute Keccak256 hash of hex string
     */
    public static String keccak256Hex(String hexString) {
        try {
            byte[] bytes = Hex.decodeHex(hexString);
            return keccak256(bytes);
        } catch (Exception e) {
            System.err.println("[Keccak256 Error] " + e.getMessage());
        }
        return null;
    }
}
