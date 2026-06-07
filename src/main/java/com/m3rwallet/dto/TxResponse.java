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
public class TxResponse {
    @JsonProperty("status")
    private String status; // ACCEPTED, REJECTED, UNKNOWN, PENDING, CONFIRMED

    @JsonProperty("txHash")
    private String txHash;

    @JsonProperty("message")
    private String message;

    @JsonProperty("yesVotes")
    private Integer yesVotes;

    @JsonProperty("totalPeers")
    private Integer totalPeers;

    @JsonProperty("approvedWeight")
    private Double approvedWeight;

    @JsonProperty("totalWeight")
    private Double totalWeight;

    @JsonProperty("weightRatio")
    private Double weightRatio;

    @JsonProperty("consensusType")
    private String consensusType;

    @JsonProperty("validatorAddress")
    private String validatorAddress;
}
