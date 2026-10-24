#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Phone-hosted Telegram market-analysis bot. Python stdlib only; no trading."""
import datetime as dt
import json
import os
import secrets
import sqlite3
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

SYMBOLS = ("XAUUSD", "EURUSD", "BTCUSD")
FRAMES = {"1m": 60, "5m": 300, "15m": 900, "1h": 3600}
FEED = "https://biquote.io/api"
TOKEN = os.getenv("TELEGRAM_BOT_TOKEN", "").strip()
DB_FILE = os.getenv("BOT_DB_PATH", os.path.join(os.path.dirname(__file__), "bot_state.sqlite3"))
CHECK_SECONDS = 60
CANDLE_LIMIT = 220


class FeedError(Exception):
    pass


def utc_time(value):
    if isinstance(value, (float, int)):
        return dt.datetime.fromtimestamp(float(value) / (1000 if value > 1e12 else 1),
                                         tz=dt.timezone.utc)
    if not isinstance(value, str):
        raise FeedError("زمان داده نامعتبر است.")
    try:
        return dt.datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(dt.timezone.utc)
    except ValueError as exc:
        raise FeedError("زمان داده خوانده نشد.") from exc


def fetch_json(url, params=None, timeout=15):
    if params:
        url += "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"User-Agent": "MarketAnalyzerBot/1.0",
                                               "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            data = response.read(2_000_000)
        return json.loads(data.decode("utf-8"))
    except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, ValueError) as exc:
        raise FeedError("دسترسی به منبع داده برقرار نشد: " + str(exc)[:90]) from exc


def normalize_bars(obj):
    if not isinstance(obj, dict) or not isinstance(obj.get("bars"), list):
        raise FeedError("پاسخ کندل‌های API ساختار معتبر ندارد.")
    found = {}
    for item in obj["bars"]:
        try:
            t = utc_time(item["openTime"])
            o, h, l, c = (float(item[n]) for n in ("open", "high", "low", "close"))
            if not all(map(lambda n: n == n and abs(n) < 1e12, (o, h, l, c))):
                continue
            if o <= 0 or l <= 0 or h < max(o, c) or l > min(o, c):
                continue
            found[t] = dict(time=t, open=o, high=h, low=l, close=c,
                            is_open=item.get("isOpen") is True)
        except (ValueError, KeyError, TypeError, OverflowError, FeedError):
            continue
    bars = [found[t] for t in sorted(found)]
    if not bars:
        raise FeedError("تاریخچه کندل معتبر نیست.")
    return bars


def fetch_bars(symbol, timeframe):
    if symbol not in SYMBOLS or timeframe not in FRAMES:
        raise FeedError("نماد یا تایم‌فریم پشتیبانی نمی‌شود.")
    return normalize_bars(fetch_json(f"{FEED}/{symbol}/ohlc",
                                      {"interval": timeframe, "limit": CANDLE_LIMIT}))


def fetch_tick(symbol):
    if symbol not in SYMBOLS:
        raise FeedError("نماد پشتیبانی نمی‌شود.")
    tick = fetch_json(f"{FEED}/{symbol}")
    if not isinstance(tick, dict) or tick.get("symbol", "").upper() != symbol:
        raise FeedError("منبع داده قیمت معتبر برنگرداند.")
    try:
        bid, ask = float(tick["bid"]), float(tick["ask"])
        if not (0 < bid <= ask and ask < 1e10):
            raise ValueError()
        observed = utc_time(tick.get("timestamp") or tick.get("lastQuoteAt"))
    except (KeyError, ValueError, FeedError) as exc:
        raise FeedError("پاسخ قیمت قابل استفاده نیست.") from exc
    age = (dt.datetime.now(dt.timezone.utc) - observed).total_seconds()
    return {"symbol": symbol, "bid": bid, "ask": ask, "mid": (bid+ask)/2,
            "time": observed, "age": age,
            "closed": tick.get("marketState") == "closed",
            "stale": tick.get("stale") is True or age > 180}


def rsi_values(closes, period=14):
    result = [None] * len(closes)
    if len(closes) < period + 1:
        return result
    changes = [closes[i] - closes[i-1] for i in range(1, len(closes))]
    gain = sum(max(c, 0) for c in changes[:period]) / period
    loss = sum(max(-c, 0) for c in changes[:period]) / period

    def score(g, l):
        if g == 0 and l == 0: return 50.0
        if l == 0: return 100.0
        return 100 - 100 / (1 + g/l)

    result[period] = score(gain, loss)
    for i in range(period + 1, len(closes)):
        change = closes[i] - closes[i-1]
        gain = (gain * (period - 1) + max(change, 0)) / period
        loss = (loss * (period - 1) + max(-change, 0)) / period
        result[i] = score(gain, loss)
    return result


def ema(values, period):
    if not values:
        return []
    k = 2 / (period + 1)
    result = [values[0]]
    for x in values[1:]:
        result.append(x*k + result[-1]*(1-k))
    return result


def macd_values(closes):
    short, long = ema(closes, 12), ema(closes, 26)
    line = [a-b for a, b in zip(short, long)]
    signal = ema(line, 9)
    return line, signal, [a-b for a, b in zip(line, signal)]


def find_pivots(bars, kind, left=3, right=3):
    key = "high" if kind == "resistance" else "low"
    pts = []
    for i in range(left, len(bars) - right):
        values = [bars[j][key] for j in range(i-left, i+right+1) if j != i]
        v = bars[i][key]
        if (kind == "resistance" and all(v > x for x in values)) or (
            kind == "support" and all(v < x for x in values)):
            pts.append((bars[i]["time"].timestamp(), v))
    return pts


def trend_breaks(closed):
    """Evaluate previous-to-last CLOSED candle crossing projected trend line."""
    if len(closed) < 35:
        return []
    prior, current = closed[-2:]
    # Excluding both checked candles avoids using future information.
    historical = closed[:-2]
    detected = []
    for kind, fa in [("resistance", "مقاومت"), ("support", "حمایت")]:
        pivots = find_pivots(historical, kind)
        if len(pivots) < 2:
            continue
        (t1, p1), (t2, p2) = pivots[-2:]
        if t2 <= t1:
            continue
        value = lambda t: p1 + (p2 - p1) * (t - t1) / (t2 - t1)
        old_gap = prior["close"] - value(prior["time"].timestamp())
        new_gap = current["close"] - value(current["time"].timestamp())
        if old_gap * new_gap < 0:
            direction = "عبور رو به بالا" if new_gap > 0 else "عبور رو به پایین"
            detected.append(f"شکست {fa}: {direction}")
    return detected


def signals(closed):
    if len(closed) < 45:
        return []
    prices = [x["close"] for x in closed]
    r = rsi_values(prices)
    alerts = trend_breaks(closed)
    a, b = r[-2], r[-1]
    for threshold in (30, 70):
        if a is not None and b is not None and (a-threshold)*(b-threshold) < 0:
            alerts.append(f"عبور RSI از سطح {threshold} ({a:.1f} → {b:.1f})")
    return alerts


def describe_price(tick):
    status = "بازار بسته" if tick["closed"] else (
        "داده قدیمی / غیرلحظه‌ای" if tick["stale"] else "آخرین قیمت دریافتی")
    return (f"💹 {tick['symbol']}\n"
            f"Bid: {tick['bid']:,.5f}\nAsk: {tick['ask']:,.5f}\n"
            f"Mid: {tick['mid']:,.5f}\n"
            f"وضعیت: {status}\n"
            f"زمان: {tick['time']:%Y-%m-%d %H:%M:%S} UTC\n"
            f"منبع: biquote (نه LiteFinance)")


def describe_analysis(symbol, frame, bars):
    closed = [b for b in bars if not b["is_open"]]
    if len(closed) < 45:
        raise FeedError("حداقل ۴۵ کندل بسته برای محاسبه تحلیل لازم است.")
    prices = [b["close"] for b in closed]
    rsi = rsi_values(prices)[-1]
    macd, signal, hist = macd_values(prices)
    last = closed[-1]
    age = (dt.datetime.now(dt.timezone.utc) - (last["time"] +
             dt.timedelta(seconds=FRAMES[frame]))).total_seconds()
    state = "قدیمی — برای معامله لحظه‌ای مناسب نیست" if age > FRAMES[frame]*3 else "تازه"
    trend = "صعودی" if macd[-1] > signal[-1] else "نزولی"
    found = signals(closed)
    signal_text = "\n".join("• " + x for x in found) if found else "شکست تأییدشده‌ای در کندل آخر تشخیص داده نشد"
    text = (f"📊 تحلیل {symbol} | {frame}\n"
            f"آخرین کندل بسته: {last['time']:%m-%d %H:%M} UTC\n"
            f"Close: {last['close']:,.5f}\n"
            f"RSI(14): {rsi:.1f}\n"
            f"MACD: {macd[-1]:.5f}\n"
            f"Signal: {signal[-1]:.5f}\n"
            f"Histogram: {hist[-1]:+.5f}\n"
            f"مومنتوم MACD: {trend}\n"
            f"وضعیت داده: {state}\n\n"
            f"بررسی شکست:\n{signal_text}\n\n"
            "⚠️ صرفاً تحلیل آماری؛ قیمت LiteFinance نیست و توصیه معامله نیست.")
    return text


def create_db():
    conn = sqlite3.connect(DB_FILE)
    conn.execute("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    conn.execute("CREATE TABLE IF NOT EXISTS watches "
                 "(chat_id INTEGER, symbol TEXT, frame TEXT, PRIMARY KEY(chat_id,symbol,frame))")
    conn.execute("CREATE TABLE IF NOT EXISTS sent_alerts "
                 "(chat_id INTEGER, symbol TEXT, frame TEXT, event TEXT, bar_time TEXT, "
                 "PRIMARY KEY(chat_id,symbol,frame,event,bar_time))")
    conn.commit()
    return conn


def setting(db, key, default=None):
    row = db.execute("SELECT value FROM settings WHERE key=?", (key,)).fetchone()
    return row[0] if row else default


def save_setting(db, key, value):
    db.execute("INSERT OR REPLACE INTO settings(key,value) VALUES(?,?)", (key, str(value)))
    db.commit()


def tg(method, payload):
    encoded = urllib.parse.urlencode(payload).encode("utf-8")
    request = urllib.request.Request(f"https://api.telegram.org/bot{TOKEN}/{method}",
                                     data=encoded, headers={"Content-Type":
                                     "application/x-www-form-urlencoded"})
    with urllib.request.urlopen(request, timeout=40) as reply:
        obj = json.load(reply)
    if not obj.get("ok"):
        raise RuntimeError("Telegram API returned an error")
    return obj["result"]


def send(chat_id, message, keyboard=False):
    payload = {"chat_id": str(chat_id), "text": message[:4000]}
    if keyboard:
        layout = {"keyboard": [
            ["/price XAUUSD", "/price EURUSD", "/price BTCUSD"],
            ["/analyze XAUUSD 5m", "/analyze EURUSD 15m"],
            ["/watch XAUUSD 5m", "/watches", "/help"]
        ], "resize_keyboard": True}
        payload["reply_markup"] = json.dumps(layout, ensure_ascii=False)
    tg("sendMessage", payload)


HELP = (
    "🤖 ربات تحلیل بازار\n"
    "/price XAUUSD — قیمت\n"
    "/analyze XAUUSD 5m — RSI، MACD و خطوط روند\n"
    "/watch XAUUSD 5m — روشن کردن هشدارهای آن نماد\n"
    "/unwatch XAUUSD 5m — خاموش کردن هشدار\n"
    "/watches — فهرست هشدارها\n"
    "/source — توضیح منبع داده\n"
    "/help — راهنما\n\n"
    "نمادها: XAUUSD / EURUSD / BTCUSD\n"
    "بازه‌ها: 1m / 5m / 15m / 1h\n"
    "این ربات به حساب واقعی LiteFinance وصل نیست و هیچ معامله‌ای انجام نمی‌دهد."
)


def command(db, chat_id, text, setup_code):
    parts = text.strip().split()
    if not parts:
        return
    cmd = parts[0].split("@", 1)[0].lower()
    owner = setting(db, "owner")
    if owner is None:
        if cmd == "/claim" and len(parts) == 2 and secrets.compare_digest(parts[1], setup_code):
            save_setting(db, "owner", chat_id)
            send(chat_id, "✅ ربات شخصی تو فعال شد.\n" + HELP, keyboard=True)
        else:
            send(chat_id, "این ربات خصوصی است. کد فعال‌سازی را از صفحه Termux "
                 "بردار و با دستور /claim CODE وارد کن.")
        return
    if str(chat_id) != owner:
        # Do not reveal anything about market data or configuration to others.
        return
    if cmd in ("/start", "/help"):
        send(chat_id, HELP, keyboard=True)
        return
    if cmd == "/source":
        send(chat_id, "منبع قیمت: https://biquote.io/docs/\n"
             "منبع مستقل MT5؛ نه سرور LiteFinance و نه حساب شخصی تو.\n"
             "قیمت‌ها ممکن است با بروکر متفاوت باشند. برای استفاده باید "
             "برنامه Termux در گوشی فعال و به اینترنت متصل باشد.")
        return
    if cmd == "/watches":
        rows = db.execute("SELECT symbol,frame FROM watches WHERE chat_id=? ORDER BY symbol,frame",
                          (chat_id,)).fetchall()
        send(chat_id, "🔔 هشدارهای فعال:\n" +
             ("\n".join(f"{s} {f}" for s, f in rows) if rows else "هنوز هشداری ثبت نکرده‌ای."))
        return
    if cmd not in ("/price", "/analyze", "/watch", "/unwatch"):
        send(chat_id, "دستور شناخته نشد.\n" + HELP)
        return
    symbol = parts[1].upper() if len(parts) > 1 else "XAUUSD"
    frame = parts[2].lower() if len(parts) > 2 else "5m"
    if symbol not in SYMBOLS or (cmd != "/price" and frame not in FRAMES):
        send(chat_id, "نماد یا تایم‌فریم نامعتبر است.\n" + HELP)
        return
    if cmd == "/watch":
        db.execute("INSERT OR IGNORE INTO watches(chat_id,symbol,frame) VALUES(?,?,?)",
                   (chat_id, symbol, frame))
        db.commit()
        send(chat_id, f"✅ هشدار {symbol} {frame} فعال شد.\n"
             "هر ۶۰ ثانیه بررسی می‌شود؛ فقط روی کندل بسته و داده تازه.")
    elif cmd == "/unwatch":
        db.execute("DELETE FROM watches WHERE chat_id=? AND symbol=? AND frame=?",
                   (chat_id, symbol, frame))
        db.commit()
        send(chat_id, f"🔕 هشدار {symbol} {frame} خاموش شد.")
    elif cmd == "/price":
        send(chat_id, describe_price(fetch_tick(symbol)))
    else:
        send(chat_id, describe_analysis(symbol, frame, fetch_bars(symbol, frame)))


def monitor(db):
    watches = db.execute("SELECT chat_id,symbol,frame FROM watches").fetchall()
    unique = {(s, f) for _, s, f in watches}
    snapshot = {}
    for symbol, frame in unique:
        try:
            closed = [x for x in fetch_bars(symbol, frame) if not x["is_open"]]
            if len(closed) < 45:
                continue
            latest = closed[-1]
            closed_at = latest["time"] + dt.timedelta(seconds=FRAMES[frame])
            age = (dt.datetime.now(dt.timezone.utc) - closed_at).total_seconds()
            if age < -10 or age > max(FRAMES[frame] * 2, 150):
                continue
            snapshot[(symbol, frame)] = (latest["time"].isoformat(), signals(closed))
        except FeedError as exc:
            print(f"Feed error {symbol}/{frame}: {exc}", flush=True)
    for chat_id, symbol, frame in watches:
        pair = snapshot.get((symbol, frame))
        if pair is None:
            continue
        bar_time, events = pair
        for event in events:
            try:
                inserted = db.execute(
                    "INSERT OR IGNORE INTO sent_alerts VALUES(?,?,?,?,?)",
                    (chat_id, symbol, frame, event, bar_time)).rowcount
                db.commit()
                if inserted:
                    send(chat_id, f"🔔 {symbol} {frame}\n{event}\n"
                         f"کندل: {bar_time}\nمنبع: biquote، نه LiteFinance")
            except (urllib.error.URLError, RuntimeError) as exc:
                print("Telegram alert error:", exc, flush=True)
    # Bound the state store.
    db.execute("DELETE FROM sent_alerts WHERE bar_time < ?",
               ((dt.datetime.now(dt.timezone.utc) -
                 dt.timedelta(days=7)).isoformat(),))
    db.commit()


def run():
    if not TOKEN or ":" not in TOKEN:
        print("Missing TELEGRAM_BOT_TOKEN. Create your own bot with @BotFather.", file=sys.stderr)
        sys.exit(2)
    db = create_db()
    code = secrets.token_urlsafe(8)
    print("Checking Telegram token...", flush=True)
    try:
        identity = tg("getMe", {})
    except Exception as exc:
        print(f"Could not validate bot token: {exc}", file=sys.stderr)
        sys.exit(2)
    print("Telegram bot @" + identity.get("username", ""), flush=True)
    if setting(db, "owner") is None:
        print("FIRST SETUP: send /claim " + code + " to your bot in Telegram.", flush=True)
        print("Do NOT share this code or your bot token with anyone.", flush=True)
    print("Market data: biquote (not LiteFinance). Polling every 60s for alerts.", flush=True)
    offset = int(setting(db, "offset", 0))
    last_check = time.monotonic()
    while True:
        try:
            result = tg("getUpdates", {"offset": offset, "timeout": 22,
                                      "allowed_updates": json.dumps(["message"])})
            for item in result:
                update_id = item.get("update_id")
                if isinstance(update_id, int):
                    offset = max(offset, update_id + 1)
                    save_setting(db, "offset", offset)
                msg = item.get("message", {})
                chat = msg.get("chat", {})
                chat_id = chat.get("id")
                if chat.get("type") != "private" or not chat_id:
                    continue
                content = msg.get("text", "")
                if content.startswith("/"):
                    try:
                        command(db, chat_id, content, code)
                    except FeedError as exc:
                        send(chat_id, "⚠️ " + str(exc))
                    except (TypeError, KeyError, ValueError) as exc:
                        send(chat_id, "خطای پردازش داده: " + str(exc)[:100])
            if time.monotonic() - last_check >= CHECK_SECONDS:
                monitor(db)
                last_check = time.monotonic()
        except KeyboardInterrupt:
            print("Bot stopped.")
            break
        except Exception as exc:
            print("Bot connection error:", str(exc)[:150], flush=True)
            time.sleep(5)


if __name__ == "__main__":
    run()
