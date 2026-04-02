package com.algotrading.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "open_position")
public class OpenPositionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private String side;

    private String strategy;

    @Column(name = "entry_price", nullable = false)
    private double entryPrice;

    @Column(name = "stop_loss")
    private double stopLoss;

    private double target;
    private int quantity;

    @Column(name = "entry_time")
    private LocalDateTime entryTime;

    @Column(name = "signal_reason")
    private String signalReason;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
