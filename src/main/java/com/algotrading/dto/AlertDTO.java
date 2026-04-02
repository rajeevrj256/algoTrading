package com.algotrading.dto;

import com.algotrading.enums.AlertType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AlertDTO {
    private AlertType type;
    private String    title;
    private String    message;
    private String    symbol;
    private double    pnl;
    private boolean   urgent;
}
