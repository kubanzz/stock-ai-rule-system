package com.jx.tracker.domain.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.Map;

@Data
public class SignalGenerateRequestDto {

    private String symbol;

    private LocalDate signalDate;

    private Map<String, Object> factors;
}
