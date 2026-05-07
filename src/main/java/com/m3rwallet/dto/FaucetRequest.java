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
public class FaucetRequest {
    @JsonProperty("addr")
    private String addr;

    @JsonProperty("address")
    private String address;

    @JsonProperty("amount")
    private String amount;
}
