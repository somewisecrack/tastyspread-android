# TastySpread (Android)

**Statistical pairs-trading scanner for S&P 100 stocks (the Android version of [TastySpread](https://github.com/somewisecrack/tastyspread)), with tastytrade option spreads and news sentiment — runs on-device, no server required.**

TastySpread screens every pair in a universe for cointegration using the same **v2** method as the [OmniSpread Python backend](https://github.com/somewisecrack/OmniSpread), adds per-ticker **news sentiment** (TickerVibe-style) and **tastytrade volatility data**, and turns a signal into a pair of **credit or debit vertical spreads** that you can dry-run and place from the phone.

---

## Features

| Feature | Details |
|---|---|
| **v2 cointegration screen** | Johansen (`det_order=0`, `k_ar_diff=1`) must reject rank 0, **and** Engle–Granger in both orderings (larger p-value) must be **< 0.10** (OmniSpread uses 0.05). Static Johansen hedge, raw or log prices. |
| **Parity with Python** | ADF with AIC lag selection, MacKinnon p-values and Johansen are ported from statsmodels; unit tests check them against statsmodels on synthetic and real data. |
| **Universe** | The S&P 100 (101 tickers, 5,050 pairs), or one of its GICS sectors. Downloads and pair screening run in parallel. |
| **News sentiment per ticker** | Google News headlines from the **last 24 hours** that name the company (curated names for every S&P 100 ticker), scored with an on-device finance lexicon and recency-weighted; each result shows the label, score and headline count. |
| **tastytrade volatility** | IV index, IV rank and percentile, HV 30/60/90, **IV for every expiry**, next earnings date and industry (used for the same-industry flag). |
| **Option spreads (rules-based)** | Both IVR > 60 → credit spreads, both < 40 → debit spreads, otherwise none; one expiry from the half-life; strikes from the expected move; contracts sized so net deltas are in the hedge ratio (±15 %). |
| **Order flow** | Dry-run both legs (buying-power effect, fees, warnings), then place two Day limit orders at mid after an explicit confirmation. Sandbox or production. |
| **Z-score chart & stock backtest** | As before. |

---

## How it works

### 1. Scan
1. Prices: Yahoo Finance adjusted closes (tastytrade has no REST price history). Berkshire is `BRK-B` on Yahoo and `BRK/B` on tastytrade; the app maps between them.
2. If tastytrade is connected, `GET /market-metrics` is loaded for the universe first (industry, IV, earnings).
3. For each pair: Johansen rank ≥ 1 → CADF p < 0.10 → β > 0 from the Johansen vector → spread `Y − β·X` → half-life → z over a half-life window → keep if |z| > 2.
4. Results are ranked by CADF p-value, then |z|.

`qty` is always X shares per one Y share. `SHORT_SPREAD` = buy X / sell Y, `LONG_SPREAD` = sell X / buy Y.

### 2. Sentiment
For every ticker in a result: curated company name(s) → Google News RSS query `"<name>" (stock OR shares OR earnings OR analyst OR price OR investors) when:1d` → keep headlines from the last 24 h whose title names the company → each scored −1…+1 → recency-weighted mean (8-hour half-life). Bullish > +0.15, Bearish < −0.15. The **Sentiment Δ** sort ranks pairs where the news favours the signal (long-leg score minus short-leg score).

TickerVibe uses FinBERT; that model is ~440 MB, so the app uses a compact lexicon with negation handling instead. Treat the score as a rough news-tone flag.

### 3. Options plan

| Step | Rule |
|---|---|
| Credit, debit or none | Both legs must agree: **both IVR > 60 → credit spreads**, **both IVR < 40 → debit spreads**, anything else → no options for the pair |
| Expiry | **One date for both legs**: the first expiry listed for both underlyings with DTE ≥ half-life × 1.5 (trading → calendar days), at least 7 and at most 180 days |
| Expected move | EM = spot × IV(that expiry) × √(DTE/365), per leg |
| Credit strikes | Sell ~1.0 EM OTM, buy ~1.5 EM OTM (bull put on the long leg, bear call on the short leg) |
| Debit strikes | Buy the strike nearest spot, sell ~1.0 EM in the favourable direction (bull call on the long leg, bear put on the short leg) |
| Sizing | Contracts so that (nX × \|net ΔX\|) / (nY × \|net ΔY\|) = hedge qty (X shares per Y share); must be within ±15 % and the pair's max-loss budget (default $1,000, adjustable in the trade screen), otherwise no trade |

Quotes and Greeks come from `GET /market-data/by-type`.

### 4. Orders
Each leg is a separate 2-leg limit order (`POST /accounts/{acct}/orders/dry-run`, then `/orders`) with a unique `external-identifier`. The legs are **not** linked, so one can fill without the other. The **Place orders** button is enabled only after both legs pass a dry-run.

---

## tastytrade setup

1. my.tastytrade.com → **Manage → My Profile → API → OAuth Applications** → create an app (any redirect URI, e.g. `https://localhost`).
2. **Manage → Create Grant** → copy the refresh token. Copy the client secret when the app is created (it is shown once).
3. In the app: ⚙ → paste both → **Test connection** → pick the account → **Save**.

Use the **read** scope for scanning and volatility data. Dry-runs and orders need the **trade** scope; with a read-only grant they fail with "insufficient scopes". Secrets are encrypted with an Android Keystore key and sent only to tastytrade's OAuth endpoint. App backup is disabled.

---

## Build

```bash
./gradlew assembleDebug
./gradlew installDebug
```

Requires Android Studio (or its bundled JDK) and Android SDK 35.

### Tests

```bash
./gradlew testDebugUnitTest
```

- `StatsParityTest` checks CADF p-values, Johansen statistics, rank, β, half-life and z against statsmodels 0.14.6 on 8 synthetic series and 91 real pairs × 2 price bases (fixtures in `app/src/test/resources`).
- `Sp100ScanLiveTest` (skipped by default) scans the full S&P 100 from Yahoo and writes the passing pairs: `OMNISPREAD_SCAN_OUT=/tmp/sp100.tsv ./gradlew testDebugUnitTest --tests '*Sp100ScanLiveTest*' --rerun`. On 2026-10-09 (3y, raw) it matched the Python backend exactly (COP/MRK, GS/MO).
- `TastytradeLiveTest` (read-only, skipped by default) builds a live plan and dry-runs it:
  `OMNISPREAD_TASTY_ENV=/path/to/.env ./gradlew testDebugUnitTest --tests '*TastytradeLiveTest*' -i`, where the file holds `TT_CLIENT_SECRET=`, `TT_REFRESH_TOKEN=` and optionally `TT_ENV=sandbox`.

---

## Project structure

```
app/src/main/java/com/example/omnispread/
├── data/
│   ├── Stats.kt            # ADF/AIC, MacKinnon p-values, Johansen (statsmodels port)
│   ├── OmniSpreadEngine.kt # v2 scan
│   ├── Presets.kt          # S&P 100 constituents + sectors
│   ├── TastytradeApi.kt    # OAuth, market metrics, chains, quotes, dry-run/orders
│   ├── CredentialStore.kt  # Keystore-encrypted credentials
│   ├── OptionStrategy.kt   # signal → credit/debit verticals, sizing
│   ├── NewsSentiment.kt    # Google News RSS + finance lexicon
│   ├── YahooFinanceApi.kt  # prices + company names
│   ├── BacktestEngine.kt   # forward stock backtest
│   └── Models.kt
├── viewmodel/              # scan, enrichment, trade state
└── ui/                     # scan form, results, pair sheet, trade screen, settings
```

---

## Data sources and limits

- **Yahoo Finance** chart API is unofficial and can change.
- **Google News RSS** is for personal, non-commercial use under Google's feed terms.
- **tastytrade** REST quotes are for funded accounts; sandbox quotes are delayed.
- The S&P 100 list is a snapshot (October 2026) in `Presets.kt`; update it when constituents change.
- GOOG/GOOGL are two share classes of one company and will usually pair with each other.
- Screening 5,050 pairs without multiple-testing correction will produce some false positives.

---

## Disclaimer

TastySpread is **for educational and research purposes only**. Not affiliated with tastytrade. It is not financial advice. Past statistical relationships do not guarantee future performance. Options involve risk and are not suitable for all investors. You are responsible for every order placed from your account.

---

## License

```
MIT License

Copyright (c) 2025 Rahul Girish Kumar

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
