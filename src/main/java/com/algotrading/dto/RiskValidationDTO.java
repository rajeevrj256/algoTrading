package com.algotrading.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class RiskValidationDTO {
    private boolean approved;
    private String  reason;
    private double  riskAmount;
    private int     adjustedQuantity;
}
