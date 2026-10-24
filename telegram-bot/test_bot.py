import datetime as dt
import os
import sqlite3
import tempfile
import unittest
from unittest.mock import patch
import bot

UTC = dt.timezone.utc


def make_bars(n=100, start=None):
    start = start or dt.datetime(2026, 10, 22, tzinfo=UTC)
    bars = []
    for i in range(n):
        v = 100 + i * 0.1
        bars.append(dict(openTime=(start + dt.timedelta(minutes=i)).isoformat(),
                         open=v, high=v+1, low=v-1, close=v+0.2,
                         isOpen=i == n-1))
    return bars


class AnalyticsTests(unittest.TestCase):
    def test_iso_times(self):
        self.assertEqual(bot.utc_time("2026-10-24T10:00:00Z"),
                         dt.datetime(2026,10,24,10,tzinfo=UTC))
        self.assertEqual(bot.utc_time(1700000000000).timestamp(), 1700000000)

    def test_candle_sort_and_validation(self):
        arr = make_bars(75)
        obj = {"symbol": "XAUUSD", "bars": list(reversed(arr)) + [arr[0]]}
        normalized = bot.normalize_bars(obj)
        self.assertEqual(len(normalized), 75)
        self.assertLess(normalized[0]["time"], normalized[-1]["time"])
        self.assertTrue(normalized[-1]["is_open"])

    def test_wilder_rsi(self):
        data = [100+i for i in range(90)]
        vals = bot.rsi_values(data)
        self.assertIsNone(vals[0])
        self.assertAlmostEqual(vals[-1], 100)
        self.assertAlmostEqual(bot.rsi_values([100]*90)[-1], 50)
        self.assertAlmostEqual(bot.rsi_values(list(reversed(data)))[-1], 0)

    def test_macd(self):
        line, sig, hist = bot.macd_values([100+i for i in range(100)])
        self.assertEqual(len(line), 100)
        self.assertEqual(len(sig), 100)
        self.assertEqual(len(hist), 100)
        self.assertGreater(line[-1], 0)
        self.assertAlmostEqual(hist[-1], line[-1]-sig[-1])

    def test_report_and_no_trend_for_monotonic(self):
        data = bot.normalize_bars({"bars": make_bars(100)})
        self.assertEqual(bot.trend_breaks(data[:-1]), [])
        message = bot.describe_analysis("XAUUSD", "1m", data)
        self.assertIn("RSI(14)", message)
        self.assertIn("MACD", message)
        self.assertIn("قیمت LiteFinance نیست", message)

    def test_source_does_not_leak_owner_info(self):
        with tempfile.TemporaryDirectory() as tmp:
            with patch.object(bot, "DB_FILE", tmp+"/bot.sqlite3"):
                db = bot.create_db()
                out = []
                with patch.object(bot, "send", side_effect=lambda chat, msg, keyboard=False: out.append((chat, msg))):
                    bot.command(db, 999, "/claim WRONG", "SECRET")
                    self.assertIsNone(bot.setting(db, "owner"))
                    bot.command(db, 123, "/claim SECRET", "SECRET")
                    self.assertEqual(bot.setting(db, "owner"), "123")
                    out.clear()
                    bot.command(db, 999, "/source", "SECRET")
                    self.assertEqual(out, [])
                    bot.command(db, 123, "/watch XAUUSD 5m", "SECRET")
                    self.assertEqual(db.execute("select count(*) from watches").fetchone()[0], 1)
                    bot.command(db, 123, "/unwatch XAUUSD 5m", "SECRET")
                    self.assertEqual(db.execute("select count(*) from watches").fetchone()[0], 0)
                db.close()

    def test_tick_parsing_no_live_claim_for_stale(self):
        old = "2026-01-01T00:00:00Z"
        with patch.object(bot, "fetch_json", return_value={
            "symbol":"XAUUSD","bid":2000.5,"ask":2001.5,
            "timestamp":old,"marketState":"closed","stale":True}):
            quote = bot.fetch_tick("XAUUSD")
        self.assertTrue(quote["closed"])
        self.assertTrue(quote["stale"])
        self.assertIn("بازار بسته", bot.describe_price(quote))


if __name__ == "__main__":
    unittest.main()
