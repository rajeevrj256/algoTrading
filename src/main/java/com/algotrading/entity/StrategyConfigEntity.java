package com.algotrading.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * StrategyConfigEntity — one row per strategy holding its enabled flag.
 * Natural key = the StrategyType enum name (no surrogate id).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "strategy_config")
public class StrategyConfigEntity {

    @Id
    private String strategy;        // StrategyType.name()

    @Builder.Default
    private boolean enabled = true;

    /** Which engine runs this strategy: EQUITY, FNO, or BOTH (Segment enum name). */
    @Builder.Default
    private String segment = "EQUITY";

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    public void touch() {
        updatedAt = LocalDateTime.now();
    }
}
