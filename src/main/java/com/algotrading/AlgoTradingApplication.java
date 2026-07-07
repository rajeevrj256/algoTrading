package com.algotrading;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AlgoTradingApplication — monolithic Spring Boot entry point.
 *
 * All services (DataFeed, Strategy, Risk, Broker, Sheets, Notification, Health)
 * are wired together inside this single application via interfaces and their
 * implementations, following strict OOP and SOLID principles.
 *
 * Package layout:
 *   enums/       — SignalType, StrategyType, PositionStatus, AlertType
 *   dto/         — CandleDTO, TradeSignalDTO, PositionDTO, DailySummaryDTO, ...
 *   model/       — DailyStats, Order (internal mutable domain objects)
 *   service/     — all service interfaces (contracts)
 *   service/impl — all service implementations (one impl per interface)
 *   controller/  — REST controllers (one per domain)
 *   scheduler/   — @Scheduled scan loop and EOD jobcre
 *
 *   config/      — Spring bean configuration
 *   util/        — Indicator (pure-static TA calculations)
 */
@SpringBootApplication
@EnableScheduling
public class AlgoTradingApplication {

    public static void main(String[] args) {
        SpringApplication.run(AlgoTradingApplication.class, args);
    }
}
