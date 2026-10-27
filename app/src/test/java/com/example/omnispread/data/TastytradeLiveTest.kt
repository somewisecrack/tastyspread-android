package com.example.omnispread.data

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Read-only check against the live tastytrade API. Skipped unless OMNISPREAD_TASTY_ENV points
 * to a KEY=VALUE file with TT_CLIENT_SECRET, TT_REFRESH_TOKEN and optionally TT_ENV=sandbox.
 * Builds an option plan for a sample pair and dry-runs it (dry-runs never place orders).
 */
class TastytradeLiveTest {

    @Test
    fun buildPlanAgainstLiveApi() {
        val path = System.getenv("OMNISPREAD_TASTY_ENV")
        assumeTrue("set OMNISPREAD_TASTY_ENV to run", path != null && File(path).exists())
        val env = File(path!!).readLines().mapNotNull { l ->
            l.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() }
        }.toMap()
        val cfg = TastyConfig(env["TT_CLIENT_SECRET"]!!, env["TT_REFRESH_TOKEN"]!!, sandbox = env["TT_ENV"] == "sandbox")
        val api = TastytradeApi(cfg)

        val x = System.getenv("OMNISPREAD_X") ?: "XOM"
        val y = System.getenv("OMNISPREAD_Y") ?: "CVX"
        val vols = api.marketMetrics(listOf(x, y))
        vols.values.forEach { println("${it.symbol}: IV ${it.ivIndex} IVR ${it.ivRank} HV30 ${it.hv30} earnings ${it.nextEarnings} industry ${it.industry} expiries ${it.expiries.size}") }

        val qty = System.getenv("OMNISPREAD_QTY")?.toDouble() ?: 1.1
        val dir = System.getenv("OMNISPREAD_DIR") ?: "SHORT_SPREAD"
        val budget = System.getenv("OMNISPREAD_BUDGET")?.toDouble() ?: 1000.0
        val pair = PairResult(pair = "$x/$y", x = x, y = y, qty = qty, direction = dir, half_life = 12, z_score = 2.4)
        val plan = OptionStrategy(api, StrategySettings(pairBudget = budget)).plan(pair, vols.mapValues { TickerInsight(it.key, vol = it.value) }, "1h")
        println("regime=${plan.regime} tradeable=${plan.tradeable} ratio=${plan.achievedRatio} err=${plan.ratioError}")
        listOfNotNull(plan.legX, plan.legY).forEach { p ->
            println("${p.underlying} ${p.kind.label} ${p.expiry} (${p.dte}d) spot ${"%.2f".format(p.spot)} x${p.contracts}: " +
                "${p.shortLeg.action} ${p.shortLeg.strike}${p.shortLeg.type} ${p.shortLeg.bid}/${p.shortLeg.ask} Δ${p.shortLeg.delta} | " +
                "${p.longLeg.action} ${p.longLeg.strike}${p.longLeg.type} ${p.longLeg.bid}/${p.longLeg.ask} Δ${p.longLeg.delta} | " +
                "net ${"%.2f".format(p.netPrice)} maxP ${"%.0f".format(p.maxProfit)} maxL ${"%.0f".format(p.maxLoss)} Δ$ ${"%.0f".format(p.deltaDollars)}")
            println("   reason: ${p.reason}")
        }
        plan.notes.forEach { println("note: $it") }

        val account = api.accountNumbers().firstOrNull()
        if (account != null && plan.tradeable) listOfNotNull(plan.legX, plan.legY).forEach { p ->
            val r = api.dryRun(account, api.verticalOrder(p, kotlin.math.abs(p.netPrice), "omnispread-test"), p.underlying)
            println("dry-run ${p.underlying}: ok=${r.ok} bp=${r.buyingPowerEffect} fees=${r.fees} errors=${r.errors} warnings=${r.warnings}")
        }
    }
}
