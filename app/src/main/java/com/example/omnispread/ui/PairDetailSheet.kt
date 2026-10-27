package com.example.omnispread.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.omnispread.data.BacktestRequest
import com.example.omnispread.data.PairResult
import com.example.omnispread.data.TickerInsight
import androidx.compose.ui.platform.LocalUriHandler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.omnispread.ui.theme.AccentBlue
import com.example.omnispread.ui.theme.AccentCyan
import com.example.omnispread.ui.theme.AccentGreen
import com.example.omnispread.ui.theme.AccentRed
import com.example.omnispread.ui.theme.AccentYellow
import com.example.omnispread.ui.theme.BgCard
import com.example.omnispread.ui.theme.BgSecondary
import com.example.omnispread.ui.theme.Border
import com.example.omnispread.ui.theme.TextMuted
import com.example.omnispread.ui.theme.TextPrimary
import com.example.omnispread.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairDetailSheet(
    pair: PairResult,
    insightX: TickerInsight?,
    insightY: TickerInsight?,
    interval: String,
    endDate: String,
    canTrade: Boolean,
    onDismiss: () -> Unit,
    onBacktest: (BacktestRequest) -> Unit,
    onBuildTrade: () -> Unit,
) {
    val isShortSpread = pair.direction in listOf("SHORT_SPREAD", "long_x_short_y")
    val xSym = pair.pair.split("/").getOrElse(0) { pair.x }
    val ySym = pair.pair.split("/").getOrElse(1) { pair.y }
    val signalText  = if (isShortSpread) "Buy $xSym — Sell $ySym" else "Sell $xSym — Buy $ySym"
    val signalColor = if (isShortSpread) AccentRed else AccentGreen

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor   = BgCard,
        dragHandle       = { BottomSheetDefaults.DragHandle(color = Border) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(pair.pair, color = TextPrimary, fontFamily = FontFamily.Monospace,
                        fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "${pair.method} • ${formatHl(pair.half_life, interval)} half-life • ${pair.price_basis} prices • ${if (pair.same_sector == "Yes") "Same industry" else "Cross industry"}",
                        color = TextMuted, fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(pair.combo, color = TextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Close", tint = TextSecondary)
                }
            }

            HorizontalDivider(color = Border.copy(alpha = 0.5f))

            // Stats grid — 2 per row, 4 rows
            val stats = listOf(
                Triple("Z-Score",    "${if (pair.z_score > 0) "+" else ""}${String.format("%.2f", pair.z_score)}",
                    if (pair.z_score > 0) AccentRed else AccentGreen),
                Triple("CADF p-value", String.format("%.4f", pair.cadf_pvalue),
                    if (pair.cadf_pvalue < 0.01) AccentGreen else AccentBlue),
                Triple("Half-Life",  formatHl(pair.half_life, interval), TextPrimary),
                Triple("Johansen",   "rank ${pair.johansen_rank}", AccentCyan),
                Triple("Hedge qty",  String.format("%.4g", pair.qty), TextPrimary),
                Triple("β (${pair.price_basis})", String.format("%.4g", pair.beta), TextSecondary),
                Triple("Exp. Return","${String.format("%.2f", pair.exp_return)}%", AccentYellow),
                Triple("Unit Price", String.format("%.2f", pair.unit_price), TextSecondary),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                stats.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (label, value, color) ->
                            StatCard(label = label, value = value, color = color, modifier = Modifier.weight(1f))
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            // Extreme Z info
            if (pair.extreme_z_detail.isNotEmpty()) {
                Surface(color = BgSecondary, border = BorderStroke(1.dp, Border), shape = MaterialTheme.shapes.small) {
                    Row(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("Ext.Z in HL: ", color = TextSecondary, fontSize = 12.sp)
                        Text(
                            "${pair.extreme_z_in_hl} (${pair.extreme_z_detail})",
                            color      = if (pair.extreme_z_in_hl == "Yes") AccentRed else TextPrimary,
                            fontSize   = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            // Z-Score Chart section
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Z-SCORE HISTORY", color = TextSecondary, fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 0.06.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LegendDot(AccentBlue, "Z-Score")
                    LegendDot(AccentRed.copy(alpha = 0.7f), "+2σ")
                    LegendDot(AccentGreen.copy(alpha = 0.7f), "-2σ")
                }
                Surface(color = BgSecondary, border = BorderStroke(1.dp, Border), shape = MaterialTheme.shapes.medium) {
                    ZScoreChart(dataPoints = pair.historical_z_scores, modifier = Modifier.padding(8.dp))
                }
            }

            // Signal banner
            Surface(
                color  = signalColor.copy(alpha = 0.08f),
                border = BorderStroke(1.dp, signalColor.copy(alpha = 0.3f)),
                shape  = MaterialTheme.shapes.small,
            ) {
                Text(
                    "Signal: $signalText",
                    modifier   = Modifier.fillMaxWidth().padding(12.dp),
                    color      = signalColor,
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign  = TextAlign.Center,
                )
            }

            // Volatility + sentiment per leg
            listOf(pair.x to insightX, pair.y to insightY).forEach { (sym, ins) -> InsightCard(sym, ins) }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RegimeTag(insightX, insightY)
                Text("Rule: both IVR > 70 → credit, both < 30 → debit, otherwise no options.", color = TextMuted, fontSize = 11.sp)
            }

            // Options trade (tastytrade)
            Button(
                onClick  = onBuildTrade,
                enabled  = canTrade,
                modifier = Modifier.fillMaxWidth(),
                colors   = ButtonDefaults.buttonColors(containerColor = AccentBlue, contentColor = TextPrimary),
            ) {
                Text(if (canTrade) "Build options trade" else "Options: connect tastytrade in Settings", fontWeight = FontWeight.SemiBold)
            }

            // Backtest button (only when end date is available)
            if (endDate.isNotEmpty()) {
                Button(
                    onClick = {
                        onBacktest(BacktestRequest(
                            x         = pair.x,
                            y         = pair.y,
                            qty       = pair.qty,
                            direction = pair.direction,
                            interval  = interval,
                            half_life = pair.half_life,
                            end_date  = endDate,
                        ))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = AccentBlue.copy(alpha = 0.2f),
                        contentColor   = AccentCyan,
                    ),
                    border = BorderStroke(1.dp, AccentBlue.copy(alpha = 0.5f)),
                ) {
                    Text("Run Forward Half-Life Backtest (stocks)", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun InsightCard(symbol: String, insight: TickerInsight?) {
    val uri = LocalUriHandler.current
    val fmt = remember { SimpleDateFormat("MMM d", Locale.US) }
    Surface(color = BgSecondary, border = BorderStroke(1.dp, Border), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(symbol, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            val v = insight?.vol
            if (v != null) {
                fun f(x: Double?) = x?.let { String.format("%.1f", it) } ?: "–"
                Text("IV ${f(v.ivIndex)} • IVR ${f(v.ivRank)} • IVP ${f(v.ivPercentile)}", color = TextSecondary, fontSize = 12.sp)
                Text("HV30 ${f(v.hv30)} • HV60 ${f(v.hv60)} • HV90 ${f(v.hv90)} • RV21 ${f(insight.realizedVol21)}", color = TextSecondary, fontSize = 12.sp)
                if (v.expiries.isNotEmpty()) {
                    Text("IV by expiry: " + v.expiries.filter { it.dte in 1..90 }.take(6)
                        .joinToString("  ") { "${it.dte}d ${String.format("%.0f", it.iv)}" },
                        color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                v.nextEarnings?.let { Text("Next earnings: $it", color = AccentYellow, fontSize = 11.sp) }
            } else if (insight?.realizedVol21 != null) {
                Text("RV21 ${String.format("%.1f", insight.realizedVol21)} (connect tastytrade for IV data)", color = TextMuted, fontSize = 12.sp)
            }
            val s = insight?.sentiment
            if (s == null) {
                Text("Reading news…", color = TextMuted, fontSize = 12.sp)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MiniTag(s.label, sentimentColor(s.label))
                    Text("score ${String.format("%+.2f", s.score)} • ${s.positive}↑ ${s.negative}↓ ${s.neutral}• of ${s.headlines.size} headline${if (s.headlines.size == 1) "" else "s"} (last 24h)",
                        color = TextMuted, fontSize = 11.sp)
                }
                s.headlines.take(5).forEach { h ->
                    Text(
                        "${fmt.format(Date(h.published))} · ${h.title} — ${h.source}",
                        color = if (h.score > 0.05) AccentGreen else if (h.score < -0.05) AccentRed else TextSecondary,
                        fontSize = 11.sp, lineHeight = 14.sp,
                        modifier = Modifier.clickable { runCatching { uri.openUri(h.link) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color    = BgSecondary,
        border   = BorderStroke(1.dp, Border),
        shape    = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier              = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalAlignment   = Alignment.CenterHorizontally,
        ) {
            Text(label, color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.05.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                color      = color,
                fontSize   = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                textAlign  = TextAlign.Center,
                lineHeight = 20.sp,
            )
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(modifier = Modifier.size(10.dp, 2.dp), color = color) {}
        Text(label, color = TextMuted, fontSize = 10.sp)
    }
}
