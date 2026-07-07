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
username / password`). On first start Flyway runs migrations **V1 → V12** (creates
`fno_open_position`, `fno_trade_log`, and the `strategy_config.segment` column).

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

---

## 6. Legacy generic backtest

`POST /api/backtest/run?symbols=RELIANCE,TCS&strategy=VWAP_TREND&count=500` — replays
over stored `candle_history` / live feed (candle **count**, not days), cash charges,
no segment filter. Kept for quick checks; prefer `/equity` and `/fno`.

---

## 7. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| F&O backtest returns high `skipped`, few `trades` | Groww has no historical premium candles for those (expired) option symbols | try recent `days`, liquid indices (NIFTY/BANKNIFTY); this is a data-availability limit, not a bug |
| Every data call 403 / no candles | Groww **free** plan (no market-data scope) | use the paid plan, or set `app.datafeed.provider: yahoo` (equity/live only; Yahoo has no option data) |
| Backtest 0 trades | window too short, or strategy disabled/segment mismatch | raise `days`; check `GET /api/strategy/config` (segment + enabled) |
| Live scan does nothing | off-hours, both engines disabled, or empty watchlist | check IST market hours, `trading.*.enabled`, `GET /api/symbols/resolved` |
| F&O pass never trades | `fno.enabled=false` or no FNO-segment strategy enabled | enable `fno.enabled` + `trading.fno.enabled`; ensure a strategy has `segment=FNO` |
| Flyway checksum error on start | an applied migration was edited | never edit `V1..V12`; add a new `V13__*.sql` |

---

## 8. Quick reference

```bash
# BUILD
mvn clean package -DskipTests

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
