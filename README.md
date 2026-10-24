# Market Analyzer (Android)

## Free LiteFinance web-terminal mode (default)

**Version 1.2.0** starts in a free LiteFinance mode. Tap **LiteFinance ↗** or **ورود به وب‌ترمینال لایت‌فاینانس** to open the [official LiteFinance web terminal](https://my.litefinance.org/trading) in a browser Custom Tab on the Android phone.

- This works **entirely on a phone**. MetaTrader 5 does not need to be installed on a Windows PC.
- Sign in **only on the official LiteFinance domain**, with your existing LiteFinance profile, then select the MT5 account you already use.
- No paid MetaTrader API account or API key is required **for using the broker's own web terminal**.
- LiteFinance's web terminal itself supplies broker prices and its own charts/technical indicators.
- The web terminal **does not publish a supported feed** to this independent app; therefore the custom RSI/MACD/trend detection and Android alert engine in Market Analyzer are **not connected** to live LiteFinance quotes in free mode. Those features remain available only with a separately authorized market data API.
- **No LiteFinance credentials or cookies are handled or saved by this app.** Authentication takes place in the phone's browser environment via Chrome Custom Tabs. The native app only opens a constant HTTPS URL with a checked host. No trading instructions are submitted by Market Analyzer.
- Free mode makes no calls to metatraderapi.net. To enable a paid external data source deliberately, use Settings -> API (optional). Settings also allow switching back to the free broker web terminal.

## Separate API-backed custom charts (optional)

The independent app still supports external MetaTrader API connectivity when explicitly enabled by the user. It is **not a free direct LiteFinance/MT5 connection**. API credentials, if voluntarily entered, stay in Android encrypted preferences. Never commit these to GitHub.

- Charts: [Lightweight Charts](https://tradingview.github.io/lightweight-charts/) 4.2.0 loaded locally from Android assets.
- History from `PriceHistory` M1 timeframe (`1`), live quotes and candles from WebSocket.
- Custom indicators: RSI(14), MACD(12,26,9), confirmed pivot support/resistance, closed-candle alerts.
- Broker-specific symbol suffixes can be configured.

## Build APK

In GitHub: Actions -> **Android APK** -> the latest successful run -> **MarketAnalyzer-debug-apk** artifact.

Workflow uses JDK 17, Android SDK 35, Gradle 8.7, and runs JavaScript tests + Android debug build.

The APK is intended for installation on Android 7.0+.
