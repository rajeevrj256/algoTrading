package com.algotrading.repository;

import com.algotrading.entity.OpenPositionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OpenPositionRepository extends JpaRepository<OpenPositionEntity, Long> {
}
