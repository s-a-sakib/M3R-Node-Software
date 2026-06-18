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
public class TxSubmitRequest {
    @JsonProperty("rawTxHex")
    private String rawTxHex;

    @JsonProperty("pubKeyCompressedHex")
    private String pubKeyCompressedHex;

    @JsonProperty("broadcasterAddress")
    private String broadcasterAddress;
}
