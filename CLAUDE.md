# CLAUDE.md

Guidance for Claude Code (and any agent) working in this repo. Read this first instead of scanning the whole tree.

## What this is

**NSE intraday F&O + equity paper-trading system.** Single-JAR Spring Boot monolith. Every 5 min during market hours (IST) it runs **two parallel engine passes** — an EQUITY pass and an F&O pass (each independently gated by `trading.equity.enabled` / `trading.fno.enabled`) — each with its own symbols, its own strategies (`strategy_config.segment`), and its own order tables. It fetches candles, runs technical strategies (4 registered; which ones are live is controlled by the `strategy_config` table), validates against risk rules, opens/closes simulated ("paper") positions through a fake broker with a breakeven+trailing stop, persists everything to PostgreSQL, captures candles for backtesting, and emits non-critical work (trade logging, candle ingest, Telegram alerts) over Kafka with a synchronous fallback.

**F&O mode (`fno.enabled`, ON by default):** signals on configured indices (NIFTY/BANKNIFTY/FINNIFTY/MIDCPNIFTY) are converted into LONG index-option paper orders on the option chain — BUY index → buy CE, SELL index → buy PE, at the ATM strike of the nearest expiry, sized in lots. Exit decisions run on the underlying index levels (faithful to the strategy); fills use the option premium LTP (Groww FNO segment) with a Black-Scholes synthetic fallback; a premium hard-stop guards theta/IV bleed. Equity (non-index) symbols still trade cash intraday.

**Educational / paper only. No real orders are ever placed.** (`PaperBrokerServiceImpl`).

**Deeper reference:** `docs/SYSTEM_DESIGN.md` (architecture, HLD/flow diagrams, design tradeoffs), `docs/RUNBOOK.md` (how to run live + backtest), `docs/GROWW_API.md` (Groww call pattern, rate limits). This file is the quick agent guide; those go deeper.

## Stack

- **Java 8** (`1.8` source/target — do NOT use Java 9+ APIs: no `var`, no `List.of`, no records, no switch expressions).
- **Spring Boot 2.7.18** — Web, Actuator, Scheduling, Data JPA, Kafka.
- **PostgreSQL** + **Flyway** migrations + Hibernate (`ddl-auto: validate` — schema comes from Flyway, NOT Hibernate).
- **Kafka** (optional, gated by `app.kafka.enabled` — **default `false`**; when off, non-critical work saves directly). Enable ONLY with a broker running, else publishes stall/fail (candle persistence falls back to a direct DB save regardless).
- **Lombok** (`@RequiredArgsConstructor`, `@Slf4j`, `@Data`, `@Builder` everywhere).
- Maven build. Group `com.algotrading`, artifact `algo-trading-monolith`, v1.0.0.

## Architecture pattern

Strict **interface + impl** split. Every service is an interface in `service/` with one implementation in `service/impl/`. Constructor injection via Lombok `@RequiredArgsConstructor` (no `@Autowired` fields). Match this when adding code.

Layering: `controller` → `service` (interface) → `service/impl` → `repository` (Spring Data JPA) → `entity`. DTOs cross the wire; `entity` is JPA-only; `model` is internal mutable state.

## Core flow (the important path)

`TradingScheduler` (cron/fixed-rate) → `ScanOrchestrationServiceImpl.runScan()` (the brain). **runScan runs two engine passes sequentially** (shared in-memory broker/risk/POS-counter state → sequential, not threaded, avoids locks):

```
runScan()
├─ system gate ............................ RiskService.canTrade()
├─ if trading.equity.enabled:  runEnginePass(equity shortlist,  EQUITY)  → per symbol   processSymbol(symbol, EQUITY)
└─ if trading.fno.enabled && fno.enabled:  runEnginePass(index underlyings, FNO)   → per index    processSymbol(symbol, FNO)

processSymbol(symbol, SEGMENT) — CRITICAL (sync):
  DataFeedService     fetch candles       → YahooFinanceDataFeedServiceImpl (default; free) or GrowFinanceDataFeedServiceImpl
                                            (Groww market data needs their PAID plan — free-plan keys 403 on all data endpoints)
  StrategyService     generate signal     → StrategyServiceImpl.runStrategies(symbol, candles, SEGMENT) — enabled + segment-matched
                                            strategies only; confluence-aware (opposite signals cancel; 2+ agreeing = confidence bonus)
  OptionChainService  index → option leg  → GrowwOptionChainServiceImpl (FNO pass only, configured index: expiry+ATM strike,
                                            premium LTP, delta-mapped SL/target, lots)
  RiskService         validate + gate     → RiskServiceImpl (min-confidence 0.70, min-rr 2.1; option risk cap 2.5%)
  BrokerService       open/close paper    → PaperBrokerServiceImpl (breakeven + trailing stop; option exits decided on the
                                            UNDERLYING index price, filled at option LTP — checkOptionExit)
NON-CRITICAL (async via Kafka, direct call when kafka disabled — the default):
  via TradingEventPublisher →
    SheetsService          trade log / open-positions → PostgresSheetsServiceImpl (routes equity → trade_log/open_position,
                                                        option → fno_trade_log/fno_open_position by isOption())
    NotificationService    Telegram alerts            → TelegramNotificationServiceImpl
    Candle ingest          OHLCV persistence          → routed by symbol type (see "Candle stores" below)
```

Each engine writes its **own tables**: equity → `open_position`/`trade_log`; F&O → `fno_open_position`/`fno_trade_log`. Cross-cutting reads (POS-n sequence, daily-loss circuit, expectancy) **must union both** — the main maintenance hazard.

After any stop-loss exit a per-symbol cooldown (`risk.stop-cooldown-min`, default 20) blocks immediate re-entry; option positions key the cooldown on their underlying.

Equity symbols come from the `intraday_symbol` DB table (`IntradaySymbolService`, refreshed every 30 min; `app.symbols` yaml is fallback; indices excluded — the F&O pass owns them). F&O underlyings come from `fno.underlyings` yaml.

**Candle stores (3-way split, routed in `TradingEventPublisher.publishCandles` by symbol type — live AND backtest):**

| Symbol type | Table | Service |
|---|---|---|
| equity (RELIANCE, …) | `candle_history` | `CandleHistoryService` |
| index (NIFTY, BANKNIFTY, …) | `index_candle_history` | `IndexCandleHistoryService` |
| option premium (NSE-NIFTY-…-CE/PE) | `fno_candle_history` | `FnoCandleHistoryService` |

Each has a Kafka topic + consumer (gated on kafka) with a direct-save fallback. Publish never loses candles: if the Kafka send fails (broker down), it falls back to a direct DB save.

## Scheduler timings (IST, `Asia/Kolkata`)

| Job | When | Method |
|-----|------|--------|
| Exit checks (SL/target on open positions) | every 5s, market hours | `scheduledExitCheck` |
| Market scan (EQUITY pass + F&O pass) | every 5 min, market hours (09:15–15:25) | `scheduledScan` |
| EOD square-off | 15:26 Mon–Fri | `scheduledEod` |
| Auto shutdown | 15:27 Mon–Fri (if enabled) | `scheduledShutdown` |
| Symbol shortlist refresh | every 30 min | `IntradaySymbolService` |
| Health check | every 15 min | `HealthService` |

File: `src/main/java/com/algotrading/scheduler/TradingScheduler.java`

## Strategies (`service/impl/`, all implement `service/TradingStrategy`)

Which strategies actually trade is set by the `strategy_config` table, NOT the code. Each strategy is always a registered bean; `StrategyServiceImpl` only runs the enabled ones. Toggle at runtime via the API (below) — no restart.

**Legacy strategies were DELETED (V11)** — ORB, ORB_RETEST, VWAP_MR, EMA_CROSS, SUPERTREND, GAP_GO had negative net expectancy (charges > edge; VWAP_MR: live 0 wins / 14, avgR −0.895). Their `strategy_config` rows are removed by V11; historic `trade_log` rows with those names are tolerated (parsers null the enum).

**Live strategies — ALL ENABLED by default.** Built to beat charges: trend filter + min-move target + risk-based 2–2.5R + trailing stop. Volume confirms are NEUTRAL on indices (NSE index candles report zero volume — a hard volume gate would block every index signal).

Each strategy has a **segment** (`strategy_config.segment` = EQUITY / FNO / BOTH) that picks which engine pass runs it. Defaults:

| StrategyType | File | Segment | Setup |
|---|---|---|---|
| `VWAP_TREND`   | `VwapTrendStrategy.java`   | EQUITY | pullback to rising VWAP, ride trend, 2.5R, RSI exhaustion guard |
| `EMA_PULLBACK` | `EmaPullbackStrategy.java` | EQUITY | stacked EMA9/21/50, buy pullback to EMA21, 2.5R, RSI guard |
| `ORB_REFINED`  | `OrbRefinedStrategy.java`  | EQUITY | opening-range breakout in gap/bias direction only, 2R, 09:30–10:30 |
| `INDEX_TREND`  | `IndexTrendStrategy.java`  | FNO | volume-free index momentum: ADX(14)≥20 regime + Supertrend + EMA + VWAP alignment + strong-body trigger candle, 2.2R, stop ≤1.5×ATR |

**Confluence runner** (`StrategyServiceImpl.runStrategies(symbol, candles, Segment)`): within a pass, all enabled strategies **matching that segment** run; opposite-direction signals cancel (that disagreement = chop, stay flat); 2+ agreeing = highest-confidence signal +0.05/extra vote (cap 0.95).

Indicator math (pure static, no state): `src/main/java/com/algotrading/util/Indicator.java` — EMA, RSI, ATR, VWAP, rolling stddev, MACD, Supertrend, ADX.

**Backtest runner** — `BacktestService`/`BacktestServiceImpl` replays historical candles through strategies (no look-ahead; simulates stop/target via candle high/low, breakeven+trailing, EOD square-off, slippage both legs, real charges). Fed by the Groww **paid historical API** (`GrowwHistoricalService`). Endpoints are **`@PostMapping`** (a browser GET → 405, no logs):

- `POST /api/backtest/equity?symbols=RELIANCE,TCS&strategy=&days=30` — cash replay, cash charges. Runs EQUITY-segment strategies on the **equity** candles.
- `POST /api/backtest/fno?symbols=NIFTY&strategy=&days=30` — the strategy still runs on the **INDEX** candles; each fired signal resolves an ATM option and fetches its **real Groww FNO premium candles** for the fill only. Exit decided on the underlying frame + premium hard-stop. Signals with no option data are counted in `skipped`.
- `POST /api/backtest/run?symbols=…&count=500` — legacy generic replay (stored/live candles, cash charges).

**Every run is persisted** (best-effort, never fails the response) via `BacktestPersistenceService`: a `backtest_run` header + one `backtest_result` per strategy (= the JSON `data[]`) + **one row per simulated trade** (`backtest_trade_log` equity / `backtest_fno_trade_log` F&O) with entry/exit time+price, side, charges, gross/net pnl, R, and `exit_reason` (TARGET / STOP_LOSS / TRAIL_STOP / UNDERLYING_STOP / PREMIUM_STOP / EOD / RANGE_END). Fetched OHLC lands in the candle stores above. Read `avgR`/`avgPnl` to kill negative-edge strategies.

## Important file map

**Entry / config**
- `src/main/java/com/algotrading/AlgoTradingApplication.java` — `@SpringBootApplication` main
- `src/main/java/com/algotrading/config/AppConfig.java` — `RestTemplate`, `ObjectMapper` beans
- `src/main/java/com/algotrading/config/KafkaConfig.java` — Kafka producer/consumer, gated by `app.kafka.enabled=true` (default false → not loaded)
- `src/main/java/com/algotrading/config/FnoProperties.java` — `fno:` yaml binding (lot sizes, strike steps, expiry days, risk caps). Exchange revisions (SEBI/NSE circulars) are CONFIG changes, not code.
- `src/main/resources/application.yml` — **all tunables** (risk, fno, charges incl. `charges.fno`, datafeed provider, kafka, telegram, db)

**Orchestration / events**
- `src/main/java/com/algotrading/service/impl/ScanOrchestrationServiceImpl.java` — main trading loop (start here for behavior changes)
- `src/main/java/com/algotrading/scheduler/TradingScheduler.java` — triggers
- `src/main/java/com/algotrading/event/TradingEventPublisher.java` — Kafka publish + **direct-save fallback**. `publishCandles` routes by symbol type → equity/index/option candle store. `send()`/`trySend()` never propagate (a broker-down send that threw used to 500 the backtest); candle publishes fall back to a direct DB save so OHLC is never lost.
- `src/main/java/com/algotrading/event/TradeReportingConsumer.java` — consumes `algotrading.trade-reporting`
- `src/main/java/com/algotrading/event/TradeNotificationConsumer.java` — consumes `algotrading.trade-notifications`
- Candle consumers (all gated on kafka, dedup on symbol+ts, direct-save fallback):
  - `CandleIngestConsumer.java` — `algotrading.candle-ingest` → `candle_history` (equity)
  - `IndexCandleIngestConsumer.java` — `algotrading.index-candle-ingest` → `index_candle_history` (index underlying)
  - `FnoCandleIngestConsumer.java` — `algotrading.fno-candle-ingest` → `fno_candle_history` (option premium)

**Services (interface in `service/`, impl in `service/impl/`)**
- DataFeed → `YahooFinanceDataFeedServiceImpl` (default, free; browser UA required — Yahoo 429s Java's default; indices map to ^NSEI/^NSEBANK/...) / `GrowFinanceDataFeedServiceImpl` (needs Groww's paid data plan; free-plan tokens lack the market-data scope → 403)
- GrowwAuth → `GrowwAuthServiceImpl` (daily token exchange + throttled authed GETs, single ~150 ms gate; shared by the feed AND the option chain)
- GrowwHistorical → `GrowwHistoricalServiceImpl` (paid **historical** candles for the backtest — `GET /v1/historical/candles`, chunked; CASH underlying + FNO option premium; persists each pull to the matching candle store)
- OptionChain → `GrowwOptionChainServiceImpl` (index signal → option leg: expiry/ATM strike resolution, compact NSE trading-symbol for LTP, Groww FNO LTP with Black-Scholes synthetic fallback, delta-mapped premium SL/target, lot sizing)
- Strategy → `StrategyServiceImpl` (enabled + **segment** gating + runtime toggle + confluence)
- Risk → `RiskServiceImpl` (+ per-strategy expectancy report; unions equity+F&O trade tables; option positions get a 2.5% risk cap vs 1% equity)
- Broker → `PaperBrokerServiceImpl` (slippage + breakeven/trailing stop; `checkOptionExit` = underlying-frame decisions, premium fills, premium hard-stop)
- Sheets (trade log) → `PostgresSheetsServiceImpl` (routes equity vs F&O tables by `isOption()`)
- Notification → `TelegramNotificationServiceImpl`
- Health → `InternalHealthServiceImpl`
- IntradaySymbol → `IntradaySymbolServiceImpl`
- Charges → `ChargesServiceImpl` (India intraday equity AND index-option brokerage/STT/GST math — see `charges.fno`)
- CandleHistory → `CandleHistoryServiceImpl` (equity CASH) · IndexCandleHistory → `IndexCandleHistoryServiceImpl` (index CASH) · FnoCandleHistory → `FnoCandleHistoryServiceImpl` (option premium) — each upserts on symbol+ts, serves the backtest
- Backtest → `BacktestServiceImpl` (replay; validates the UNDERLYING edge) + `BacktestPersistenceServiceImpl` (persists run/results/trades)

**F&O utils/models**
- `util/OptionPricing.java` — Black-Scholes premium + delta (synthetic fallback and SL/target mapping)
- `util/Symbols.java` — canonical index aliases (NIFTY_50 → NIFTY) **+ Groww `groww_symbol` builders**: `growwCashSymbol` (`NSE-RELIANCE`/`NSE-NIFTY`), `growwOptionSymbol` (`NSE-NIFTY-08Jul25-24500-CE`, case-sensitive). Shared feed/chain/historical.
- `model/OptionContract.java`, `enums/InstrumentType.java`, `enums/OptionType.java`, `enums/Segment.java`

**Persistence**
- Entities: `src/main/java/com/algotrading/entity/` —
  - Live equity: `TradeLogEntity`, `OpenPositionEntity`; live F&O (mirror): `FnoTradeLogEntity`, `FnoOpenPositionEntity`
  - `DailySummaryEntity`, `IntradaySymbolEntity`, `HealthCheckLogEntity`, `StrategyConfigEntity` (enabled + `segment`)
  - Candle stores (all unique on symbol+ts): `CandleHistoryEntity` (equity), `IndexCandleHistoryEntity` (index), `FnoCandleHistoryEntity` (option premium)
  - Backtest: `BacktestRunEntity` (run header), `BacktestResultEntity` (per-strategy summary), `BacktestTradeLogEntity` (equity trades), `BacktestFnoTradeLogEntity` (F&O trades)
- Repos: `src/main/java/com/algotrading/repository/` (Spring Data JPA). The candle repos have native `ON CONFLICT` upserts.
- Migrations: `src/main/resources/db/migration/V1..V16__*.sql` — **schema changes go here as a new `V17__*.sql`, never edit existing migrations** (Flyway validates checksums). Key ones: V8 `strategy_config`, V10 `candle_history`, V11 F&O columns, **V12** parallel engines + `strategy_config.segment` + `fno_open_position`/`fno_trade_log`, **V13** `fno_candle_history`, V14 restore FNO segment defaults, **V15** backtest persistence (`backtest_run`/`backtest_result`/`backtest_trade_log`/`backtest_fno_trade_log`), **V16** `index_candle_history`. On Neon, Flyway runs over the DIRECT (non-pooler) endpoint — see `spring.flyway.url` — because session advisory locks break on PgBouncer.

**Strategy enable/disable** — `strategy_config` table is the source of truth for which strategies run the live scan. Loaded once into memory at startup, toggled at runtime via API (no restart). `StrategyServiceImpl` holds a `volatile Set<StrategyType>`. The `strategy.enabled` yaml CSV is only a fallback when the table is empty.

**REST controllers** (`src/main/java/com/algotrading/controller/`)
- `/api/feed/**` DataFeed · `/api/strategy/**` · `/api/risk/**` · `/api/broker/**` (positions, equity+F&O) · `/api/sheets/**` · `/api/notify/**` · `/api/health/**` · `/api/engine/**` (manual scan/eod) · `/api/symbols/**` · `IntradaySymbolController`
- **Strategy runtime toggle**: `GET /api/strategy/config`, `POST /api/strategy/config/{type}/enable|disable`, `POST /api/strategy/config/refresh`
- **Per-strategy expectancy** (kill negative-edge strategies): `GET /api/risk/expectancy?days=N`
- **Backtest** (all POST): `POST /api/backtest/equity?symbols=…&strategy=&days=30`, `POST /api/backtest/fno?symbols=NIFTY&strategy=&days=30`, `POST /api/backtest/run?symbols=…&count=500` (legacy). `strategy` optional = all for that segment.

**Tests** (`src/test/java/com/algotrading/`)
- `service/impl/`: `ChargesServiceImplTest` (equity + option charges), `IntradaySymbolServiceImplTest`, `PaperBrokerServiceImplTest`, `GrowwOptionChainServiceImplTest` (CE/PE conversion, ATM strike, lot sizing)
- `util/SymbolsTest` (Groww `groww_symbol` cash + option formats)

## Build & run

```bash
mvn clean package -DskipTests         # build jar
java -jar target/algo-trading-monolith-1.0.0.jar
mvn spring-boot:run                   # or run directly
mvn test                              # run tests
mvn test -Dtest=ChargesServiceImplTest  # single test
```

App: **http://localhost:8080**. Requires PostgreSQL (set `spring.datasource.url/username` in `application.yml`); create DB `algo_trading`, Flyway builds/upgrades tables on startup (through V16). **Kafka default OFF** — leave it off unless a broker is actually running. Groww creds (`GROWW_API_KEY`/`SECRET` env vars) are needed for the Groww feed + all backtests; Telegram optional; blank by default.

## Conventions / gotchas

- **Java 8 only.** No 9+ syntax or APIs.
- **New migration, never edit old ones.** Next = `V17__*.sql`. Entity columns must match the SQL exactly or `ddl-auto: validate` fails fast at boot.
- **Groww historical = `GET /v1/historical/candles`** (current API), params `groww_symbol` + `candle_interval` (`5minute`) + `start_time`/`end_time` in **epoch seconds**. The old `/v1/historical/candle/range` (`trading_symbol` + `interval_in_minutes` + millis) is deprecated — do not reintroduce it. `groww_symbol` is dash-joined and **case-sensitive** for options (`NSE-NIFTY-08Jul25-24500-CE`). Groww caps 1–5 min candles at **15 days/request** (`backtest.groww.max-days-per-request: 15`). LTP stays on `/v1/live-data/ltp` with `exchange_symbols=NSE_RELIANCE` (underscore).
- **Candle storage is a 3-way split**, routed by symbol type in `publishCandles` (live + backtest): equity → `candle_history`, index → `index_candle_history`, option → `fno_candle_history`. `fno_candle_history` is signal-driven (only the ATM contract of a fired signal is fetched) — no signal = no option candles, by design.
- **Backtest endpoints are POST** — a browser GET returns 405 with no logs. Every run is persisted to `backtest_*` tables (best-effort — a persistence failure never fails the response). Trace logs: `[GrowwHist] → GET … ← N candle(s)`, `[Backtest][EQUITY|FNO] sym · STRAT → N trade(s)`.
- **Kafka default OFF.** Enabling it without a live broker makes each publish stall on metadata then fail (candle publishes fall back to a direct save; reporting/notification sends are dropped). The health "Kafka OK" line does NOT round-trip the broker — it's not proof a broker exists.
- **Index candles have ZERO volume** — never hard-gate an index strategy on volume; make the confirm neutral when `rollingAvgVolume <= 0` (see VwapTrendStrategy/OrbRefinedStrategy).
- **F&O tunables live in yaml** (`fno:` block) — lot sizes, strike steps, expiry days, risk caps. NSE revises these by circular; update config, not code. Option orders are always LONG (buy CE/PE) — no writing/margin model.
- **Separate F&O tables** (`fno_open_position`/`fno_trade_log`) mirror the equity ones — any new F&O-writing path must keep the cross-table unions in sync (POS-n sequence, daily-loss circuit, expectancy).
- New service ⇒ add interface in `service/` + impl in `service/impl/`. Inject via constructor (Lombok), not field `@Autowired`.
- New strategy ⇒ implement `TradingStrategy` as a `@Component`, add a `StrategyType` enum value, seed a `strategy_config` row (with `segment`) in a new migration. It's auto-injected into `StrategyServiceImpl`.
- All times are IST (`Asia/Kolkata`). Market 09:15–15:25.
- Non-critical work (logging/alerts/candle ingest) goes through `TradingEventPublisher`, not direct service calls, so it works both with and without Kafka.
- `risk:` / `charges:` / `broker.trailing:` blocks in `application.yml` are the tuning knobs — change behavior there before touching code. Strategy on/off + segment live in the DB (`strategy_config`), not yaml.
- **Improving profitability workflow**: run `POST /api/backtest/equity|fno` → read `GET /api/risk/expectancy` / backtest `avgR`+`avgPnl` (or the persisted `backtest_result` rows) → disable negative-expectancy strategies via the toggle API. Edge must beat ~0.1% round-trip charges; that's why strategies enforce a ≥0.4% min target + trend filter, and the broker trails winners.
- README.md is partly stale. Trust this file (and `docs/`) over README.

## Disclaimer

Paper trading / educational only. Not financial advice.
