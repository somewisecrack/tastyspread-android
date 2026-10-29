package com.example.omnispread.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.omnispread.data.BacktestRequest
import com.example.omnispread.data.CredentialStore
import com.example.omnispread.data.DryRunResult
import com.example.omnispread.data.NEWS_NAMES
import com.example.omnispread.data.NewsSentiment
import com.example.omnispread.data.OmniSpreadEngine
import com.example.omnispread.data.OptionStrategy
import com.example.omnispread.data.PairResult
import com.example.omnispread.data.PairTradePlan
import com.example.omnispread.data.Stats
import com.example.omnispread.data.StrategySettings
import com.example.omnispread.data.TastyConfig
import com.example.omnispread.data.TastytradeApi
import com.example.omnispread.data.TickerInsight
import com.example.omnispread.data.TickerVol
import com.example.omnispread.data.YahooFinanceApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.UUID

sealed interface ScanState {
    object Idle : ScanState
    data class Scanning(val status: String) : ScanState
    data class Success(val results: List<PairResult>, val message: String) : ScanState
    data class Error(val message: String) : ScanState
}

sealed interface TradeState {
    object Idle : TradeState
    data class Loading(val status: String) : TradeState
    data class Ready(
        val plan: PairTradePlan,
        val checks: List<DryRunResult> = emptyList(),
        val submitted: List<DryRunResult> = emptyList(),
        val busy: String? = null,
    ) : TradeState
    data class Error(val message: String) : TradeState
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val store = CredentialStore(application)

    private val _config = MutableStateFlow(store.load())
    val config: StateFlow<TastyConfig> = _config.asStateFlow()

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private val _insights = MutableStateFlow<Map<String, TickerInsight>>(emptyMap())
    val insights: StateFlow<Map<String, TickerInsight>> = _insights.asStateFlow()

    private val _enrichStatus = MutableStateFlow<String?>(null)
    val enrichStatus: StateFlow<String?> = _enrichStatus.asStateFlow()

    private val _selectedPair = MutableStateFlow<PairResult?>(null)
    val selectedPair: StateFlow<PairResult?> = _selectedPair.asStateFlow()

    private val _tradeState = MutableStateFlow<TradeState>(TradeState.Idle)
    val tradeState: StateFlow<TradeState> = _tradeState.asStateFlow()

    private val _pendingBacktest = MutableStateFlow<BacktestRequest?>(null)
    val pendingBacktest: StateFlow<BacktestRequest?> = _pendingBacktest.asStateFlow()

    private val _currentInterval = MutableStateFlow("1d")
    val currentInterval: StateFlow<String> = _currentInterval.asStateFlow()

    private val _currentEndDate = MutableStateFlow("")
    val currentEndDate: StateFlow<String> = _currentEndDate.asStateFlow()

    private var closes: Map<String, DoubleArray> = emptyMap()
    private var api: TastytradeApi? = null

    private fun api(): TastytradeApi? {
        val cfg = _config.value
        if (!cfg.isConfigured) return null
        return api ?: TastytradeApi(cfg).also { api = it }
    }

    fun selectPair(pair: PairResult?) { _selectedPair.value = pair }
    fun setPendingBacktest(request: BacktestRequest?) { _pendingBacktest.value = request }

    fun reset() {
        _scanState.value = ScanState.Idle
        _selectedPair.value = null
        _insights.value = emptyMap()
        _enrichStatus.value = null
        _currentInterval.value = "1d"
        _currentEndDate.value = ""
        closes = emptyMap()
    }

    // ── tastytrade settings ─────────────────────────────────────────────────

    fun saveConfig(cfg: TastyConfig) {
        store.save(cfg)
        _config.value = cfg
        api = null
    }

    fun clearConfig() {
        store.clear()
        _config.value = TastyConfig()
        api = null
    }

    /** Verifies credentials and returns the account numbers (or an error message). */
    suspend fun testConnection(cfg: TastyConfig): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching { TastytradeApi(cfg).accountNumbers() }
    }

    // ── scan ────────────────────────────────────────────────────────────────

    fun startScan(
        tickers: List<String>,
        period: String,
        interval: String = "1d",
        startDate: String? = null,
        endDate: String? = null,
        priceBasis: String = "raw",
    ) {
        _currentInterval.value = interval
        _currentEndDate.value = endDate ?: ""
        _insights.value = emptyMap()
        _enrichStatus.value = null
        _scanState.value = ScanState.Scanning("Starting scan...")

        viewModelScope.launch {
            try {
                // Industry / IV data for the whole universe first (one call per 50 symbols),
                // so the scan can flag same-industry pairs.
                val vols: Map<String, TickerVol> = api()?.let { a ->
                    _scanState.value = ScanState.Scanning("Loading tastytrade metrics...")
                    withContext(Dispatchers.IO) { runCatching { a.marketMetrics(tickers) }.getOrElse { emptyMap() } }
                } ?: emptyMap()
                if (vols.isNotEmpty()) _insights.value = vols.mapValues { TickerInsight(it.key, vol = it.value) }

                val output = withContext(Dispatchers.IO) {
                    OmniSpreadEngine(
                        tickers = tickers, period = period, interval = interval,
                        startDate = startDate, endDate = endDate, priceBasis = priceBasis,
                        industryMap = vols.mapNotNull { (k, v) -> v.industry?.let { k to it } }.toMap(),
                        onProgress = { _scanState.value = ScanState.Scanning(it) },
                    ).runScan()
                }
                closes = output.closes
                val results = output.results
                val msg = if (results.isEmpty()) "No pairs passed CADF + Johansen with |z| > 2"
                          else "Found ${results.size} cointegrated pair${if (results.size > 1) "s" else ""}"
                _scanState.value = ScanState.Success(results, msg)
                enrich(results.flatMap { listOf(it.x, it.y) }.distinct(), interval)
            } catch (e: Exception) {
                _scanState.value = ScanState.Error(e.message ?: "An error occurred")
            }
        }
    }

    /** News sentiment (all tickers) + realised vol for every ticker that appears in a result. */
    private suspend fun enrich(symbols: List<String>, interval: String) {
        if (symbols.isEmpty()) return
        val gate = Semaphore(4)
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        _enrichStatus.value = "Reading news for ${symbols.size} tickers..."
        withContext(Dispatchers.IO) {
            symbols.map { sym ->
                async {
                    gate.withPermit {
                        val name = if (sym in NEWS_NAMES) null else YahooFinanceApi.fetchName(sym)
                        val sentiment = runCatching { NewsSentiment.analyze(sym, name) }.getOrNull()
                        val rv = if (interval == "1d") closes[sym]?.let { Stats.realizedVolPct(it, 21) }?.takeIf { it.isFinite() } else null
                        _insights.update { m ->
                            val cur = m[sym] ?: TickerInsight(sym)
                            m + (sym to cur.copy(sentiment = sentiment, realizedVol21 = rv))
                        }
                        _enrichStatus.value = "Reading news... ${done.incrementAndGet()}/${symbols.size}"
                    }
                }
            }.awaitAll()
        }
        _enrichStatus.value = null
    }

    // ── options trade builder ───────────────────────────────────────────────

    private val uiPrefs = application.getSharedPreferences("tastyspread_ui", android.content.Context.MODE_PRIVATE)
    private val _budget = MutableStateFlow(uiPrefs.getFloat("pair_budget", 1000f).toDouble())
    /** Max loss for both legs of a pair, $. */
    val budget: StateFlow<Double> = _budget.asStateFlow()
    private var lastTradePair: PairResult? = null

    fun rebuildTrade(newBudget: Double) {
        _budget.value = newBudget.coerceIn(100.0, 1_000_000.0)
        uiPrefs.edit().putFloat("pair_budget", _budget.value.toFloat()).apply()
        lastTradePair?.let { buildTrade(it) }
    }

    fun buildTrade(pair: PairResult) {
        lastTradePair = pair
        val a = api() ?: run { _tradeState.value = TradeState.Error("Connect tastytrade in Settings first."); return }
        _tradeState.value = TradeState.Loading("Loading option chains for ${pair.x} and ${pair.y}...")
        viewModelScope.launch {
            _tradeState.value = try {
                val plan = withContext(Dispatchers.IO) {
                    // Scan may have run before tastytrade was connected — fetch the two legs now.
                    val missing = listOf(pair.x, pair.y).filter { _insights.value[it]?.vol == null }
                    if (missing.isNotEmpty()) runCatching { a.marketMetrics(missing) }.getOrNull()?.let { vols ->
                        _insights.update { m ->
                            m + vols.mapValues { (k, v) -> (m[k] ?: TickerInsight(k)).copy(vol = v) }
                        }
                    }
                    OptionStrategy(a, StrategySettings(pairBudget = _budget.value)).plan(pair, _insights.value, _currentInterval.value)
                }
                TradeState.Ready(plan)
            } catch (e: Exception) {
                TradeState.Error(e.message ?: "Could not build the trade")
            }
        }
    }

    fun clearTrade() { _tradeState.value = TradeState.Idle }

    /** Dry-run both legs (no order is placed). */
    fun dryRunTrade() = sendOrders(live = false)

    /** Places both legs as live limit orders at mid. Only call after an explicit user confirmation. */
    fun submitTrade() = sendOrders(live = true)

    private fun sendOrders(live: Boolean) {
        val ready = _tradeState.value as? TradeState.Ready ?: return
        val a = api() ?: return
        if (!ready.plan.tradeable) return
        // Live orders only after both legs passed a dry-run.
        if (live && (ready.checks.isEmpty() || ready.checks.any { !it.ok })) return
        val account = _config.value.accountNumber
        if (account.isBlank()) {
            _tradeState.value = ready.copy(checks = listOf(DryRunResult("", false, null, null, emptyList(), listOf("Pick an account in Settings."))))
            return
        }
        _tradeState.value = ready.copy(busy = if (live) "Submitting orders..." else "Running dry-run...")
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                listOfNotNull(ready.plan.legX, ready.plan.legY).map { leg ->
                    val price = kotlin.math.abs(leg.netPrice)
                    val order = a.verticalOrder(leg, price, "tastyspread-${UUID.randomUUID().toString().take(12)}")
                    if (live) a.submit(account, order, leg.underlying) else a.dryRun(account, order, leg.underlying)
                }
            }
            _tradeState.value = if (live) ready.copy(submitted = results, busy = null)
                                else ready.copy(checks = results, busy = null)
        }
    }
}
