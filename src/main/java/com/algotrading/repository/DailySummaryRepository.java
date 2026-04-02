package com.algotrading.repository;

import com.algotrading.entity.DailySummaryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DailySummaryRepository extends JpaRepository<DailySummaryEntity, Long> {
    Optional<DailySummaryEntity> findBySummaryDate(String summaryDate);
}
