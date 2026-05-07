package com.m3rwallet.util;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;

@Slf4j
public class TxDecoder {

    @Data
    @Builder
    public static class ParsedPayload {
        private String toAddr;
        private BigInteger amount;
        private String escrowId;
        private String buyer;
        private String seller;
        private String arbiter;
        private BigInteger expiryTs;
        private int releaseMode;
        private int disputeMode;
        private String metaHash;
    }

    @Data
    @Builder
    public static class ParsedTx {
        private boolean valid;
        private String error;

        private int version;
        private long chainId;
        private int type;
        private BigInteger nonce;
        private BigInteger fee;
        private BigInteger timestamp;
        private String fromAddr20;
        
        private ParsedPayload parsedPayload;
        
        private byte[] memo;
        private int sigScheme;
        private byte[] signature;
        private byte[] encodeForSigning; // The bytes that are signed
    }

    private static BigInteger getUnsignedLongAsBigInteger(ByteBuffer buffer) {
        byte[] bytes = new byte[8];
        buffer.get(bytes);
        // Prepend 0 byte to ensure it is interpreted as positive
        byte[] unsignedBytes = new byte[9];
        System.arraycopy(bytes, 0, unsignedBytes, 1, 8);
        return new BigInteger(1, unsignedBytes);
    }
    
    // Read arbitrary length as unsigned long
    private static BigInteger getUnsignedIntAsBigInteger(ByteBuffer buffer) {
        byte[] bytes = new byte[4];
        buffer.get(bytes);
        return new BigInteger(1, bytes);
    }

    public static ParsedTx parseTx(String rawHex) {
        try {
            byte[] buf = Hex.decodeHex(rawHex);
            ByteBuffer buffer = ByteBuffer.wrap(buf);

            int MIN_HEADER_SIZE = 55;
            if (buf.length < MIN_HEADER_SIZE) {
                return ParsedTx.builder().valid(false).error("Buffer too short: " + buf.length + " bytes (min " + MIN_HEADER_SIZE + ")").build();
            }

            int version = Short.toUnsignedInt(buffer.getShort());
            long chainId = Integer.toUnsignedLong(buffer.getInt());
            int type = Byte.toUnsignedInt(buffer.get());
            BigInteger nonce = getUnsignedLongAsBigInteger(buffer);
            BigInteger fee = getUnsignedLongAsBigInteger(buffer);
            BigInteger timestamp = getUnsignedLongAsBigInteger(buffer);

            byte[] fromAddrBytes = new byte[20];
            buffer.get(fromAddrBytes);
            String fromAddr20 = Hex.encodeHexString(fromAddrBytes);

            if (buffer.remaining() < 4) {
                return ParsedTx.builder().valid(false).error("Buffer truncated before payload length").build();
            }

            long payloadLen = Integer.toUnsignedLong(buffer.getInt());
            if (payloadLen > buffer.remaining()) {
                return ParsedTx.builder().valid(false).error("Payload length " + payloadLen + " exceeds remaining buffer").build();
            }
            if (payloadLen > 10240) {
                return ParsedTx.builder().valid(false).error("Payload too large: " + payloadLen + " bytes (max 10240)").build();
            }

            byte[] payloadBytes = new byte[(int) payloadLen];
            buffer.get(payloadBytes);
            ByteBuffer payloadBf = ByteBuffer.wrap(payloadBytes);

            ParsedPayload payloadObj = ParsedPayload.builder().build();

            if (type == 0) { // TRANSFER
                if (payloadBytes.length < 28) {
                    return ParsedTx.builder().valid(false).error("Transfer payload too short: " + payloadBytes.length + " (need 28)").build();
                }
                byte[] toAddrBits = new byte[20];
                payloadBf.get(toAddrBits);
                payloadObj.setToAddr(Hex.encodeHexString(toAddrBits));
                payloadObj.setAmount(getUnsignedLongAsBigInteger(payloadBf));
            } else if (type == 1) { // ESCROW_CREATE
                if (payloadBytes.length < 142) {
                    return ParsedTx.builder().valid(false).error("EscrowCreate payload too short: " + payloadBytes.length + " (need 142)").build();
                }
                byte[] escrowIdBits = new byte[32]; payloadBf.get(escrowIdBits); payloadObj.setEscrowId(Hex.encodeHexString(escrowIdBits));
                byte[] buyerBits = new byte[20]; payloadBf.get(buyerBits); payloadObj.setBuyer(Hex.encodeHexString(buyerBits));
                byte[] sellerBits = new byte[20]; payloadBf.get(sellerBits); payloadObj.setSeller(Hex.encodeHexString(sellerBits));
                byte[] arbiterBits = new byte[20]; payloadBf.get(arbiterBits); payloadObj.setArbiter(Hex.encodeHexString(arbiterBits));
                payloadObj.setAmount(getUnsignedLongAsBigInteger(payloadBf));
                payloadObj.setExpiryTs(getUnsignedLongAsBigInteger(payloadBf));
                payloadObj.setReleaseMode(Byte.toUnsignedInt(payloadBf.get()));
                payloadObj.setDisputeMode(Byte.toUnsignedInt(payloadBf.get()));
                byte[] metaHashBits = new byte[32]; payloadBf.get(metaHashBits); payloadObj.setMetaHash(Hex.encodeHexString(metaHashBits));
            } else if (type == 2 || type == 3) { // ESCROW_RELEASE / REFUND
                if (payloadBytes.length < 60) {
                    return ParsedTx.builder().valid(false).error("Escrow action payload too short: " + payloadBytes.length + " (need 60)").build();
                }
                byte[] escrowIdBits = new byte[32]; payloadBf.get(escrowIdBits); payloadObj.setEscrowId(Hex.encodeHexString(escrowIdBits));
                byte[] toAddrBits = new byte[20]; payloadBf.get(toAddrBits); payloadObj.setToAddr(Hex.encodeHexString(toAddrBits));
                payloadObj.setAmount(getUnsignedLongAsBigInteger(payloadBf));
            } else {
                return ParsedTx.builder().valid(false).error("Unknown transaction type: " + type).build();
            }

            if (buffer.remaining() < 4) {
                return ParsedTx.builder().valid(false).error("Buffer truncated before memo length").build();
            }

            long memoLen = Integer.toUnsignedLong(buffer.getInt());
            if (memoLen > buffer.remaining()) {
                return ParsedTx.builder().valid(false).error("Memo length " + memoLen + " exceeds remaining buffer").build();
            }
            if (memoLen > 1024) {
                return ParsedTx.builder().valid(false).error("Memo too large: " + memoLen + " bytes (max 1024)").build();
            }
            byte[] memoBytes = new byte[(int) memoLen];
            buffer.get(memoBytes);

            if (buffer.remaining() < 1) {
                return ParsedTx.builder().valid(false).error("Buffer truncated before sigScheme").build();
            }
            int sigScheme = Byte.toUnsignedInt(buffer.get());

            // the bytes signed are exactly everything up to this point
            int offsetSigned = buffer.position();
            byte[] encodeForSigning = Arrays.copyOfRange(buf, 0, offsetSigned);

            if (buffer.remaining() < 4) {
                return ParsedTx.builder().valid(false).error("Buffer truncated before signature length").build();
            }
            
            long sigLen = Integer.toUnsignedLong(buffer.getInt());
            if (sigLen > buffer.remaining()) {
                return ParsedTx.builder().valid(false).error("Signature length " + sigLen + " exceeds remaining buffer").build();
            }
            if (sigLen == 0 || sigLen > 512) {
                return ParsedTx.builder().valid(false).error("Invalid signature length: " + sigLen).build();
            }
            
            byte[] signatureBytes = new byte[(int) sigLen];
            buffer.get(signatureBytes);

            return ParsedTx.builder()
                    .valid(true)
                    .version(version)
                    .chainId(chainId)
                    .type(type)
                    .nonce(nonce)
                    .fee(fee)
                    .timestamp(timestamp)
                    .fromAddr20(fromAddr20)
                    .parsedPayload(payloadObj)
                    .memo(memoBytes)
                    .sigScheme(sigScheme)
                    .signature(signatureBytes)
                    .encodeForSigning(encodeForSigning)
                    .build();

        } catch (Exception e) {
            log.error("Tx Decode Error: {}", e.getMessage());
            return ParsedTx.builder().valid(false).error("Decode error: " + e.getMessage()).build();
        }
    }
}
