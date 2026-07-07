package com.algotrading.repository;

import com.algotrading.entity.BacktestTradeLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BacktestTradeLogRepository extends JpaRepository<BacktestTradeLogEntity, Long> {
    List<BacktestTradeLogEntity> findByRunId(Long runId);
}
