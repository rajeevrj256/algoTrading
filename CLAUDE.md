# CLAUDE.md

Guidance for Claude Code (and any agent) working in this repo. Read this first instead of scanning the whole tree.

## What this is

**NSE intraday paper-trading system.** Single-JAR Spring Boot monolith. Scans a watchlist of symbols every 5 min during market hours (IST), runs technical strategies (8 registered; which ones are live is controlled by the `strategy_config` table — trend-aligned ones enabled by default), validates against risk rules, opens/closes simulated ("paper") positions through a fake broker with a breakeven+trailing stop, persists everything to PostgreSQL, captures live candles for backtesting, and emits non-critical work (trade logging, candle ingest, Telegram alerts) over Kafka with a synchronous fallback.

**Educational / paper only. No real orders are ever placed.** (`PaperBrokerServiceImpl`).

## Stack

- **Java 8** (`1.8` source/target — do NOT use Java 9+ APIs: no `var`, no `List.of`, no records, no switch expressions).
- **Spring Boot 2.7.18** — Web, Actuator, Scheduling, Data JPA, Kafka.
- **PostgreSQL** + **Flyway** migrations + Hibernate (`ddl-auto: validate` — schema comes from Flyway, NOT Hibernate).
- **Kafka** (optional, gated by `app.kafka.enabled`).
- **Lombok** (`@RequiredArgsConstructor`, `@Slf4j`, `@Data`, `@Builder` everywhere).
- Maven build. Group `com.algotrading`, artifact `algo-trading-monolith`, v1.0.0.

## Architecture pattern

Strict **interface + impl** split. Every service is an interface in `service/` with one implementation in `service/impl/`. Constructor injection via Lombok `@RequiredArgsConstructor` (no `@Autowired` fields). Match this when adding code.

Layering: `controller` → `service` (interface) → `service/impl` → `repository` (Spring Data JPA) → `entity`. DTOs cross the wire; `entity` is JPA-only; `model` is internal mutable state.

## Core flow (the important path)

`TradingScheduler` (cron/fixed-rate) → `ScanOrchestrationServiceImpl` (the brain) which sequences:

```
CRITICAL (sync):
  DataFeedService    fetch candles      → service/impl/GrowFinanceDataFeedServiceImpl (default) or YahooFinanceDataFeedServiceImpl
  StrategyService    generate signal    → service/impl/StrategyServiceImpl (runs only strategies enabled in strategy_config; first signal wins)
  RiskService        validate + gate    → service/impl/RiskServiceImpl (min-confidence 0.65, min-rr 1.5)
  BrokerService      open/close paper    → service/impl/PaperBrokerServiceImpl (breakeven + trailing stop)
NON-CRITICAL (async via Kafka, falls back to direct call when kafka disabled):
  via TradingEventPublisher →
    SheetsService          trade log / open-positions → service/impl/PostgresSheetsServiceImpl
    NotificationService    Telegram alerts            → service/impl/TelegramNotificationServiceImpl
```

Symbols to scan come from the `intraday_symbol` DB table (`IntradaySymbolService`), refreshed every 30 min; `app.symbols` in yaml is only a fallback list.

## Scheduler timings (IST, `Asia/Kolkata`)

| Job | When | Method |
|-----|------|--------|
| Exit checks (SL/target on open positions) | every 15s, market hours | `scheduledExitCheck` |
| Market scan | every 5 min, market hours (09:15–15:25) | `scheduledScan` |
| EOD square-off | 15:26 Mon–Fri | `scheduledEod` |
| Auto shutdown | 15:27 Mon–Fri (if enabled) | `scheduledShutdown` |
| Symbol shortlist refresh | every 30 min | `IntradaySymbolService` |
| Health check | every 15 min | `HealthService` |

File: `src/main/java/com/algotrading/scheduler/TradingScheduler.java`

## Strategies (`service/impl/`, all implement `service/TradingStrategy`)

Which strategies actually trade is set by the `strategy_config` table, NOT the code. Each strategy is always a registered bean; `StrategyServiceImpl` only runs the enabled ones. Toggle at runtime via the API (below) — no restart.

**Legacy strategies — all DISABLED by default (V9).** Kept for reference/backtesting; had negative net expectancy (charges > edge) or no trend filter. VWAP_MR especially: live 0 wins / 14, avgR −0.895.

| StrategyType | File | Condition | Min candles |
|--------------|------|-----------|-------------|
| `ORB`        | `OrbStrategy.java`              | break of 09:15–09:30 range + 1.5× vol | 5 |
| `VWAP_MR`    | `VwapMeanReversionStrategy.java`| ±2σ VWAP band cross + RSI extreme | 30 |
| `EMA_CROSS`  | `EmaCrossStrategy.java`         | EMA9/EMA21 cross + MACD histogram | 35 |
| `SUPERTREND` | `SupertrendStrategy.java`       | direction flip | 20 |
| `GAP_GO`     | `GapAndGoStrategy.java`         | gap >0.5% + 2× vol, 09:15–09:45 | 25 |

**Trend-aligned strategies — ENABLED by default (V9).** Built to beat charges: trend filter + ≥0.4% target + risk-based 2–2.5R + trailing stop.

| StrategyType | File | Setup |
|---|---|---|
| `VWAP_TREND`   | `VwapTrendStrategy.java`   | pullback to rising VWAP, ride trend, 2.5R |
| `EMA_PULLBACK` | `EmaPullbackStrategy.java` | stacked EMA9/21/50, buy pullback to EMA21, 2.5R |
| `ORB_REFINED`  | `OrbRefinedStrategy.java`  | opening-range breakout in gap/bias direction only, 2R, 09:30–10:30 |

Indicator math (pure static, no state): `src/main/java/com/algotrading/util/Indicator.java` — EMA, RSI, ATR, VWAP, rolling stddev, MACD, Supertrend.

**Backtest runner** — `BacktestService`/`BacktestServiceImpl` replays stored candles through strategies (no look-ahead; simulates stop/target via candle high/low, breakeven+trailing, EOD square-off, slippage both legs, real charges). Endpoint: `POST /api/backtest/run?symbols=RELIANCE,TCS&strategy=VWAP_TREND&count=500`. Validate a strategy without a live trading window.

## Important file map

**Entry / config**
- `src/main/java/com/algotrading/AlgoTradingApplication.java` — `@SpringBootApplication` main
- `src/main/java/com/algotrading/config/AppConfig.java` — `RestTemplate`, `ObjectMapper` beans
- `src/main/java/com/algotrading/config/KafkaConfig.java` — Kafka producer/consumer, gated by `app.kafka.enabled=true`
- `src/main/resources/application.yml` — **all tunables** (risk, charges, datafeed provider, kafka, telegram, db)

**Orchestration / events**
- `src/main/java/com/algotrading/service/impl/ScanOrchestrationServiceImpl.java` — main trading loop (start here for behavior changes)
- `src/main/java/com/algotrading/scheduler/TradingScheduler.java` — triggers
- `src/main/java/com/algotrading/event/TradingEventPublisher.java` — Kafka publish + sync fallback
- `src/main/java/com/algotrading/event/TradeReportingConsumer.java` — consumes `algotrading.trade-reporting`
- `src/main/java/com/algotrading/event/TradeNotificationConsumer.java` — consumes `algotrading.trade-notifications`
- `src/main/java/com/algotrading/event/CandleIngestConsumer.java` — consumes `algotrading.candle-ingest` → persists live candles to `candle_history` (via `CandleHistoryService`). Published from `GrowFinanceDataFeedServiceImpl` on each fresh fetch; deduped on (symbol, ts). Builds backtest depth beyond the feed's 5-day window. Falls back to direct DB save when Kafka off.

**Services (interface in `service/`, impl in `service/impl/`)**
- DataFeed → `GrowFinanceDataFeedServiceImpl` (Groww API, default) / `YahooFinanceDataFeedServiceImpl`
- Strategy → `StrategyServiceImpl` (enabled-set gating + runtime toggle)
- Risk → `RiskServiceImpl` (+ per-strategy expectancy report)
- Broker → `PaperBrokerServiceImpl` (slippage + breakeven/trailing stop)
- Sheets (trade log) → `PostgresSheetsServiceImpl`
- Notification → `TelegramNotificationServiceImpl`
- Health → `InternalHealthServiceImpl`
- IntradaySymbol → `IntradaySymbolServiceImpl`
- Charges → `ChargesServiceImpl` (India intraday brokerage/STT/GST math)
- CandleHistory → `CandleHistoryServiceImpl` (upsert live candles, serve to backtest)
- Backtest → `BacktestServiceImpl` (replay strategies over stored/live candles)

**Persistence**
- Entities: `src/main/java/com/algotrading/entity/` — `TradeLogEntity`, `OpenPositionEntity`, `DailySummaryEntity`, `IntradaySymbolEntity`, `HealthCheckLogEntity`, `StrategyConfigEntity` (per-strategy enabled flag), `CandleHistoryEntity` (stored 5-min OHLCV, unique on symbol+ts)
- Repos: `src/main/java/com/algotrading/repository/` (Spring Data JPA). `CandleHistoryRepository` has a native `ON CONFLICT` upsert.
- Migrations: `src/main/resources/db/migration/V1..V10__*.sql` — **schema changes go here as a new `V11__*.sql`, never edit existing migrations** (Flyway validates checksums). Key ones: V8 `strategy_config`, V9 seed trend strategies (disable legacy), V10 `candle_history`. On Neon, Flyway runs over the DIRECT (non-pooler) endpoint — see `spring.flyway.url` — because session advisory locks break on PgBouncer.

**Strategy enable/disable** — `strategy_config` table is the source of truth for which strategies run the live scan. Loaded once into memory at startup, toggled at runtime via API (no restart). `StrategyServiceImpl` holds a `volatile Set<StrategyType>`. The `strategy.enabled` yaml CSV is only a fallback when the table is empty.

**REST controllers** (`src/main/java/com/algotrading/controller/`)
- `/api/feed/**` DataFeed · `/api/strategy/**` · `/api/risk/**` · `/api/broker/**` · `/api/sheets/**` · `/api/notify/**` · `/api/health/**` · `/api/engine/**` (manual scan/eod) · `IntradaySymbolController`
- **Strategy runtime toggle**: `GET /api/strategy/config`, `POST /api/strategy/config/{type}/enable|disable`, `POST /api/strategy/config/refresh`
- **Per-strategy expectancy** (kill negative-edge strategies): `GET /api/risk/expectancy?days=N`
- **Backtest**: `POST /api/backtest/run?symbols=RELIANCE,TCS&strategy=VWAP_TREND&count=500` (`strategy` optional = all)

**Tests** (`src/test/java/com/algotrading/service/impl/`)
- `ChargesServiceImplTest`, `IntradaySymbolServiceImplTest`, `PaperBrokerServiceImplTest`

## Build & run

```bash
mvn clean package -DskipTests         # build jar
java -jar target/algo-trading-monolith-1.0.0.jar
mvn spring-boot:run                   # or run directly
mvn test                              # run tests
mvn test -Dtest=ChargesServiceImplTest  # single test
```

App: **http://localhost:8080**. Requires PostgreSQL (set `spring.datasource.url/username` in `application.yml`); create DB `algo_trading`, Flyway builds tables on startup. Kafka only needed if `app.kafka.enabled=true`. Telegram/Groww creds are optional and blank by default.

## Conventions / gotchas

- **Java 8 only.** No 9+ syntax or APIs.
- **New migration, never edit old ones.** Next = `V11__*.sql`.
- New service ⇒ add interface in `service/` + impl in `service/impl/`. Inject via constructor (Lombok), not field `@Autowired`.
- New strategy ⇒ implement `TradingStrategy` as a `@Component`, add a `StrategyType` enum value, seed a `strategy_config` row in a new migration. It's auto-injected into `StrategyServiceImpl`.
- All times are IST (`Asia/Kolkata`). Market 09:15–15:25.
- Non-critical work (logging/alerts/candle ingest) goes through `TradingEventPublisher`, not direct service calls, so it works both with and without Kafka.
- `risk:` / `charges:` / `broker.trailing:` blocks in `application.yml` are the tuning knobs — change behavior there before touching code. Strategy on/off lives in the DB (`strategy_config`), not yaml.
- **Improving profitability workflow** (this is the point of the recent changes): run live or `POST /api/backtest/run` → read `GET /api/risk/expectancy` / backtest `avgR`+`avgPnl` → disable negative-expectancy strategies via the toggle API. Edge must beat ~0.1% round-trip charges; that's why strategies enforce a ≥0.4% min target + trend filter, and the broker trails winners.
- README.md is partly stale. Trust this file over README.

## Disclaimer

Paper trading / educational only. Not financial advice.
