package com.example.omnispread.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.omnispread.data.OptionStrategy
import com.example.omnispread.data.PairResult
import com.example.omnispread.data.TickerInsight
import com.example.omnispread.ui.theme.AccentBlue
import com.example.omnispread.ui.theme.AccentCyan
import com.example.omnispread.ui.theme.AccentGreen
import com.example.omnispread.ui.theme.AccentPurple
import com.example.omnispread.ui.theme.AccentRed
import com.example.omnispread.ui.theme.AccentYellow
import com.example.omnispread.ui.theme.BgCard
import com.example.omnispread.ui.theme.Border
import com.example.omnispread.ui.theme.TextMuted
import com.example.omnispread.ui.theme.TextPrimary
import com.example.omnispread.ui.theme.TextSecondary

@Composable
fun ResultCard(
    index: Int,
    result: PairResult,
    insightX: TickerInsight?,
    insightY: TickerInsight?,
    interval: String,
    hasEndDate: Boolean,
    onClick: () -> Unit,
    onBacktest: () -> Unit,
) {
    val isShortSpread = result.direction in listOf("SHORT_SPREAD", "long_x_short_y")

    Card(
        onClick  = onClick,
        colors   = CardDefaults.cardColors(containerColor = BgCard),
        border   = BorderStroke(1.dp, Border),
        shape    = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            // Header: index + pair name + method badge
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("$index", color = TextMuted, fontSize = 11.sp, modifier = Modifier.width(24.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        result.pair,
                        color      = TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize   = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    val comboDisplay = if (result.combo.length > 72) result.combo.take(72) + "…" else result.combo
                    Text(comboDisplay, color = TextMuted, fontSize = 10.sp, lineHeight = 13.sp)
                }
                Spacer(Modifier.width(8.dp))
                MethodBadge(result.method)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = Border.copy(alpha = 0.5f))

            // Stats: 2-column layout
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatRow("Z-Score",
                        "${if (result.z_score > 0) "+" else ""}${String.format("%.2f", result.z_score)}",
                        if (result.z_score > 0) AccentRed else AccentGreen)
                    StatRow("Half-Life", formatHl(result.half_life, interval), TextSecondary)
                    StatRow("Hedge qty", String.format("%.3g", result.qty), TextSecondary)
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val pColor = if (result.cadf_pvalue < 0.01) AccentGreen
                    else if (result.cadf_pvalue < 0.03) AccentBlue else AccentYellow
                    StatRow("CADF p", String.format("%.4f", result.cadf_pvalue), pColor)
                    StatRow("Johansen", "rank ${result.johansen_rank}", if (result.johansen_rank == 1) AccentGreen else AccentCyan)
                    StatRow("Exp.Ret", "${String.format("%.2f", result.exp_return)}%", AccentYellow)
                }
            }

            // Per-ticker sentiment + IV rank
            Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TickerStrip(result.x, insightX)
                TickerStrip(result.y, insightY)
            }

            Row(
                verticalAlignment    = Alignment.CenterVertically,
                modifier             = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (result.extreme_z_in_hl == "Yes") MiniTag("Ext.Z", AccentRed)
                if (result.same_sector == "Yes") MiniTag("Same Sector", AccentGreen)
                RegimeTag(insightX, insightY)
                Text(
                    if (isShortSpread) "▲ Short Spread" else "▼ Long Spread",
                    color      = if (isShortSpread) AccentRed else AccentGreen,
                    fontSize   = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick        = onBacktest,
                    enabled        = hasEndDate,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    colors         = ButtonDefaults.outlinedButtonColors(
                        contentColor        = if (hasEndDate) AccentCyan else TextMuted,
                        disabledContentColor = TextMuted,
                    ),
                    border = BorderStroke(1.dp, if (hasEndDate) AccentBlue.copy(alpha = 0.5f) else Border),
                ) {
                    Text("Backtest", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun TickerStrip(symbol: String, insight: TickerInsight?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(symbol, color = TextSecondary, fontSize = 11.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(72.dp))
        val s = insight?.sentiment
        if (s == null) MiniTag("news…", TextMuted)
        else MiniTag(if (s.headlines.isEmpty()) "No news 24h" else "${s.label} ${String.format("%+.2f", s.score)} · ${s.headlines.size}", sentimentColor(s.label))
        insight?.vol?.ivRank?.let { MiniTag("IVR ${it.toInt()}", if (it >= 50) AccentYellow else if (it < 25) AccentCyan else TextSecondary) }
        insight?.vol?.ivIndex?.let { iv ->
            val rv = insight.realizedVol21 ?: insight.vol.hv30
            if (rv != null) MiniTag("IV−RV ${String.format("%+.0f", iv - rv)}", TextSecondary)
        }
    }
}

/** Option rule chip: both IVR > 70 → credit, both < 30 → debit, otherwise none. Hidden when IVR is unknown. */
@Composable
fun RegimeTag(ix: TickerInsight?, iy: TickerInsight?) {
    val a = ix?.vol?.ivRank ?: return
    val b = iy?.vol?.ivRank ?: return
    when (OptionStrategy.regime(a, b).first) {
        "credit" -> MiniTag("Opt: credit", AccentGreen)
        "debit" -> MiniTag("Opt: debit", AccentCyan)
        else -> MiniTag("Opt: none", TextMuted)
    }
}

fun sentimentColor(label: String) = when (label) {
    "Bullish" -> AccentGreen
    "Bearish" -> AccentRed
    "Neutral" -> AccentBlue
    else -> TextMuted
}

@Composable
private fun StatRow(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextMuted, fontSize = 10.sp, modifier = Modifier.width(72.dp))
        Text(value, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun MethodBadge(method: String) {
    val color = when (method) {
        "Both", "CADF+Johansen" -> AccentGreen
        "Johansen" -> AccentPurple
        else       -> AccentBlue
    }
    Surface(color = color.copy(alpha = 0.15f), shape = MaterialTheme.shapes.extraSmall) {
        Box(Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) {
            Text(method, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun MiniTag(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.12f), shape = MaterialTheme.shapes.extraSmall) {
        Box(Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

fun formatHl(hl: Int, interval: String): String = when (interval) {
    "15m" -> "${String.format("%.1f", hl * 15.0 / 60)}h"
    "30m" -> "${String.format("%.1f", hl * 30.0 / 60)}h"
    "1h", "60m" -> "${hl}h"
    else  -> "${hl}d"
}
