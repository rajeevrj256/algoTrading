package com.algotrading.repository;

import com.algotrading.entity.HealthCheckLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HealthCheckLogRepository extends JpaRepository<HealthCheckLogEntity, Long> {
}
