package com.algotrading.repository;

import com.algotrading.entity.BacktestResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BacktestResultRepository extends JpaRepository<BacktestResultEntity, Long> {
    List<BacktestResultEntity> findByRunId(Long runId);
}
