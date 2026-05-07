package com.m3rwallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.List;

/**
 * Response body for GET /{network}/tx/history?addr=&lt;hex20&gt;
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TxHistoryResponse {

    @JsonProperty("status")
    private String status; // "OK" or "ERROR"

    @JsonProperty("message")
    private String message;

    @JsonProperty("entries")
    private List<LedgerEntryDto> entries;

    // ---- Inner DTO ----

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class LedgerEntryDto {

        @JsonProperty("txHash")
        private String txHash;

        /**
         * SEND, RECEIVE,
         * ESCROW_CREATE, ESCROW_RECEIVE, ESCROW_ARBITER,
         * ESCROW_RELEASE, ESCROW_RELEASE_RECEIVED,
         * ESCROW_REFUND, ESCROW_REFUND_RECEIVED
         */
        @JsonProperty("type")
        private String type;

        /** Amount as decimal string */
        @JsonProperty("amount")
        private String amount;

        /** Fee as decimal string */
        @JsonProperty("fee")
        private String fee;

        @JsonProperty("fromAddr")
        private String fromAddr;

        @JsonProperty("toAddr")
        private String toAddr;

        /** Null for plain transfers */
        @JsonProperty("escrowId")
        private String escrowId;

        @JsonProperty("status")
        private String status;

        /** Unix milliseconds */
        @JsonProperty("createdAt")
        private Long createdAt;
    }
}
