package com.m3rwallet.util;

import org.bitcoinj.core.Base58;
import org.apache.commons.codec.binary.Hex;
import java.security.MessageDigest;

public class AddressUtil {

    /**
     * Normalize address - lowercase and remove 0x prefix
     */
    public static String normalizeAddr(String addr) {
        if (addr == null || addr.isEmpty()) {
            return null;
        }
        return addr.toLowerCase().replace("0x", "");
    }

    /**
     * Decode Base58 address to Hex20 (20 byte address)
     */
    public static String decodeBase58ToHex20(String pubAddr) {
        try {
            byte[] decoded = Base58.decode(pubAddr);
            if (decoded.length == 25) {
                // Extract 20 bytes from position 1-21 (skip version byte, skip checksum)
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
            byte[] addressBytes = Hex.decodeHex(hex20);
            byte[] withVersion = new byte[25];
            withVersion[0] = (byte) 0x26; // Version byte for M3R
            System.arraycopy(addressBytes, 0, withVersion, 1, 20);
            // Checksum calculation (simplified - use proper implementation)
            return Base58.encode(withVersion);
        } catch (Exception e) {
            System.err.println("[Base58 Encode Error] " + e.getMessage());
        }
        return null;
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
