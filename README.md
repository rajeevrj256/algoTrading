# Algo Trading Monolith
### NSE Paper Trading System — Spring Boot + Java 8 (OOP / Interface-Impl Pattern)

A clean, single-JAR monolithic Spring Boot application that implements every concern
(data feed, strategy engine, risk management, broker execution, Google Sheets logging,
Telegram alerts, health monitoring) as a proper **interface + implementation** pair.

---

## OOP Structure

```
src/main/java/com/algotrading/
│
├── AlgoTradingApplication.java          ← Main entry point (@SpringBootApplication)
│
├── enums/
│   ├── SignalType.java                  ← BUY / SELL
│   ├── StrategyType.java                ← ORB / ORB_RETEST / VWAP_MR / EMA_CROSS / SUPERTREND / GAP_GO
│   ├── PositionStatus.java              ← OPEN / CLOSED
│   └── AlertType.java                  ← TRADE_OPENED / CIRCUIT_BREAKER / ...
│
├── dto/                                 ← Pure data transfer objects (no logic)
│   ├── CandleDTO.java
│   ├── TradeSignalDTO.java
│   ├── PositionDTO.java
│   ├── RiskValidationDTO.java
│   ├── DailySummaryDTO.java
│   ├── AlertDTO.java
│   ├── HealthReportDTO.java
│   └── ApiResponse.java                ← Generic REST wrapper ApiResponse<T>
│
├── model/                               ← Mutable domain objects (internal state)
│   ├── DailyStats.java                 ← Thread-safe daily P&L state (AtomicReference)
│   └── Order.java                      ← Paper order record
│
├── service/                             ← INTERFACES (contracts only, no logic)
│   ├── DataFeedService.java
│   ├── TradingStrategy.java            ← Interface for all 6 strategies
│   ├── StrategyService.java
│   ├── RiskService.java
│   ├── BrokerService.java
│   ├── SheetsService.java
│   ├── NotificationService.java
│   ├── HealthService.java
│   └── ScanOrchestrationService.java
│
├── service/impl/                        ← IMPLEMENTATIONS (one per interface)
│   ├── YahooFinanceDataFeedServiceImpl.java  ← DataFeedService
│   ├── OrbStrategy.java                      ← TradingStrategy (ORB)
│   ├── OrbRetestStrategy.java                ← TradingStrategy (ORB_RETEST)
│   ├── VwapMeanReversionStrategy.java        ← TradingStrategy (VWAP_MR)
│   ├── EmaCrossStrategy.java                 ← TradingStrategy (EMA_CROSS)
│   ├── SupertrendStrategy.java               ← TradingStrategy (SUPERTREND)
│   ├── GapAndGoStrategy.java                 ← TradingStrategy (GAP_GO)
│   ├── StrategyServiceImpl.java              ← StrategyService
│   ├── RiskServiceImpl.java                  ← RiskService
│   ├── PaperBrokerServiceImpl.java           ← BrokerService
│   ├── GoogleSheetsServiceImpl.java          ← SheetsService
│   ├── TelegramNotificationServiceImpl.java  ← NotificationService
│   ├── InternalHealthServiceImpl.java        ← HealthService
│   └── ScanOrchestrationServiceImpl.java     ← ScanOrchestrationService
│
├── controller/                          ← REST endpoints (one per domain)
│   ├── DataFeedController.java          ← /api/feed/**
│   ├── StrategyController.java          ← /api/strategy/**
│   ├── RiskController.java              ← /api/risk/**
│   ├── BrokerController.java            ← /api/broker/**
│   ├── SheetsController.java            ← /api/sheets/**
│   ├── NotificationController.java      ← /api/notify/**
│   ├── HealthController.java            ← /api/health/**
│   └── ScanEngineController.java        ← /api/engine/**
│
├── scheduler/
│   └── TradingScheduler.java            ← @Scheduled scan (5 min) + EOD (15:35)
│
├── config/
│   └── AppConfig.java                   ← RestTemplate + ObjectMapper beans
│
└── util/
    └── Indicator.java                   ← Pure-static TA: EMA, RSI, ATR, VWAP,
                                            Rolling StdDev, MACD, Supertrend
```

---

## Quick Start

### 1. Build
```bash
cd algo-trading-monolith
mvn clean package -DskipTests
```

### 2. Run
```bash
java -jar target/algo-trading-monolith-1.0.0.jar
```

Or with Maven:
```bash
mvn spring-boot:run
```

The app starts on **http://localhost:8080**

---

## Configuration

Edit `src/main/resources/application.yml`:

### Risk parameters
```yaml
risk:
  capital: 100000          # ₹1,00,000
  daily-target: 1000       # Stop at ₹1,000 profit
  daily-max-loss: 500      # Circuit breaker at ₹500 loss
  max-trades-per-day: 100
  min-confidence: 0.60
  min-rr-ratio: 1.5
```

### Dynamic symbol source
```yaml
app:
  symbols: RELIANCE,TCS,INFY

symbol-source:
  cache-ttl-minutes: 30
  refresh-interval-ms: 1800000
  fallback-enabled: true
```

`app.symbols` is now only a fallback list. The trading engine prefers rows from the
`intraday_symbol` table, so another microservice can refresh the shortlist every 30 minutes.

### Trading charges
```yaml
charges:
  intraday: true
  brokerage-rate: 0.0003
  brokerage-cap-per-order: 20
  stt-intraday-sell-rate: 0.00025
  exchange-rate: 0.0000325
  gst-rate: 0.18
  sebi-rate: 0.000001
  stamp-intraday-buy-rate: 0.00003
```

### Google Sheets (optional)
```yaml
sheets:
  sheet-id: YOUR_SHEET_ID_HERE
  service-account-file: /path/to/service_account.json
```

### Telegram Alerts (optional)
```yaml
notification:
  telegram:
    bot-token: "123456:ABC..."
    chat-id:   "-1001234567890"
```

---

## REST API

### Data Feed
```
GET  /api/feed/candles/RELIANCE?count=120
GET  /api/feed/price/RELIANCE
DELETE /api/feed/cache/RELIANCE
GET  /api/feed/status
```

### Strategy
```
POST /api/strategy/scan/RELIANCE          body: [CandleDTO...]
POST /api/strategy/scan/RELIANCE/ORB      body: [CandleDTO...]
GET  /api/strategy/list
```

### Risk
```
GET  /api/risk/can-trade
POST /api/risk/validate                   body: TradeSignalDTO
POST /api/risk/record?pnl=250.00
GET  /api/risk/summary
GET  /api/risk/circuit-breaker
POST /api/risk/reset
```

### Broker
```
POST /api/broker/open                     body: TradeSignalDTO
POST /api/broker/close/{positionId}?exitPrice=2540&reason=MANUAL
POST /api/broker/check-exits/RELIANCE?price=2540
POST /api/broker/square-off?price=2540
GET  /api/broker/positions/open
GET  /api/broker/positions/closed
GET  /api/broker/orders
```

### Engine (manual trigger)
```
POST /api/engine/scan
POST /api/engine/eod
GET  /api/engine/status
```

### Health
```
GET  /api/health/check
GET  /api/health/last
```

### Sheets
```
GET  /api/sheets/status
```

### Notifications
```
GET  /api/notify/status
```

---

## Strategies

| Strategy     | Signal Condition                                         | Min Candles |
|--------------|----------------------------------------------------------|-------------|
| `ORB`        | Breakout of 09:15–09:30 range + 1.5× volume             | 5           |
| `VWAP_MR`    | Price crosses ±2σ VWAP band + RSI extreme                | 30          |
| `EMA_CROSS`  | EMA9/EMA21 golden/death cross + MACD histogram confirm   | 35          |
| `SUPERTREND` | Direction flip bullish↔bearish                           | 20          |
| `GAP_GO`     | Morning gap >0.5% with 2× volume (09:15–09:45 only)     | 25          |

---

## Disclaimer
Paper trading / educational use only. Not financial advice.
Always backtest thoroughly before any live trading consideration.
