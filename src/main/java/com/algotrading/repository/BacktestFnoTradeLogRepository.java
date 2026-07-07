package com.algotrading.repository;

import com.algotrading.entity.BacktestFnoTradeLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BacktestFnoTradeLogRepository extends JpaRepository<BacktestFnoTradeLogEntity, Long> {
    List<BacktestFnoTradeLogEntity> findByRunId(Long runId);
}
