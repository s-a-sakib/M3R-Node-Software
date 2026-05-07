package com.m3rwallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountInfoResponse {
    @JsonProperty("status")
    private String status; // OK, ERROR

    @JsonProperty("balance")
    private java.math.BigInteger balance;

    @JsonProperty("nonce")
    private Long nonce;

    @JsonProperty("message")
    private String message;
}
