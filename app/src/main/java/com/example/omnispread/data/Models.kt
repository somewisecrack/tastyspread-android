package com.example.omnispread.data

data class PairResult(
    val pair: String = "",
    val x: String = "",
    val y: String = "",
    /** X shares per one Y share. */
    val qty: Double = 0.0,
    /** Static Johansen hedge coefficient on the chosen price basis. */
    val beta: Double = 0.0,
    val direction: String = "",
    val combo: String = "",
    val method: String = "",
    val engine_version: String = "v2",
    val price_basis: String = "raw",
    val cadf_pvalue: Double = 1.0,
    val johansen_rank: Int = 0,
    val price_corr: Double = 0.0,
    val z_score: Double = 0.0,
    val half_life: Int = 0,
    val move_to_mean: Double = 0.0,
    val exp_return: Double = 0.0,
    val unit_price: Double = 0.0,
    val same_sector: String = "",
    val industry_x: String = "Unknown",
    val industry_y: String = "Unknown",
    val extreme_z_in_hl: String = "",
    val extreme_z_detail: String = "",
    val profitable_since_extreme: String = "",
    val pnl_since_extreme: Double = 0.0,
    val historical_z_scores: List<HistoricalZScore> = emptyList(),
)

data class HistoricalZScore(
    val time: Long = 0L,
    val value: Double = 0.0,
)

// ── Per-ticker enrichment (tastytrade volatility + news sentiment) ──────────

data class ExpiryVol(
    val date: String,          // yyyy-MM-dd
    val dte: Int,
    val iv: Double,            // annualised, percent
)

data class TickerVol(
    val symbol: String,
    val ivIndex: Double? = null,      // percent
    val ivRank: Double? = null,       // percent (0–100)
    val ivPercentile: Double? = null, // percent (0–100)
    val hv30: Double? = null,
    val hv60: Double? = null,
    val hv90: Double? = null,
    val liquidityRating: Int? = null,
    val industry: String? = null,
    val sector: String? = null,
    val nextEarnings: String? = null, // yyyy-MM-dd
    val expiries: List<ExpiryVol> = emptyList(),
)

data class Headline(
    val title: String,
    val source: String,
    val published: Long,   // epoch millis
    val link: String,
    val score: Double,     // -1 … +1
)

data class TickerSentiment(
    val symbol: String,
    val query: String,
    val score: Double,          // mean headline score, -1 … +1
    val label: String,          // Bullish / Bearish / Neutral / No news
    val positive: Int,
    val negative: Int,
    val neutral: Int,
    val headlines: List<Headline>,
)

data class TickerInsight(
    val symbol: String,
    val vol: TickerVol? = null,
    val sentiment: TickerSentiment? = null,
    val realizedVol21: Double? = null,   // percent, from scan prices
    val error: String? = null,
)

// ── Option-spread proposal ───────────────────────────────────────────────

enum class SpreadKind(val label: String, val isCredit: Boolean, val bullish: Boolean) {
    BULL_PUT_CREDIT("Bull put credit", true, true),
    BEAR_CALL_CREDIT("Bear call credit", true, false),
    BULL_CALL_DEBIT("Bull call debit", false, true),
    BEAR_PUT_DEBIT("Bear put debit", false, false),
}

data class OptionLeg(
    val symbol: String,        // OCC symbol, e.g. "AAPL  261016C00330000"
    val strike: Double,
    val type: String,          // "C" / "P"
    val action: String,        // "Sell to Open" / "Buy to Open"
    val bid: Double,
    val ask: Double,
    val mid: Double,
    val delta: Double?,
    val iv: Double?,           // percent
)

data class SpreadProposal(
    val underlying: String,
    val spot: Double,
    val bullish: Boolean,
    val kind: SpreadKind,
    val expiry: String,
    val dte: Int,
    val expiryIv: Double,          // percent, IV of this expiry
    val expectedMove: Double,      // spot × IV × √(DTE/365)
    val ivRank: Double?,
    val reason: String,
    val shortLeg: OptionLeg,
    val longLeg: OptionLeg,
    val contracts: Int,
    val netPrice: Double,          // per-share mid credit (+) or debit (−) of one spread
    val maxProfit: Double,         // dollars for all contracts
    val maxLoss: Double,           // dollars for all contracts
    val netDelta: Double,          // per contract, Δlong − Δshort
    val deltaDollars: Double,      // netDelta × spot × 100 × contracts
    val earnings: String?,
    val earningsBeforeExpiry: Boolean,
)

/** "credit" | "debit" | null (no options). */
data class PairTradePlan(
    val pair: String,
    val direction: String,
    val regime: String?,
    val legX: SpreadProposal?,
    val legY: SpreadProposal?,
    val notes: List<String>,
    val tradeable: Boolean,
    val targetRatio: Double,
    val achievedRatio: Double? = null,
    val ratioError: Double? = null,
    val budget: Double,
)

data class DryRunResult(
    val underlying: String,
    val ok: Boolean,
    val buyingPowerEffect: String?,
    val fees: String?,
    val warnings: List<String>,
    val errors: List<String>,
    val orderId: String? = null,   // set only after a live submit
)

// ── Backtest (unchanged) ────────────────────────────────────────────────

data class BacktestRequest(
    val x: String,
    val y: String,
    val qty: Double,
    val direction: String,
    val interval: String = "1d",
    val half_life: Int,
    val end_date: String,
)

data class BacktestPoint(
    val time: Long = 0L,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val spread: Double = 0.0,
    val pnl_pct: Double = 0.0,
)

data class BacktestResult(
    val status: String = "",
    val pair: String? = null,
    val x: String? = null,
    val y: String? = null,
    val qty: Double? = null,
    val direction: String? = null,
    val interval: String? = null,
    val half_life: Int? = null,
    val entry_time: Long? = null,
    val exit_time: Long? = null,
    val final_pnl_pct: Double? = null,
    val max_profit_pct: Double? = null,
    val points: List<BacktestPoint>? = null,
    val note: String? = null,
    val error: String? = null,
)
