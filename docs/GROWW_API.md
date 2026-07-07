# Groww API — Call Pattern, Rate & Throughput

How this system talks to the Groww API: which endpoints, on what schedule, at what
gaps, how many requests/second we generate (with **both EQUITY + F&O engines
enabled**), what Groww accepts, and how to push more throughput — with the tradeoffs.

> Groww's published limits change. Numbers marked **(verify)** are representative —
> confirm against the current Groww developer docs before tuning aggressively.
> Applies when `app.datafeed.provider: groww` (paid plan). Yahoo is a separate path.

---

## 1. How we call Groww (one shared, throttled gate)

Every Groww HTTP call — from the data feed **and** the option chain — goes through
`GrowwAuthServiceImpl.get(url)`, which:

1. attaches the daily access token (exchanged once/day via the SHA256 checksum flow), and
2. passes through **`throttleRequestRate()`** — a **`synchronized`** method that sleeps
   so consecutive calls are at least **`groww.min-request-gap-ms` (default 150 ms)** apart.

```
   feed.getCandles ─┐
   feed.getLastPrice ├─► GrowwAuthService.get() ─► [ synchronized 150ms gap ] ─► Groww
   optionChain.LTP ─┘        (single global gate, shared by BOTH engines)
```

**Consequence:** the throttle is a single global serializer. Enabling both engines does
**not** raise the peak rate — all calls queue through the same 150 ms gate. The hard
ceiling we impose on ourselves is:

```
   1000 ms / 150 ms  ≈  6.67 requests / second   (max, single-threaded, shared)
```

---

## 2. Endpoints we hit

| Purpose | Endpoint | Segment | Cached? | Called from |
|---------|----------|---------|---------|-------------|
| Daily token exchange | `POST /v1/token/api/access` (checksum) | — | once/day | `GrowwAuthServiceImpl` |
| Underlying/equity candles | `GET /v1/historical/candles` | CASH | **yes**, `datafeed.cache.ttl-seconds` (90s) | `GrowFinanceDataFeedServiceImpl` |
| Last price (LTP) | `GET /v1/live-data/ltp?exchange_symbols=…` | CASH / FNO | **no** (always live) | feed + option chain |
| Option premium candles (backtest) | `GET /v1/historical/candles` | FNO | persisted to `fno_candle_history` (async via Kafka) | `GrowwHistoricalService` |

> **Historical candle params (current API — `/v1/historical/candles`):**
> `exchange`, `segment` (CASH/FNO), `groww_symbol`, `start_time`, `end_time`, `candle_interval`.
> - `groww_symbol` is Groww's dash-joined id: `NSE-RELIANCE` / `NSE-NIFTY` (CASH),
>   `NSE-NIFTY-08Jul25-24500-CE` (FNO option — **case-sensitive**, `ddMMMyy` expiry).
>   Built by `Symbols.growwCashSymbol` / `Symbols.growwOptionSymbol`.
> - `start_time` / `end_time` = **epoch seconds** (or `yyyy-MM-dd HH:mm:ss`). Digits-only
>   seconds avoid RestTemplate double-encoding a space in the datetime form.
> - `candle_interval` = a token like `5minute` / `1hour` / `1day` (NOT `interval_in_minutes`).
> - The old `/v1/historical/candle/range` shape (`trading_symbol` + `interval_in_minutes` +
>   epoch **millis**) was the deprecated SDK-style call — migrated away from it.
>
> The LTP endpoint's param is `exchange_symbols` (**plural**, underscore-joined
> `NSE_RELIANCE`) — Groww accepts **multiple symbols per call**. We currently send **one
> symbol per call**; batching is the biggest throughput lever (see §6).

---

## 3. When calls happen (live schedule, IST)

| Job | Frequency | Groww work |
|-----|-----------|-----------|
| Market scan (both passes) | every **5 min**, 09:15–15:25 | candle fetch per symbol (cache-miss) + option LTP on fired F&O signals |
| Exit checks | every **5 s**, market hours | LTP per open position (equity: 1; option: 2) |
| Token exchange | once at startup / daily | 1 |
| Backtest (after hours) | on-demand | many historical calls, chunked (see §7) |

Candle fetches are cached for 90 s, so within one 5-min scan each symbol is fetched at
most once. **LTP is never cached** — every exit check re-queries Groww.

---

## 4. Request math with BOTH engines enabled

Let:
- `E` = equity scan symbols (e.g. 10)
- `U` = F&O index underlyings = **4** (NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY)
- `Pe` = open equity positions, `Po` = open option positions
- `S` = F&O signals that fire this scan (usually 0–few)

### Per 5-minute scan

```
  Equity pass : E candle fetches            (1 per symbol, cache expired)
  F&O pass    : U candle fetches  + S LTP   (option LTP only when a signal fires)
  ------------------------------------------------
  ≈ (E + U) candle calls + S option-LTP calls
```

Worked example `E=10, U=4, S=2`: **~16 calls per scan**. Serialized at 150 ms ≈
**2.4 s of Groww traffic**, once every 300 s. Average during a scan burst ≈ 6.7 req/s
(throttle-bound); scan-window average ≈ 16 / 300 ≈ **0.05 req/s**.

### Per 5-second exit check

```
  Equity : Pe × 1 LTP        (equity price cached per symbol within the batch)
  Option : Po × 2 LTP        (1 underlying LTP + 1 option LTP each)
  ------------------------------------------------
  ≈ Pe + 2·Po  calls  every 5 s
```

Worked example `Pe=3, Po=2`: **~7 calls / 5 s** = **1.4 req/s average**, ~1.05 s of
Groww traffic per check. A busy book (`Pe=10, Po=8` → 26 calls) is ~3.9 s per check at
the 150 ms gap — still under the **5 s** window, but the margin is now tight: a very
large book (**>~33 calls per check**) would overrun a 5 s cycle and back the checks up.
This is the main cost of the 15 s → 5 s change — see §6.

### Peak vs average (both engines on)

| Metric | Value |
|--------|-------|
| **Hard ceiling** (throttle) | **≤ ~6.67 req/s** |
| Scan burst (during the ~2–3 s of calls) | ~6.7 req/s |
| Scan-window average | ~0.05 req/s |
| Exit-check average (at 5 s) | ~1.0–1.5 req/s |
| Per-minute (typical) | ~60–120 req/min |
| Per-day (rough, ~6.25 h session) | a few thousand calls |

**Both engines enabled ≈ the same peak as one engine** — it only adds `U` candle calls
and the option LTPs to the queue behind the shared gate.

---

## 5. What Groww accepts (documented limits — **verify**)

Groww publishes rate limits per API category (per-second, per-minute, per-day). They
are structured roughly like this **(representative; confirm current values):**

| Category | Per-second **(verify)** | Per-minute **(verify)** | Per-day **(verify)** |
|----------|:---:|:---:|:---:|
| Live data (LTP / quote / OHLC) | ~10/s | ~300/min | a few thousand |
| Historical candles | lower (a few/s) | limited | limited |
| Orders (not used — paper only) | ~15/s | — | — |

**Headroom verdict:** at 150 ms (≈6.7/s) with candle caching, we sit **comfortably
under** a ~10/s live-data cap and well under per-minute/day caps. There is real
headroom to speed up — see §6.

---

## 6. Increasing throughput — levers & tradeoffs

Ordered roughly best-value first.

| # | Change | Effect | Tradeoff / risk |
|---|--------|--------|-----------------|
| 1 | **Batch LTP** — send many `exchange_symbols` in one call | N per-symbol calls → **1 call**; biggest cut in request count | code change (batch request + parse per-symbol map); a failed batch affects all symbols in it |
| 2 | **Lower `groww.min-request-gap-ms`** (150 → 100 / 75) | raises ceiling to ~10–13 req/s | closer to Groww's cap → **429s / temp bans** if you overshoot; test in steps |
| 3 | **Raise `datafeed.cache.ttl-seconds`** (90 → up to bar interval) | fewer candle refetches | staler candles — safe up to ~5 min since a 5-min bar doesn't change mid-bar |
| 4 | **Cache LTP briefly** (add a 3–10 s LTP cache) | dedupes repeated LTP within/near an exit cycle | exits act on slightly stale price → marginally worse fills |
| 5 | **Tune exit-check frequency** (`app.exit-check-interval-ms`, now **5s**) | ↓ interval = faster stop/target reaction; ↑ = fewer LTP calls | faster = 3× the LTP volume (15s→5s already applied); slower = worse fills, more slippage-vs-model drift |
| 6 | **Split providers** — Yahoo for equity candles, Groww for F&O/options only | frees Groww quota for options; parallel rate budgets | two code paths; Yahoo 429s on bursts (needs its 1200 ms gap); Yahoo has no option data |
| 7 | **Token-bucket limiter** instead of a fixed 150 ms gap | allow short bursts up to Groww's per-second cap while honoring per-minute/day | more complex; must track multiple windows; misconfig → **ban** |
| 8 | **Parallelize passes/fetches** (thread pool behind the limiter) | better wall-clock when the book is large | shared broker/risk state needs synchronization; only worth it past the 6.7/s gate, so pair with #2/#7 |

**Highest leverage:** **#1 (batch LTP)** + **#3 (cache TTL)** cut the *number* of calls
without approaching Groww's cap — safest wins. **#2/#7** raise the *rate* but move you
toward 429s; change them in small steps and watch for `429` / `Forbidden` in logs
(`GrowwAuthServiceImpl` already backs off on `403`).

---

## 7. Backtest traffic (after hours)

`GrowwHistoricalService` pulls wide windows in **chunks** of
`backtest.groww.max-days-per-request` (default **15 days**) at `interval-minutes` (5), each
chunk one throttled call. **Groww caps 1–5 min candles at 15 days per request** — do NOT
raise this above 15 for the default 5-min granularity or the call errors (coarser
intervals allow more: 10–30 min → 90d, hourly+ → 180d). For the F&O backtest it also
fetches **one option's premium candles per traded signal** (entry day), so request count
scales with the number of signals × symbols. This runs after hours, so it doesn't compete
with the live loop, but it is the heaviest Groww consumer.

---

## 8. Config knobs (summary)

```yaml
groww:
  min-request-gap-ms: 150          # §6.2 — global throttle; ↓ = more req/s, ↑ ban risk

datafeed:
  cache:
    ttl-seconds: 90                # §6.3 — candle cache; ↑ = fewer fetches, staler bars

app:
  exit-check-interval-ms: 5000     # §6.5 — LTP cadence (now 5s); ↑ = fewer calls, slower exits
  datafeed:
    provider: groww                # §6.6 — groww | yahoo

backtest:
  groww:
    max-days-per-request: 15       # §7 — chunk size; Groww caps 5-min candles at 15d/request
    interval-minutes: 5
```

> **Kafka default is OFF** (`app.kafka.enabled: false`). Candle persistence + trade
> logging + alerts then run as a **direct synchronous DB save** — no broker required.
> Turn it on ONLY with a broker actually running at `spring.kafka.bootstrap-servers`;
> enabling it without one makes every publish block on metadata then throw (a backtest
> returns **HTTP 500** and the logs fill with `Broker may not be available`).

**Bottom line:** with both engines on we generate a **bursty ≤6.7 req/s, low average**
load that fits comfortably inside Groww's typical limits. To go faster, **batch LTP
first** (fewer calls, no extra ban risk), then carefully lower the request gap while
watching for 429s.
