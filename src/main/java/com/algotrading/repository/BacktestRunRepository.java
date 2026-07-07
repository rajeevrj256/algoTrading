package com.algotrading.repository;

import com.algotrading.entity.BacktestRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BacktestRunRepository extends JpaRepository<BacktestRunEntity, Long> {
}
