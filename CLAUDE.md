# CLAUDE.md

Guidance for Claude Code (and any agent) working in this repo. Read this first instead of scanning the whole tree.

## What this is

**NSE intraday F&O + equity paper-trading system.** Single-JAR Spring Boot monolith. Scans a watchlist of symbols every 5 min during market hours (IST), runs technical strategies (4 registered; which ones are live is controlled by the `strategy_config` table — all enabled by default), validates against risk rules, opens/closes simulated ("paper") positions through a fake broker with a breakeven+trailing stop, persists everything to PostgreSQL, captures live candles for backtesting, and emits non-critical work (trade logging, candle ingest, Telegram alerts) over Kafka with a synchronous fallback.

**F&O mode (`fno.enabled`, ON by default):** signals on configured indices (NIFTY/BANKNIFTY/FINNIFTY/MIDCPNIFTY) are converted into LONG index-option paper orders on the option chain — BUY index → buy CE, SELL index → buy PE, at the ATM strike of the nearest expiry, sized in lots. Exit decisions run on the underlying index levels (faithful to the strategy); fills use the option premium LTP (Groww FNO segment) with a Black-Scholes synthetic fallback; a premium hard-stop guards theta/IV bleed. Equity (non-index) symbols still trade cash intraday.

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
  DataFeedService     fetch candles       → service/impl/YahooFinanceDataFeedServiceImpl (default; free) or GrowFinanceDataFeedServiceImpl
                                            (Groww market data needs their PAID plan — free-plan keys 403 on all data endpoints)
  StrategyService     generate signal     → service/impl/StrategyServiceImpl (runs ALL enabled strategies; confluence-aware:
                                            opposite-direction signals cancel (chop), 2+ agreeing = confidence bonus)
  OptionChainService  index → option leg  → service/impl/GrowwOptionChainServiceImpl (only when fno.enabled and the symbol is a
                                            configured index: resolves expiry+ATM strike, premium LTP, delta-mapped SL/target, lots)
  RiskService         validate + gate     → service/impl/RiskServiceImpl (min-confidence 0.70, min-rr 2.1; option risk cap 2.5%)
  BrokerService       open/close paper    → service/impl/PaperBrokerServiceImpl (breakeven + trailing stop; option exits decided on
                                            the UNDERLYING index price, filled at option LTP — checkOptionExit)
NON-CRITICAL (async via Kafka, falls back to direct call when kafka disabled):
  via TradingEventPublisher →
    SheetsService          trade log / open-positions → service/impl/PostgresSheetsServiceImpl
    NotificationService    Telegram alerts            → service/impl/TelegramNotificationServiceImpl
```

After any stop-loss exit a per-symbol cooldown (`risk.stop-cooldown-min`, default 20) blocks immediate re-entry; option positions key the cooldown on their underlying.

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

**Legacy strategies were DELETED (V11)** — ORB, ORB_RETEST, VWAP_MR, EMA_CROSS, SUPERTREND, GAP_GO had negative net expectancy (charges > edge; VWAP_MR: live 0 wins / 14, avgR −0.895). Their `strategy_config` rows are removed by V11; historic `trade_log` rows with those names are tolerated (parsers null the enum).

**Live strategies — ALL ENABLED by default.** Built to beat charges: trend filter + min-move target + risk-based 2–2.5R + trailing stop. Volume confirms are NEUTRAL on indices (NSE index candles report zero volume — a hard volume gate would block every index signal).

| StrategyType | File | Setup |
|---|---|---|
| `VWAP_TREND`   | `VwapTrendStrategy.java`   | pullback to rising VWAP, ride trend, 2.5R, RSI exhaustion guard |
| `EMA_PULLBACK` | `EmaPullbackStrategy.java` | stacked EMA9/21/50, buy pullback to EMA21, 2.5R, RSI guard |
| `ORB_REFINED`  | `OrbRefinedStrategy.java`  | opening-range breakout in gap/bias direction only, 2R, 09:30–10:30 |
| `INDEX_TREND`  | `IndexTrendStrategy.java`  | volume-free index momentum: ADX(14)≥20 regime + Supertrend + EMA + VWAP alignment + strong-body trigger candle, 2.2R, stop ≤1.5×ATR |

**Confluence runner** (`StrategyServiceImpl.runStrategies`): all enabled strategies run; opposite-direction signals cancel (that disagreement = chop, stay flat); 2+ agreeing = highest-confidence signal +0.05/extra vote (cap 0.95).

Indicator math (pure static, no state): `src/main/java/com/algotrading/util/Indicator.java` — EMA, RSI, ATR, VWAP, rolling stddev, MACD, Supertrend, ADX.

**Backtest runner** — `BacktestService`/`BacktestServiceImpl` replays stored candles through strategies (no look-ahead; simulates stop/target via candle high/low, breakeven+trailing, EOD square-off, slippage both legs, real charges). Endpoint: `POST /api/backtest/run?symbols=RELIANCE,TCS&strategy=VWAP_TREND&count=500`. Validate a strategy without a live trading window.

## Important file map

**Entry / config**
- `src/main/java/com/algotrading/AlgoTradingApplication.java` — `@SpringBootApplication` main
- `src/main/java/com/algotrading/config/AppConfig.java` — `RestTemplate`, `ObjectMapper` beans
- `src/main/java/com/algotrading/config/KafkaConfig.java` — Kafka producer/consumer, gated by `app.kafka.enabled=true`
- `src/main/java/com/algotrading/config/FnoProperties.java` — `fno:` yaml binding (lot sizes, strike steps, expiry days, risk caps). Exchange revisions (SEBI/NSE circulars) are CONFIG changes, not code.
- `src/main/resources/application.yml` — **all tunables** (risk, fno, charges incl. `charges.fno`, datafeed provider, kafka, telegram, db)

**Orchestration / events**
- `src/main/java/com/algotrading/service/impl/ScanOrchestrationServiceImpl.java` — main trading loop (start here for behavior changes)
- `src/main/java/com/algotrading/scheduler/TradingScheduler.java` — triggers
- `src/main/java/com/algotrading/event/TradingEventPublisher.java` — Kafka publish + sync fallback
- `src/main/java/com/algotrading/event/TradeReportingConsumer.java` — consumes `algotrading.trade-reporting`
- `src/main/java/com/algotrading/event/TradeNotificationConsumer.java` — consumes `algotrading.trade-notifications`
- `src/main/java/com/algotrading/event/CandleIngestConsumer.java` — consumes `algotrading.candle-ingest` → persists live candles to `candle_history` (via `CandleHistoryService`). Published from `GrowFinanceDataFeedServiceImpl` on each fresh fetch; deduped on (symbol, ts). Builds backtest depth beyond the feed's 5-day window. Falls back to direct DB save when Kafka off.

**Services (interface in `service/`, impl in `service/impl/`)**
- DataFeed → `YahooFinanceDataFeedServiceImpl` (default, free; browser UA required — Yahoo 429s Java's default; indices map to ^NSEI/^NSEBANK/...) / `GrowFinanceDataFeedServiceImpl` (needs Groww's paid data plan; free-plan tokens lack the market-data scope → 403)
- GrowwAuth → `GrowwAuthServiceImpl` (daily token exchange + throttled authed GETs; shared by the feed AND the option chain)
- OptionChain → `GrowwOptionChainServiceImpl` (index signal → option leg: expiry/ATM strike resolution, NSE trading-symbol construction, Groww FNO LTP with Black-Scholes synthetic fallback, delta-mapped premium SL/target, lot sizing)
- Strategy → `StrategyServiceImpl` (enabled-set gating + runtime toggle + confluence)
- Risk → `RiskServiceImpl` (+ per-strategy expectancy report; option positions get a 2.5% risk cap vs 1% equity)
- Broker → `PaperBrokerServiceImpl` (slippage + breakeven/trailing stop; `checkOptionExit` = underlying-frame decisions, premium fills, premium hard-stop)
- Sheets (trade log) → `PostgresSheetsServiceImpl`
- Notification → `TelegramNotificationServiceImpl`
- Health → `InternalHealthServiceImpl`
- IntradaySymbol → `IntradaySymbolServiceImpl`
- Charges → `ChargesServiceImpl` (India intraday equity AND index-option brokerage/STT/GST math — see `charges.fno`)
- CandleHistory → `CandleHistoryServiceImpl` (upsert live candles, serve to backtest)
- Backtest → `BacktestServiceImpl` (replay strategies over stored/live candles — validates the UNDERLYING edge; option conversion applies to the live paper flow only)

**F&O utils/models**
- `util/OptionPricing.java` — Black-Scholes premium + delta (synthetic fallback and SL/target mapping)
- `util/Symbols.java` — canonical index aliases (NIFTY_50 → NIFTY), shared feed/chain
- `model/OptionContract.java`, `enums/InstrumentType.java`, `enums/OptionType.java`

**Persistence**
- Entities: `src/main/java/com/algotrading/entity/` — `TradeLogEntity`, `OpenPositionEntity`, `DailySummaryEntity`, `IntradaySymbolEntity`, `HealthCheckLogEntity`, `StrategyConfigEntity` (per-strategy enabled flag), `CandleHistoryEntity` (stored 5-min OHLCV, unique on symbol+ts)
- Repos: `src/main/java/com/algotrading/repository/` (Spring Data JPA). `CandleHistoryRepository` has a native `ON CONFLICT` upsert.
- Migrations: `src/main/resources/db/migration/V1..V11__*.sql` — **schema changes go here as a new `V12__*.sql`, never edit existing migrations** (Flyway validates checksums). Key ones: V8 `strategy_config`, V9 seed trend strategies, V10 `candle_history`, V11 F&O columns (option contract fields + underlying-frame levels + trail state on `open_position`/`trade_log`, deletes legacy strategy rows, seeds INDEX_TREND). On Neon, Flyway runs over the DIRECT (non-pooler) endpoint — see `spring.flyway.url` — because session advisory locks break on PgBouncer.

**Strategy enable/disable** — `strategy_config` table is the source of truth for which strategies run the live scan. Loaded once into memory at startup, toggled at runtime via API (no restart). `StrategyServiceImpl` holds a `volatile Set<StrategyType>`. The `strategy.enabled` yaml CSV is only a fallback when the table is empty.

**REST controllers** (`src/main/java/com/algotrading/controller/`)
- `/api/feed/**` DataFeed · `/api/strategy/**` · `/api/risk/**` · `/api/broker/**` · `/api/sheets/**` · `/api/notify/**` · `/api/health/**` · `/api/engine/**` (manual scan/eod) · `IntradaySymbolController`
- **Strategy runtime toggle**: `GET /api/strategy/config`, `POST /api/strategy/config/{type}/enable|disable`, `POST /api/strategy/config/refresh`
- **Per-strategy expectancy** (kill negative-edge strategies): `GET /api/risk/expectancy?days=N`
- **Backtest**: `POST /api/backtest/run?symbols=RELIANCE,TCS&strategy=VWAP_TREND&count=500` (`strategy` optional = all)

**Tests** (`src/test/java/com/algotrading/service/impl/`)
- `ChargesServiceImplTest` (equity + option charge models), `IntradaySymbolServiceImplTest`, `PaperBrokerServiceImplTest`, `GrowwOptionChainServiceImplTest` (CE/PE conversion, ATM strike, lot sizing)

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
- **New migration, never edit old ones.** Next = `V12__*.sql`.
- **Index candles have ZERO volume** — never hard-gate an index strategy on volume; make the confirm neutral when `rollingAvgVolume <= 0` (see VwapTrendStrategy/OrbRefinedStrategy).
- **F&O tunables live in yaml** (`fno:` block) — lot sizes, strike steps, expiry days, risk caps. NSE revises these by circular; update config, not code. Option orders are always LONG (buy CE/PE) — no writing/margin model.
- New service ⇒ add interface in `service/` + impl in `service/impl/`. Inject via constructor (Lombok), not field `@Autowired`.
- New strategy ⇒ implement `TradingStrategy` as a `@Component`, add a `StrategyType` enum value, seed a `strategy_config` row in a new migration. It's auto-injected into `StrategyServiceImpl`.
- All times are IST (`Asia/Kolkata`). Market 09:15–15:25.
- Non-critical work (logging/alerts/candle ingest) goes through `TradingEventPublisher`, not direct service calls, so it works both with and without Kafka.
- `risk:` / `charges:` / `broker.trailing:` blocks in `application.yml` are the tuning knobs — change behavior there before touching code. Strategy on/off lives in the DB (`strategy_config`), not yaml.
- **Improving profitability workflow** (this is the point of the recent changes): run live or `POST /api/backtest/run` → read `GET /api/risk/expectancy` / backtest `avgR`+`avgPnl` → disable negative-expectancy strategies via the toggle API. Edge must beat ~0.1% round-trip charges; that's why strategies enforce a ≥0.4% min target + trend filter, and the broker trails winners.
- README.md is partly stale. Trust this file over README.

## Disclaimer

Paper trading / educational only. Not financial advice.
