# Runbook — How to Run (Live & Backtest)

Practical steps to run the system **live** (equity + F&O paper trading) and to
**backtest** either or both engines after hours. For the architecture see
`docs/SYSTEM_DESIGN.md`; for repo conventions see `CLAUDE.md`.

> Paper trading / educational only. No real orders are ever placed.

Base URL for all API calls: **`http://localhost:8080`**.

---

## 0. Prerequisites

| Need | For | Notes |
|------|-----|-------|
| **Java 8** + **Maven** | build/run | `java -version` → 1.8 |
| **PostgreSQL** | all persistence | create DB `algo_trading`; Flyway builds/upgrades the schema on startup |
| **Groww paid API key + secret** | F&O backtest, and live/backtest when `provider: groww` | free-plan tokens 403 on market data. Real FNO premium candles need the paid plan |
| Telegram bot token + chat id | optional alerts | blank by default |

### One-time setup

```bash
# 1. Postgres
createdb algo_trading            # or: psql -c "CREATE DATABASE algo_trading;"

# 2. Credentials via env (preferred over hard-coding in yaml)
export GROWW_API_KEY=your_key
export GROWW_API_SECRET=your_secret
# optional: export GROWW_ACCESS_TOKEN=...   (skips the daily exchange)
# optional: export TELEGRAM_BOT_TOKEN=...  TELEGRAM_CHAT_ID=...
```

Set the datasource in `src/main/resources/application.yml` (`spring.datasource.url /
username / password`). On first start Flyway runs migrations **V1 → V16** — key later
ones: **V12** parallel engines + `fno_open_position`/`fno_trade_log` + `strategy_config.segment`,
**V13** `fno_candle_history`, **V15** backtest persistence tables, **V16** `index_candle_history`.

### Build

```bash
mvn clean package -DskipTests
# jar → target/algo-trading-monolith-1.0.0.jar
```

---

## 1. Configure which engines run (`application.yml`)

```yaml
trading:
  equity: { enabled: true }   # EQUITY engine (cash intraday)
  fno:    { enabled: true }   # F&O engine (index options)

fno:
  enabled: true               # F&O master switch (must be on for the F&O pass)

app:
  datafeed:
    provider: groww           # groww (paid) | yahoo (free)
```

| Goal | `trading.equity.enabled` | `trading.fno.enabled` + `fno.enabled` |
|------|:---:|:---:|
| Run **both** (default) | true | true |
| Equity **only** | true | false |
| F&O **only** | false | true |

Changing these needs a restart (they're read at startup).

---

## 2. Which strategy runs in which engine

Source of truth: the `strategy_config` table (`segment` = EQUITY / FNO / BOTH).
Defaults after V12:

| Strategy | segment |
|---|---|
| VWAP_TREND, EMA_PULLBACK, ORB_REFINED | EQUITY |
| INDEX_TREND | FNO |

**View current config:**
```bash
curl http://localhost:8080/api/strategy/config
```

**Change a strategy's engine** (e.g. also run VWAP_TREND on F&O), then hot-reload:
```sql
UPDATE strategy_config SET segment = 'BOTH' WHERE strategy = 'VWAP_TREND';
```
```bash
curl -X POST http://localhost:8080/api/strategy/config/refresh
```

**Enable / disable a strategy at runtime (no restart):**
```bash
curl -X POST http://localhost:8080/api/strategy/config/INDEX_TREND/disable
curl -X POST http://localhost:8080/api/strategy/config/INDEX_TREND/enable
```

---

## 3. Run LIVE

```bash
java -jar target/algo-trading-monolith-1.0.0.jar
# or during development:
mvn spring-boot:run
```

- App starts on `http://localhost:8080`.
- During **market hours (IST 09:15–15:25)** the scheduler runs a scan every 5 min:
  an EQUITY pass then an F&O pass (whichever engines are enabled). Exit checks run
  every 5s; EOD square-off at 15:26.
- Outside market hours the scans no-op (the risk gate blocks off-hours trading).

### Set the equity watchlist (optional)

Equity symbols come from the `intraday_symbol` table, falling back to `app.symbols`.
Index symbols are automatically excluded from the equity list (the F&O pass owns them).

```bash
# replace the equity shortlist
curl -X POST "http://localhost:8080/api/symbols/replace?source=manual" \
  -H "Content-Type: application/json" \
  -d '[{"symbol":"RELIANCE"},{"symbol":"TCS"},{"symbol":"INFY"}]'

# see what will actually be scanned
curl http://localhost:8080/api/symbols/resolved
```

F&O underlyings come from `fno.underlyings` in yaml (NIFTY, BANKNIFTY, FINNIFTY,
MIDCPNIFTY) — no watchlist needed.

### Trigger a scan manually (don't wait for the 5-min timer)

```bash
curl -X POST http://localhost:8080/api/engine/scan     # run one full scan now
curl -X POST http://localhost:8080/api/engine/eod      # force EOD square-off
curl     http://localhost:8080/api/engine/status       # engine status
```

### See results while live

```bash
curl http://localhost:8080/api/broker/positions            # open positions (equity + F&O)
curl "http://localhost:8080/api/risk/expectancy?days=7"    # per-strategy realized edge (both engines)
```
Open trades persist to `open_position` / `fno_open_position`; closed trades to
`trade_log` / `fno_trade_log`.

---

## 4. Run BACKTEST (after hours — any time)

Backtests replay strategies over Groww **historical** candles (paid plan). Two split
endpoints, one per engine. Params:

- `symbols` — CSV (required). Equity: tickers. F&O: index names.
- `strategy` — one `StrategyType` (optional; omit = all strategies **for that segment**).
- `days` — history window (default 30).

### 4a. Equity backtest

```bash
curl -X POST "http://localhost:8080/api/backtest/equity?symbols=RELIANCE,TCS,INFY&days=30"

# single strategy:
curl -X POST "http://localhost:8080/api/backtest/equity?symbols=RELIANCE,TCS&strategy=VWAP_TREND&days=60"
```

### 4b. F&O backtest (real Groww FNO option premiums)

```bash
curl -X POST "http://localhost:8080/api/backtest/fno?symbols=NIFTY,BANKNIFTY&days=30"

# single strategy:
curl -X POST "http://localhost:8080/api/backtest/fno?symbols=NIFTY&strategy=INDEX_TREND&days=30"
```

### 4c. Run BOTH (equity + F&O) — call both endpoints

```bash
# equity
curl -s -X POST "http://localhost:8080/api/backtest/equity?symbols=RELIANCE,TCS,INFY&days=30"
# F&O
curl -s -X POST "http://localhost:8080/api/backtest/fno?symbols=NIFTY,BANKNIFTY,FINNIFTY&days=30"
```

One line for both:
```bash
for seg in equity fno; do
  if [ "$seg" = equity ]; then SYMS=RELIANCE,TCS,INFY; else SYMS=NIFTY,BANKNIFTY; fi
  echo "=== $seg ==="
  curl -s -X POST "http://localhost:8080/api/backtest/$seg?symbols=$SYMS&days=30"
  echo
done
```

> Backtests only need the app running + DB + Groww creds — **not** market hours.
> Start the app, then hit the endpoints.

---

## 5. Reading backtest results

Response is `ApiResponse` with a `data` array of one result **per strategy**:

```jsonc
{
  "success": true,
  "message": "F&O backtest complete — 2 index(es), 30d, 1 strategy result(s)",
  "data": [
    {
      "strategy": "INDEX_TREND",
      "mode": "FNO",          // EQUITY or FNO
      "skipped": 4,           // FNO: signals dropped — no Groww option data / no contract
      "symbols": 2,
      "trades": 18,
      "wins": 11, "losses": 7,
      "winRate": 61.11,
      "grossPnl": 12450.0,
      "totalCharges": 1180.0,
      "netPnl": 11270.0,
      "avgPnl": 626.11,       // expectancy per trade (net)
      "avgR": 0.74,           // avg outcome in R (net of charges)
      "profitFactor": 2.1,
      "bestTrade": 3200.0,
      "worstTrade": -1450.0
    }
  ]
}
```

**What to look at:** `avgR` and `avgPnl` (expectancy) must beat charges — aim for
`avgR > 0` after `totalCharges`. `profitFactor > 1.5` and `winRate` give context.
For F&O, watch **`skipped`**: high skipped = Groww had no historical premium data for
those contracts (often expired weeklies) — the sample is thinner than `trades` alone
suggests.

**Kill negative-edge strategies:** if a strategy's `avgR` is negative live or in
backtest, disable it: `POST /api/strategy/config/{type}/disable`.

### 5a. Every run is also saved to the DB

The same run is persisted (best-effort — a DB error never fails the response). Inspect it
in SQL:

```sql
-- the run header + per-strategy summary (= the JSON data[])
SELECT * FROM backtest_run    ORDER BY id DESC LIMIT 5;
SELECT * FROM backtest_result WHERE run_id = <id>;

-- every simulated trade (entry/exit, side, charges, pnl, R, exit_reason)
SELECT symbol, side, entry_time, entry_price, exit_time, exit_price, pnl, risk_reward, exit_reason
  FROM backtest_trade_log      WHERE run_id = <id> ORDER BY entry_time;   -- equity
SELECT symbol, option_type, strike, lots, entry_price, exit_price, pnl, exit_reason
  FROM backtest_fno_trade_log  WHERE run_id = <id> ORDER BY entry_time;   -- F&O

-- the OHLC the run used (3 isolated stores)
SELECT symbol, count(*) FROM candle_history        GROUP BY symbol;   -- equity
SELECT symbol, count(*) FROM index_candle_history  GROUP BY symbol;   -- index underlying
SELECT symbol, count(*) FROM fno_candle_history    GROUP BY symbol;   -- option premium (signal-driven)
```

`exit_reason` ∈ TARGET / STOP_LOSS / TRAIL_STOP / UNDERLYING_STOP / PREMIUM_STOP / EOD /
RANGE_END. `backtest_fno_trade_log.symbol` is the option `groww_symbol`
(`NSE-NIFTY-08Jul25-24500-CE`); `fno_candle_history` fills only for contracts a signal
actually traded.

---

## 6. Legacy generic backtest

`POST /api/backtest/run?symbols=RELIANCE,TCS&strategy=VWAP_TREND&count=500` — replays
over stored `candle_history` / live feed (candle **count**, not days), cash charges,
no segment filter. Kept for quick checks; prefer `/equity` and `/fno`.

---

## 7. Running Kafka locally (Docker Desktop — optional)

Kafka is **off by default** (`app.kafka.enabled: false`) and not required — everything
falls back to a direct DB save. Turn it on only when a broker is actually running at
`spring.kafka.bootstrap-servers` (default `localhost:9092`), otherwise every publish stalls
~60s then fails. With Docker Desktop running, use either option below.

### Recommended — the repo `docker-compose.yml` (one command)

The repo root ships a `docker-compose.yml` that starts **both** dependencies the project
needs — PostgreSQL (`localhost:5432`, db `algo_trading`, `algo`/`algo`) and Kafka
(`localhost:9092`, single-node KRaft):

```bash
docker compose up -d          # start Postgres + Kafka
docker compose logs -f        # watch
docker compose down           # stop  (add -v to also wipe the DB volume)
```

If you use Neon (or another DB) and only need the broker: `docker compose up -d kafka`.

### Or just Kafka, no file (one-liner)

```bash
docker run -d --name kafka -p 9092:9092 apache/kafka:3.7.0
```

The official `apache/kafka` image runs single-node KRaft mode and advertises
`localhost:9092` out of the box — ready for a host client.

### Turn it on in the app

```yaml
# application.yml
app:
  kafka:
    enabled: true          # was false
spring:
  kafka:
    bootstrap-servers: localhost:9092
```
Restart the app. Topics auto-create on first publish (`auto.create.topics.enable`):
`algotrading.trade-reporting`, `algotrading.trade-notifications`,
`algotrading.candle-ingest`, `algotrading.index-candle-ingest`,
`algotrading.fno-candle-ingest`.

### Verify / operate

```bash
docker ps                                            # broker up?
# list topics (exec inside the container)
docker exec -it kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
# tail a topic
docker exec -it kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic algotrading.candle-ingest --from-beginning

docker stop kafka   # stop      (compose: docker compose down)
docker start kafka  # resume
```
App-side confirmation: logs show `[Kafka] Sent to algotrading.… ` instead of the
`Broker may not be available` warnings. If you stop the broker, candle publishes fall
back to a direct DB save automatically (reporting/alerts are dropped).

---

## 8. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Backtest returns **HTTP 500** + logs spam `Broker may not be available` (localhost:9092) | `app.kafka.enabled: true` but **no Kafka broker running** — candle persist blocks then throws into the request | set `app.kafka.enabled: false` (default; direct DB save, no broker needed), or start a broker at `spring.kafka.bootstrap-servers`. NB: this is the **Kafka** broker, not the trading broker |
| F&O backtest returns high `skipped`, few `trades` | Groww has no historical premium candles for those (expired) option symbols, **or** a wrong `groww_symbol` | try recent `days`, liquid indices (NIFTY/BANKNIFTY); confirm the option `groww_symbol` shape `NSE-NIFTY-08Jul25-24500-CE` (case-sensitive) |
| Every data call 403 / no candles | Groww **free** plan (no market-data scope) | use the paid plan, or set `app.datafeed.provider: yahoo` (equity/live only; Yahoo has no option data) |
| Backtest 0 trades | window too short, or strategy disabled/segment mismatch | raise `days`; check `GET /api/strategy/config` (segment + enabled) |
| Live scan does nothing | off-hours, both engines disabled, or empty watchlist | check IST market hours, `trading.*.enabled`, `GET /api/symbols/resolved` |
| F&O pass never trades | `fno.enabled=false` or no FNO-segment strategy enabled | enable `fno.enabled` + `trading.fno.enabled`; ensure a strategy has `segment=FNO` |
| Flyway checksum error on start | an applied migration was edited | never edit `V1..V16`; add a new `V17__*.sql` |
| `fno_candle_history` / `index_candle_history` empty after a run | no signal fired (option store is signal-driven), or index bars only appear once the F&O/live scan fetches them | run `/fno` with `days=30`; check `[Backtest][FNO] … → N trade(s)` (N>0) and `[GrowwHist] ← FNO … M candle(s)` (M>0) |

---

## 9. Quick reference

```bash
# BUILD
mvn clean package -DskipTests

# KAFKA (optional — only if app.kafka.enabled=true; needs Docker Desktop running)
docker run -d --name kafka -p 9092:9092 apache/kafka:3.7.0   # start local broker (KRaft)
docker stop kafka                                            # stop

# RUN LIVE (both engines per application.yml)
java -jar target/algo-trading-monolith-1.0.0.jar

# MANUAL LIVE SCAN
curl -X POST http://localhost:8080/api/engine/scan

# BACKTEST — equity / fno / both
curl -X POST "http://localhost:8080/api/backtest/equity?symbols=RELIANCE,TCS&days=30"
curl -X POST "http://localhost:8080/api/backtest/fno?symbols=NIFTY,BANKNIFTY&days=30"

# STRATEGY CONFIG
curl http://localhost:8080/api/strategy/config
curl -X POST http://localhost:8080/api/strategy/config/refresh

# EXPECTANCY (both engines)
curl "http://localhost:8080/api/risk/expectancy?days=7"
```
