package com.algotrading.repository;

import com.algotrading.entity.TradeLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TradeLogRepository extends JpaRepository<TradeLogEntity, Long> {
}
