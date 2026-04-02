package com.algotrading.model;

import com.algotrading.enums.SignalType;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Order {
    private String     orderId;
    private String     positionId;
    private String     symbol;
    private SignalType side;
    private int        quantity;
    private double     requestedPrice;
    private double     filledPrice;
    private String     status;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime filledAt;

    private String notes;
}
