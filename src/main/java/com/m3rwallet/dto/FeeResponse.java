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
public class FeeResponse {
    @JsonProperty("broadcastFee")
    private Long broadcastFee;

    @JsonProperty("percentFeeBps")
    private Integer percentFeeBps;
}
