# Market Analyzer (Android)

Native Android Kotlin + WebView charts. This is a fresh project based on the code provided in the conversation.

- Market data provider: [metatraderapi.net](https://www.metatraderapi.net/en/), an **independent paid third-party** service.
- Configure API key and connected account ID **inside the Android app** after installation; do not put credentials in this public GitHub repository.
- Broker symbols can have suffixes: enter your broker's exact names (e.g. XAUUSDm) in account settings.
- Candlestick history: /PriceHistory, real-time: /v1/stream QUOTE and CANDLE events.
- Technical indicators: RSI(14), MACD(12,26,9), pivot trend lines. Alerts are based on fully closed candles only.
- The market status is conservative: CLOSED only for explicit server flags or known weekend FX/gold closure; stale data alone never proves a market is closed.
- No trades are sent by this application.

## Build
GitHub Actions **Android APK** workflow builds debug APK on push or manually through workflow_dispatch; see Actions -> Android APK -> latest successful run -> Artifacts.
The build downloads the exact Lightweight Charts 4.2.0 script into local assets, so it is available offline after installation.

Local build (requires Android SDK 35, Java 17, Gradle 8.7): `gradle :app:assembleDebug`.

## Secrets
No API key or account id is committed or baked into the APK. They are stored on-device in encrypted preferences via Android Keystore. Because API calls originate on-device, a determined attacker with physical compromise may still extract runtime credentials: for stronger security, use a backend proxy that holds the key.

## Limitations
The provider requires a configured active account and may use broker-specific symbol suffixes. The connection and quote handling need live verification against that specific account. Price history/tick availability is broker dependent, and no data is fabricated when the feed is missing.
