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
public class ArbiterResponse {
    @JsonProperty("status")
    private String status;

    @JsonProperty("arbiterAddress")
    private String arbiterAddress;

    @JsonProperty("message")
    private String message;
}
