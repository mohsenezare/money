const assert = require("node:assert/strict");
const {
  timeSeconds, normalizeCandle, normalizeHistory, calculateRSI, calculateMACD,
  pivots, findTrendSegments, isWeekendMarketClosed
} = require("../src/main/assets/app.js");

assert.equal(timeSeconds(1700000000000), 1700000000);
assert.equal(timeSeconds("2026-10-23T10:00:00Z"), 1792749600);
assert.equal(normalizeCandle({time:1700000000000,openPrice:3,highPrice:5,lowPrice:2,closePrice:4}).close,4);
assert.equal(normalizeCandle({time:1700000000,open:3,high:2,low:1,close:4}),null);
const bars = Array.from({length:90},(_,i)=>({
  time:1700000000+i*60,open:100+i,high:102+i,low:99+i,close:101+i
}));
assert.equal(normalizeHistory([bars[1],bars[0],bars[1]]).length,2);
assert.deepEqual(normalizeHistory([bars[1],bars[0]]).map(x=>x.time),[bars[0].time,bars[1].time]);
const rsi=calculateRSI(bars);
assert.equal(rsi.length,90);
assert.equal(rsi[0].value,undefined);
assert.equal(rsi.at(-1).value,100);
const m=calculateMACD(bars);
assert.equal(m.macd.length,90);
assert.equal(m.hist[0].value,undefined);
assert.ok(Number.isFinite(m.hist.at(-1).value));
assert.equal(findTrendSegments(bars).length,0);
assert.equal(pivots(bars).highs.length,0);
assert.equal(isWeekendMarketClosed("EURUSD",new Date("2026-10-24T15:00:00Z")),true);
assert.equal(isWeekendMarketClosed("BTCUSD",new Date("2026-10-24T15:00:00Z")),false);
console.log("Market Analyzer indicator tests passed");
