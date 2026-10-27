package com.example.omnispread.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.omnispread.data.DryRunResult
import com.example.omnispread.data.OptionLeg
import com.example.omnispread.data.SpreadProposal
import com.example.omnispread.ui.theme.AccentBlue
import com.example.omnispread.ui.theme.AccentCyan
import com.example.omnispread.ui.theme.AccentGreen
import com.example.omnispread.ui.theme.AccentRed
import com.example.omnispread.ui.theme.AccentYellow
import com.example.omnispread.ui.theme.BgCard
import com.example.omnispread.ui.theme.BgPrimary
import com.example.omnispread.ui.theme.Border
import com.example.omnispread.ui.theme.TextMuted
import com.example.omnispread.ui.theme.TextPrimary
import com.example.omnispread.ui.theme.TextSecondary
import com.example.omnispread.viewmodel.MainViewModel
import com.example.omnispread.viewmodel.TradeState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TradeScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val state by viewModel.tradeState.collectAsState()
    val config by viewModel.config.collectAsState()
    val budget by viewModel.budget.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    var budgetText by remember(budget) { mutableStateOf(budget.toInt().toString()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Options trade", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextSecondary) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgPrimary),
            )
        },
        containerColor = BgPrimary,
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Budget: max loss for both legs together; more budget → more contracts → closer hedge ratio.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Pair max-loss budget $", color = TextSecondary, fontSize = 13.sp)
                OutlinedTextField(
                    value = budgetText,
                    onValueChange = { budgetText = it.filter { c -> c.isDigit() }.take(7) },
                    singleLine = true,
                    modifier = Modifier.width(110.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                        focusedBorderColor = AccentBlue, unfocusedBorderColor = Border),
                )
                OutlinedButton(
                    onClick = { budgetText.toDoubleOrNull()?.let { viewModel.rebuildTrade(it) } },
                    enabled = state !is TradeState.Loading,
                    border = BorderStroke(1.dp, AccentBlue),
                ) { Text("Rebuild", color = AccentCyan) }
            }

            when (val s = state) {
                is TradeState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = AccentBlue, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp)); Text(s.status, color = TextSecondary, fontSize = 13.sp)
                }
                is TradeState.Error -> Text(s.message, color = AccentRed, fontSize = 13.sp)
                is TradeState.Idle -> Text("Pick a pair and tap “Build options trade”.", color = TextMuted)
                is TradeState.Ready -> {
                    val plan = s.plan
                    Text(plan.pair, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text(
                        (if (plan.direction == "SHORT_SPREAD") "Long ${plan.legX?.underlying ?: "X"} / short ${plan.legY?.underlying ?: "Y"}"
                         else "Short ${plan.legX?.underlying ?: "X"} / long ${plan.legY?.underlying ?: "Y"}") +
                            " • ${if (config.sandbox) "SANDBOX" else "PRODUCTION"} • account ${config.accountNumber.ifBlank { "not set" }}",
                        color = if (config.sandbox) AccentCyan else AccentYellow, fontSize = 12.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        when (plan.regime) {
                            "credit" -> MiniTag("Credit spreads", AccentGreen)
                            "debit" -> MiniTag("Debit spreads", AccentCyan)
                            else -> MiniTag("No options", TextMuted)
                        }
                        if (plan.regime != null) MiniTag(if (plan.tradeable) "Meets rules" else "Doesn't meet rules", if (plan.tradeable) AccentGreen else AccentRed)
                    }
                    plan.achievedRatio?.let { r ->
                        val err = plan.ratioError ?: 0.0
                        Surface(color = BgCard, border = BorderStroke(1.dp, if (plan.tradeable) Border else AccentRed), shape = MaterialTheme.shapes.small) {
                            Text(
                                "Hedge ratio (X shares per Y share): target ${"%.3f".format(plan.targetRatio)} • options give ${"%.3f".format(r)} " +
                                    "(${"%+.1f".format(err * 100)}%, limit ±15%)",
                                color = if (kotlin.math.abs(err) <= 0.15) AccentGreen else AccentRed, fontSize = 12.sp,
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                            )
                        }
                    }
                    listOfNotNull(plan.legX, plan.legY).forEach { LegCard(it) }
                    if (plan.notes.isNotEmpty()) {
                        Surface(color = BgCard, border = BorderStroke(1.dp, Border), shape = MaterialTheme.shapes.small) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                plan.notes.forEach { Text("• $it", color = TextSecondary, fontSize = 12.sp) }
                            }
                        }
                    }

                    if (s.checks.isNotEmpty()) ResultsBox("Dry-run", s.checks)
                    if (s.submitted.isNotEmpty()) ResultsBox("Submitted", s.submitted)

                    val bothLegs = plan.tradeable && plan.legX != null && plan.legY != null
                    val dryOk = s.checks.isNotEmpty() && s.checks.all { it.ok }
                    if (s.busy != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = AccentBlue, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp)); Text(s.busy, color = TextSecondary, fontSize = 13.sp)
                        }
                    } else if (s.submitted.isEmpty() && bothLegs) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { viewModel.dryRunTrade() }, enabled = bothLegs,
                                modifier = Modifier.weight(1f), border = BorderStroke(1.dp, AccentBlue),
                            ) { Text("Dry-run both legs", color = AccentCyan) }
                            Button(
                                onClick = { confirm = true }, enabled = bothLegs && dryOk,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentRed.copy(alpha = 0.9f)),
                            ) { Text("Place orders…", color = TextPrimary, fontWeight = FontWeight.Bold) }
                        }
                        Text(
                            "Orders are separate Day limit orders at the mid shown, one per underlying, so one leg can fill without the other. " +
                                "Placing orders needs a grant with the trade scope.",
                            color = TextMuted, fontSize = 11.sp,
                        )
                    }
                }
            }
            Text("For educational and research purposes only. Not financial advice.", color = TextMuted, fontSize = 11.sp,
                modifier = Modifier.padding(vertical = 16.dp))
        }
    }

    if (confirm) {
        val ready = state as? TradeState.Ready
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = BgCard,
            title = { Text("Place ${if (config.sandbox) "sandbox" else "LIVE"} orders?", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOfNotNull(ready?.plan?.legX, ready?.plan?.legY).forEach {
                        Text("${it.contracts}× ${it.underlying} ${it.kind.label} ${it.expiry} " +
                            "${fmtStrike(it.shortLeg.strike)}/${fmtStrike(it.longLeg.strike)} @ ${String.format("%.2f", kotlin.math.abs(it.netPrice))} " +
                            if (it.kind.isCredit) "credit" else "debit", color = TextSecondary, fontSize = 13.sp)
                    }
                    Text("Max loss ${money(listOfNotNull(ready?.plan?.legX, ready?.plan?.legY).sumOf { it.maxLoss })}.",
                        color = AccentYellow, fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { confirm = false; viewModel.submitTrade() }) { Text("Place orders", color = AccentRed) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel", color = TextSecondary) } },
        )
    }
}

@Composable
private fun LegCard(p: SpreadProposal) {
    Surface(color = BgCard, border = BorderStroke(1.dp, Border), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.underlying, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                MiniTag(p.kind.label, if (p.kind.isCredit) AccentGreen else AccentCyan)
                Spacer(Modifier.width(6.dp))
                MiniTag(if (p.bullish) "bullish" else "bearish", if (p.bullish) AccentGreen else AccentRed)
            }
            Text("${p.expiry} (${p.dte}d) • spot ${String.format("%.2f", p.spot)} • IV ${String.format("%.1f", p.expiryIv)}% • EM ±${String.format("%.2f", p.expectedMove)} • ${p.contracts} contract${if (p.contracts > 1) "s" else ""}",
                color = TextSecondary, fontSize = 12.sp)
            Text(p.reason, color = TextMuted, fontSize = 11.sp)
            HorizontalDivider(color = Border.copy(alpha = 0.5f))
            LegRow(p.shortLeg); LegRow(p.longLeg)
            HorizontalDivider(color = Border.copy(alpha = 0.5f))
            Text(
                "${if (p.kind.isCredit) "Credit" else "Debit"} ${String.format("%.2f", kotlin.math.abs(p.netPrice))} • " +
                    "net Δ ${String.format("%+.3f", p.netDelta)} (${String.format("%.1f", kotlin.math.abs(p.netDelta) * 100)} sh/contract, " +
                    "${String.format("%.0f", kotlin.math.abs(p.netDelta) * 100 * p.contracts)} sh total) • max profit ${money(p.maxProfit)} • max loss ${money(p.maxLoss)}",
                color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            )
            if (p.earningsBeforeExpiry) Text("Earnings ${p.earnings} before expiry", color = AccentYellow, fontSize = 11.sp)
        }
    }
}

@Composable
private fun LegRow(l: OptionLeg) {
    Row(Modifier.fillMaxWidth()) {
        Text("${if (l.action.startsWith("Sell")) "SELL" else "BUY "} ${fmtStrike(l.strike)}${l.type}",
            color = if (l.action.startsWith("Sell")) AccentRed else AccentGreen,
            fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text("${String.format("%.2f", l.bid)} / ${String.format("%.2f", l.ask)}  Δ ${l.delta?.let { String.format("%.2f", it) } ?: "–"}  IV ${l.iv?.let { String.format("%.0f", it) } ?: "–"}",
            color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

@Composable
private fun ResultsBox(title: String, results: List<DryRunResult>) {
    Surface(color = BgCard, border = BorderStroke(1.dp, Border), shape = MaterialTheme.shapes.small) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = AccentCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            results.forEach { r ->
                Text(
                    "${r.underlying.ifBlank { "Order" }}: " + if (r.ok)
                        "OK" + (r.orderId?.let { " • order #$it" } ?: "") +
                            (r.buyingPowerEffect?.let { " • BP $it" } ?: "") + (r.fees?.let { " • fees $it" } ?: "")
                    else r.errors.joinToString("; "),
                    color = if (r.ok) AccentGreen else AccentRed, fontSize = 12.sp,
                )
                r.warnings.forEach { Text("  ⚠ $it", color = AccentYellow, fontSize = 11.sp) }
            }
        }
    }
}

private fun fmtStrike(k: Double) = if (k % 1.0 == 0.0) k.toInt().toString() else String.format("%.2f", k)
private fun money(v: Double) = "$" + String.format("%,.0f", v)
