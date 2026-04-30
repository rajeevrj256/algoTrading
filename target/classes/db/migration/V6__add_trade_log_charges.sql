ALTER TABLE trade_log
    ADD COLUMN IF NOT EXISTS charges DOUBLE PRECISION DEFAULT 0;

WITH base AS (
    SELECT id,
           COALESCE(quantity, 0) AS quantity,
           COALESCE(entry_price, 0) AS entry_price,
           COALESCE(exit_price, 0) AS exit_price,
           CASE
               WHEN side = 'SELL' THEN COALESCE(exit_price, 0) * COALESCE(quantity, 0)
               ELSE COALESCE(entry_price, 0) * COALESCE(quantity, 0)
           END AS buy_value,
           CASE
               WHEN side = 'SELL' THEN COALESCE(entry_price, 0) * COALESCE(quantity, 0)
               ELSE COALESCE(exit_price, 0) * COALESCE(quantity, 0)
           END AS sell_value,
           CASE
               WHEN side = 'SELL' THEN (COALESCE(entry_price, 0) - COALESCE(exit_price, 0)) * COALESCE(quantity, 0)
               ELSE (COALESCE(exit_price, 0) - COALESCE(entry_price, 0)) * COALESCE(quantity, 0)
           END AS gross_pnl
    FROM trade_log
),
calc AS (
    SELECT id,
           entry_price,
           quantity,
           gross_pnl,
           buy_value,
           sell_value,
           LEAST(buy_value * 0.0003, 20) + LEAST(sell_value * 0.0003, 20) AS brokerage,
           sell_value * 0.00025 AS stt,
           (buy_value + sell_value) * 0.0000325 AS exchange_charge,
           (buy_value + sell_value) * 0.000001 AS sebi,
           buy_value * 0.00003 AS stamp
    FROM base
),
totals AS (
    SELECT id,
           gross_pnl,
           entry_price,
           quantity,
           brokerage + stt + exchange_charge + (0.18 * (brokerage + exchange_charge)) + sebi + stamp AS total_charges
    FROM calc
)
UPDATE trade_log t
SET charges = ROUND(totals.total_charges::numeric, 2),
    pnl = ROUND((totals.gross_pnl - totals.total_charges)::numeric, 2),
    pnl_pct = CASE
        WHEN (totals.entry_price * totals.quantity) > 0
            THEN ROUND((((totals.gross_pnl - totals.total_charges) / (totals.entry_price * totals.quantity)) * 100)::numeric, 2)
        ELSE 0
    END
FROM totals
WHERE t.id = totals.id;

UPDATE daily_summary d
SET trades = s.trades,
    wins = s.wins,
    losses = s.losses,
    win_rate = ROUND(s.win_rate::numeric, 2),
    total_pnl = ROUND(s.total_pnl::numeric, 2),
    best_trade = ROUND(s.best_trade::numeric, 2),
    worst_trade = ROUND(s.worst_trade::numeric, 2),
    avg_pnl = ROUND(s.avg_pnl::numeric, 2)
FROM (
    SELECT trade_date::text AS summary_date,
           COUNT(*) AS trades,
           SUM(CASE WHEN pnl > 0 THEN 1 ELSE 0 END) AS wins,
           SUM(CASE WHEN pnl <= 0 THEN 1 ELSE 0 END) AS losses,
           CASE
               WHEN COUNT(*) > 0
                   THEN (SUM(CASE WHEN pnl > 0 THEN 1 ELSE 0 END) * 100.0) / COUNT(*)
               ELSE 0
           END AS win_rate,
           COALESCE(SUM(pnl), 0) AS total_pnl,
           COALESCE(MAX(pnl), 0) AS best_trade,
           COALESCE(MIN(pnl), 0) AS worst_trade,
           CASE
               WHEN COUNT(*) > 0 THEN COALESCE(SUM(pnl), 0) / COUNT(*)
               ELSE 0
           END AS avg_pnl
    FROM trade_log
    WHERE trade_date IS NOT NULL
    GROUP BY trade_date::text
) s
WHERE d.summary_date = s.summary_date;
