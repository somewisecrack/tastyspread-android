package com.example.omnispread.data

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Tunables for the option rules (same defaults as the TastySpread desktop app). */
data class StrategySettings(
    val creditIvr: Double = 60.0,
    val debitIvr: Double = 40.0,
    val horizonMultiple: Double = 1.5,
    val minDte: Int = 7,
    val maxDte: Int = 60,
    val creditShortEm: Double = 1.0,
    val creditLongEm: Double = 1.5,
    val debitShortEm: Double = 1.0,
    val ratioTolerance: Double = 0.15,   // ±15 % on the delta ratio
    val pairBudget: Double = 1000.0,     // max loss for both legs together, $
    val maxContracts: Int = 30,
)

/**
 * Rules-based option spreads for a cointegrated pair:
 *  1. Regime (both legs must agree): both IVR > 60 → credit spreads; both IVR < 40 → debit
 *     spreads; anything else → no options.
 *  2. One expiry for both legs: the first date listed for both underlyings with
 *     DTE ≥ half-life × 1.5 (trading → calendar days), within [minDte, maxDte].
 *  3. Strikes from each leg's expected move EM = spot × IV(expiry) × √(DTE/365):
 *     credit: sell ~1.0 EM OTM, buy ~1.5 EM OTM; debit: buy nearest spot, sell ~1.0 EM.
 *     The long leg of the pair gets a bullish spread, the short leg a bearish one.
 *  4. Contracts so that (nX × |ΔX|) / (nY × |ΔY|) = hedge qty (X shares per Y share),
 *     within ±tolerance and the pair budget; otherwise no trade.
 */
class OptionStrategy(
    private val api: TastytradeApi,
    private val s: StrategySettings = StrategySettings(),
) {

    class PlanException(message: String) : Exception(message)

    companion object {
        /** "credit" | "debit" | null, with a reason. */
        fun regime(ivrX: Double?, ivrY: Double?, s: StrategySettings = StrategySettings()): Pair<String?, String> {
            if (ivrX == null || ivrY == null) return null to "IV rank unavailable — connect tastytrade."
            val txt = "IVR ${"%.0f".format(ivrX)} / ${"%.0f".format(ivrY)}"
            return when {
                ivrX > s.creditIvr && ivrY > s.creditIvr -> "credit" to "$txt: both > ${s.creditIvr.toInt()} → credit spreads."
                ivrX < s.debitIvr && ivrY < s.debitIvr -> "debit" to "$txt: both < ${s.debitIvr.toInt()} → debit spreads."
                else -> null to "$txt: options need both > ${s.creditIvr.toInt()} (credit) or both < ${s.debitIvr.toInt()} (debit)."
            }
        }

        data class Sized(val nX: Int, val nY: Int, val ratio: Double, val error: Double)

        /** Contracts with (nX·dx)/(nY·dy) closest to qty within budget; ties → larger size. dx, dy in shares/contract. */
        fun sizeToRatio(qty: Double, dx: Double, dy: Double, lossX: Double, lossY: Double, s: StrategySettings): Sized? {
            var best: Sized? = null
            var bestErr = Double.POSITIVE_INFINITY
            var bestRisk = -1.0
            for (nX in 1..s.maxContracts) for (nY in 1..s.maxContracts) {
                val risk = nX * lossX + nY * lossY
                if (risk > s.pairBudget) continue
                val ratio = (nX * dx) / (nY * dy)
                val err = abs(ln(ratio / qty))
                if (err < bestErr - 1e-9 || (abs(err - bestErr) <= 1e-9 && risk > bestRisk)) {
                    best = Sized(nX, nY, ratio, ratio / qty - 1); bestErr = err; bestRisk = risk
                }
            }
            return best
        }

        fun halfLifeDays(hl: Int, interval: String): Double = when (interval) {
            "15m" -> hl * 15.0 / 390
            "30m" -> hl * 30.0 / 390
            "1h", "60m" -> hl * 60.0 / 390
            else -> hl.toDouble()
        }
    }

    fun plan(pair: PairResult, insights: Map<String, TickerInsight>, interval: String): PairTradePlan {
        val notes = mutableListOf<String>()
        val vx = insights[pair.x]?.vol
        val vy = insights[pair.y]?.vol
        fun result(regime: String?, lx: SpreadProposal? = null, ly: SpreadProposal? = null, tradeable: Boolean = false,
                   ratio: Double? = null, err: Double? = null) =
            PairTradePlan(pair.pair, pair.direction, regime, lx, ly, notes, tradeable, pair.qty, ratio, err, s.pairBudget)

        val (mode, why) = regime(vx?.ivRank, vy?.ivRank, s)
        notes += why
        if (mode == null) return result(null)

        // One expiry for both legs
        val hlDays = halfLifeDays(pair.half_life, interval)
        val target = ceil(hlDays * s.horizonMultiple * 365.0 / 252.0).toInt().coerceIn(s.minDte, s.maxDte)
        val ex = api.nestedChain(pair.x).associateBy { it.date }
        val ey = api.nestedChain(pair.y).associateBy { it.date }
        val date = ex.keys.intersect(ey.keys).sorted().firstOrNull { ex.getValue(it).dte in target..s.maxDte }
        if (date == null) {
            notes += "No expiry listed for both ${pair.x} and ${pair.y} between $target and ${s.maxDte} days."
            return result(mode)
        }
        notes += "Half-life ≈ ${"%.1f".format(hlDays)} trading days → target ≥ $target days → expiry $date (${ex.getValue(date).dte}d)."

        val bullX = pair.direction == "SHORT_SPREAD"   // SHORT_SPREAD = buy X, sell Y
        val lx: SpreadProposal
        val ly: SpreadProposal
        try {
            lx = leg(pair.x, ex.getValue(date), vx, bullX, mode)
            ly = leg(pair.y, ey.getValue(date), vy, !bullX, mode)
        } catch (e: PlanException) {
            notes += e.message ?: "Could not build a leg."
            return result(mode)
        }

        val dx = abs(lx.netDelta) * 100
        val dy = abs(ly.netDelta) * 100
        if (dx < 1e-6 || dy < 1e-6) {
            notes += "Option deltas unavailable — can't size to the hedge ratio."
            return result(mode, lx, ly)
        }
        val sized = sizeToRatio(pair.qty, dx, dy, lx.maxLoss, ly.maxLoss, s)
        if (sized == null) {
            notes += "One contract per leg (\$${"%,.0f".format(lx.maxLoss + ly.maxLoss)} max loss) exceeds the \$${"%,.0f".format(s.pairBudget)} pair budget."
            return result(mode, lx, ly)
        }
        val sx = scaled(lx, sized.nX)
        val sy = scaled(ly, sized.nY)
        notes += "Delta ratio ${sized.nX}×${"%.1f".format(dx)} : ${sized.nY}×${"%.1f".format(dy)} shares = ${"%.3f".format(sized.ratio)} " +
            "vs hedge qty ${"%.3f".format(pair.qty)} (${"%+.1f".format(sized.error * 100)}%)."
        if (abs(sized.error) > s.ratioTolerance) {
            notes += "Ratio is outside ±${(s.ratioTolerance * 100).toInt()}% within the \$${"%,.0f".format(s.pairBudget)} budget — not tradeable."
            return result(mode, sx, sy, false, sized.ratio, sized.error)
        }
        listOf(sx, sy).filter { it.earningsBeforeExpiry }.forEach { notes += "${it.underlying}: earnings on ${it.earnings} fall before expiry." }
        return result(mode, sx, sy, true, sized.ratio, sized.error)
    }

    private fun leg(sym: String, expiry: ChainExpiry, vol: TickerVol?, bullish: Boolean, mode: String): SpreadProposal {
        val spot = api.equityPrice(sym) ?: throw PlanException("$sym: no underlying price.")
        val iv = vol?.expiries?.firstOrNull { it.date == expiry.date }?.iv ?: vol?.ivIndex
            ?: throw PlanException("$sym: no implied volatility for ${expiry.date}.")
        val em = spot * iv / 100.0 * sqrt(expiry.dte / 365.0)
        val credit = mode == "credit"
        val kind = when {
            credit && bullish -> SpreadKind.BULL_PUT_CREDIT
            credit -> SpreadKind.BEAR_CALL_CREDIT
            bullish -> SpreadKind.BULL_CALL_DEBIT
            else -> SpreadKind.BEAR_PUT_DEBIT
        }
        val usePuts = kind == SpreadKind.BULL_PUT_CREDIT || kind == SpreadKind.BEAR_PUT_DEBIT
        val sign = if (usePuts) -1.0 else 1.0
        val strikes = expiry.strikes.map { it.strike }.sorted()
        val byStrike = expiry.strikes.associateBy { it.strike }
        fun nearest(target: Double, ok: (Double) -> Boolean = { true }): Double =
            strikes.filter(ok).minByOrNull { abs(it - target) } ?: throw PlanException("$sym: no strike near ${"%.2f".format(target)}.")

        val shortK: Double
        val longK: Double
        if (credit) {
            shortK = nearest(spot + sign * s.creditShortEm * em) { if (usePuts) it < spot else it > spot }
            longK = nearest(spot + sign * s.creditLongEm * em) { if (usePuts) it < shortK else it > shortK }
        } else {
            longK = nearest(spot)
            shortK = nearest(spot + sign * s.debitShortEm * em) { if (usePuts) it < longK else it > longK }
        }
        val syms = listOf(shortK, longK).map { k -> byStrike.getValue(k).let { if (usePuts) it.put else it.call } }
        val q = api.optionQuotes(syms)
        if (syms.any { it !in q }) throw PlanException("$sym: no quotes for the chosen strikes.")
        fun mk(k: Double, osym: String, action: String) = q.getValue(osym).let {
            OptionLeg(osym, k, if (usePuts) "P" else "C", action, it.bid, it.ask, it.mid, it.delta, it.iv)
        }
        val short = mk(shortK, syms[0], "Sell to Open")
        val long = mk(longK, syms[1], "Buy to Open")
        val width = abs(shortK - longK)
        val net = if (kind.isCredit) short.mid - long.mid else long.mid - short.mid
        if (net <= 0 || net >= width) throw PlanException("$sym: ${kind.label.lowercase()} ${fmtK(shortK)}/${fmtK(longK)} has no sensible price (net ${"%.2f".format(net)}).")
        val netDelta = (long.delta ?: 0.0) - (short.delta ?: 0.0)
        val earnings = vol?.nextEarnings
        val today = java.time.LocalDate.now(java.time.ZoneId.of("America/New_York")).toString()
        return SpreadProposal(
            underlying = sym, spot = spot, bullish = bullish, kind = kind,
            expiry = expiry.date, dte = expiry.dte, expiryIv = iv, expectedMove = em, ivRank = vol?.ivRank,
            reason = "EM ±${"%.2f".format(em)} (${"%.1f".format(iv)}% IV, ${expiry.dte}d). " + if (credit)
                "Sell ${fmtK(shortK)} (~${fmtK(s.creditShortEm)} EM), buy ${fmtK(longK)} (~${fmtK(s.creditLongEm)} EM)."
            else "Buy ${fmtK(longK)} (ATM), sell ${fmtK(shortK)} (~${fmtK(s.debitShortEm)} EM).",
            shortLeg = short, longLeg = long, contracts = 1,
            netPrice = if (kind.isCredit) net else -net,
            maxProfit = (if (kind.isCredit) net else width - net) * 100,
            maxLoss = (if (kind.isCredit) width - net else net) * 100,
            netDelta = netDelta, deltaDollars = netDelta * spot * 100,
            earnings = earnings,
            earningsBeforeExpiry = earnings != null && earnings >= today && earnings <= expiry.date,
        )
    }

    private fun scaled(p: SpreadProposal, n: Int): SpreadProposal {
        val k = n.toDouble() / p.contracts
        return p.copy(contracts = n, maxProfit = p.maxProfit * k, maxLoss = p.maxLoss * k, deltaDollars = p.deltaDollars * k)
    }

    private fun fmtK(k: Double) = if (k % 1.0 == 0.0) k.toInt().toString() else "%.2f".format(k).trimEnd('0').trimEnd('.')
}
