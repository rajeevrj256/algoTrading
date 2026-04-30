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
@Table(name = "health_check_log")
public class HealthCheckLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "check_number", nullable = false)
    private int checkNumber;

    @Column(name = "overall_status", nullable = false, length = 10)
    private String overallStatus;

    @Column(name = "ok_count", nullable = false)
    private int okCount;

    @Column(name = "warn_count", nullable = false)
    private int warnCount;

    @Column(name = "fail_count", nullable = false)
    private int failCount;

    @Column(name = "items_json", columnDefinition = "TEXT")
    private String itemsJson;

    @Column(name = "checked_at")
    private LocalDateTime checkedAt;

    @PrePersist
    public void prePersist() {
        if (checkedAt == null) checkedAt = LocalDateTime.now();
    }
}
