package com.algotrading.repository;

import com.algotrading.entity.IntradaySymbolEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface IntradaySymbolRepository extends JpaRepository<IntradaySymbolEntity, Long> {

    @Query("select s from IntradaySymbolEntity s " +
            "where s.active = true and (s.validUntil is null or s.validUntil >= ?1) " +
            "order by s.score desc, s.symbol asc")
    List<IntradaySymbolEntity> findEligibleSymbols(LocalDateTime now);
}
