package com.algotrading.repository;

import com.algotrading.entity.StrategyConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StrategyConfigRepository extends JpaRepository<StrategyConfigEntity, String> {
}
