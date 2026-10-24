"use strict";

// Market Analyzer — Lightweight Charts 4.2.0
const SYMBOLS = ["XAUUSD", "EURUSD", "BTCUSD"];
const MAX_BARS = 600;
let brokerSymbols = {XAUUSD:"XAUUSD", EURUSD:"EURUSD", BTCUSD:"BTCUSD"};
let currentSymbol = "XAUUSD";
let chart, rsiChart, macdChart, priceSeries, rsiSeries, macdSeries, signalSeries, histogramSeries;
let trendSeries = [], staticLines = [], candles = [], rsiValues = [];
let historyLoaded = false, pendingFrames = [], lastTickAt = 0, connectionState = "در حال اتصال...";
let explicitClosed = {}, lastQuoted = {}, alreadyAlerted = new Set();
let lastRedraw = 0, redrawTimer = null;
let dark = true;

function timeSeconds(raw) {
    if (raw == null || raw === "") return NaN;
    if (typeof raw === "number" || /^\d+$/.test(String(raw))) {
        const value = Number(raw);
        return Number.isFinite(value) ? Math.floor(value > 1e12 ? value / 1000 : value) : NaN;
    }
    const parsed = Date.parse(String(raw));
    return Number.isFinite(parsed) ? Math.floor(parsed / 1000) : NaN;
}

function normalizeCandle(data) {
    if (!data || typeof data !== "object") return null;
    const time = timeSeconds(data.time ?? data.timeUtc ?? data.timestamp ?? data.timeMsc);
    const open = Number(data.open ?? data.openPrice);
    const high = Number(data.high ?? data.highPrice);
    const low = Number(data.low ?? data.lowPrice);
    const close = Number(data.close ?? data.closePrice);
    if (![time, open, high, low, close].every(Number.isFinite) ||
        time <= 0 || open <= 0 || high < Math.max(open, close) ||
        low > Math.min(open, close) || low <= 0) return null;
    return {time, open, high, low, close};
}

function normalizeHistory(raw) {
    let content = raw;
    if (typeof content === "string") { try { content = JSON.parse(content); } catch (_) { return []; } }
    const bars = Array.isArray(content) ? content :
        (content && (content.data || content.candles || content.bars || content.history));
    if (!Array.isArray(bars)) return [];
    const unique = new Map();
    for (const record of bars) {
        const candle = normalizeCandle(record);
        if (candle) unique.set(candle.time, candle);
    }
    return [...unique.values()].sort((a,b) => a.time-b.time).slice(-MAX_BARS);
}

function calculateRSI(data, period=14) {
    const out = data.map(c => ({time:c.time}));
    if (data.length < period+1) return out;
    let gain=0, loss=0;
    for(let i=1;i<=period;i++) {
        const diff=data[i].close-data[i-1].close;
        gain+=Math.max(0,diff); loss+=Math.max(0,-diff);
    }
    let avgGain=gain/period, avgLoss=loss/period;
    const value=()=>avgGain===0&&avgLoss===0?50:
        avgLoss===0?100:100-100/(1+avgGain/avgLoss);
    out[period]={time:data[period].time,value:value()};
    for(let i=period+1;i<data.length;i++) {
        const diff=data[i].close-data[i-1].close;
        avgGain=(avgGain*(period-1)+Math.max(0,diff))/period;
        avgLoss=(avgLoss*(period-1)+Math.max(0,-diff))/period;
        out[i]={time:data[i].time,value:value()};
    }
    return out;
}

function calculateMACD(data) {
    const empty = data.map(c=>({time:c.time}));
    if(data.length<27) return {macd:empty,signal:empty,hist:empty};
    const ema=(values,period)=>{
        const k=2/(period+1), result=[values[0]];
        for(let i=1;i<values.length;i++) result.push(values[i]*k+result[i-1]*(1-k));
        return result;
    };
    const values=data.map(d=>d.close);
    const a=ema(values,12), b=ema(values,26);
    const m=a.map((v,i)=>v-b[i]), s=ema(m,9);
    const macd=[],signal=[],hist=[];
    for(let i=0;i<data.length;i++) {
        const t=data[i].time;
        macd.push(i<26?{time:t}:{time:t,value:m[i]});
        signal.push(i<26?{time:t}:{time:t,value:s[i]});
        hist.push(i<26?{time:t}:{time:t,value:m[i]-s[i],
            color:m[i]-s[i]>=0?"#2ED573":"#FF4757"});
    }
    return {macd,signal,hist};
}

function pivots(data,left=5,right=5) {
    const highs=[],lows=[];
    for(let i=left;i<data.length-right;i++) {
        let high=true,low=true;
        for(let j=i-left;j<=i+right;j++) {
            if(j===i) continue;
            if(data[j].high>=data[i].high) high=false;
            if(data[j].low<=data[i].low) low=false;
        }
        if(high) highs.push({time:data[i].time,price:data[i].high});
        if(low) lows.push({time:data[i].time,price:data[i].low});
    }
    return {highs,lows};
}

function findTrendSegments(data) {
    // Only confirmed pivots of completed bars; the currently forming bar is excluded.
    const done = data.slice(0,-1);
    const found=pivots(done);
    const segments=[];
    for(const [kind,points] of [["مقاومت",found.highs],["حمایت",found.lows]]) {
        if(points.length<2) continue;
        const p1=points[points.length-2], p2=points[points.length-1];
        if(p1.time===p2.time) continue;
        segments.push({kind,p1,p2,color:kind==="مقاومت"?"#FF4757":"#2ED573",
            valueAt:t=>p1.price+(p2.price-p1.price)*(t-p1.time)/(p2.time-p1.time)});
    }
    return segments;
}

function initCharts() {
    const priceEl=document.getElementById("priceChart");
    const rsiEl=document.getElementById("rsiChart");
    const macdEl=document.getElementById("macdChart");
    const options=(el)=>({
        width:el.clientWidth, height:el.clientHeight,
        layout:{background:{color:"#0B0E11"},textColor:"#E8EAED"},
        grid:{vertLines:{color:"rgba(255,255,255,.04)"},horzLines:{color:"rgba(255,255,255,.04)"}},
        rightPriceScale:{borderColor:"rgba(255,255,255,.1)"},
        timeScale:{borderColor:"rgba(255,255,255,.1)",timeVisible:true,secondsVisible:false},
        crosshair:{mode:LightweightCharts.CrosshairMode.Normal}
    });
    chart=LightweightCharts.createChart(priceEl,options(priceEl));
    priceSeries=chart.addCandlestickSeries({
        upColor:"#2ED573",downColor:"#FF4757",borderUpColor:"#2ED573",
        borderDownColor:"#FF4757",wickUpColor:"#2ED573",wickDownColor:"#FF4757"
    });
    rsiChart=LightweightCharts.createChart(rsiEl,options(rsiEl));
    rsiSeries=rsiChart.addLineSeries({color:"#9C27B0",lineWidth:2,priceLineVisible:false});
    macdChart=LightweightCharts.createChart(macdEl,options(macdEl));
    macdSeries=macdChart.addLineSeries({color:"#2E7DF7",lineWidth:2,priceLineVisible:false});
    signalSeries=macdChart.addLineSeries({color:"#FFA502",lineWidth:1,priceLineVisible:false});
    histogramSeries=macdChart.addHistogramSeries({color:"#2ED573",priceLineVisible:false});
    [30,70].forEach(level=>rsiSeries.createPriceLine({
        price:level,color:level===70?"rgba(255,71,87,.5)":"rgba(46,213,115,.5)",
        lineWidth:1,lineStyle:LightweightCharts.LineStyle.Dashed,
        axisLabelVisible:false
    }));
    chart.timeScale().subscribeVisibleLogicalRangeChange(range=>{
        if(!range) return;
        try {
            rsiChart.timeScale().setVisibleLogicalRange(range);
            macdChart.timeScale().setVisibleLogicalRange(range);
        } catch (_) {}
    });
    const observer=new ResizeObserver(()=>{
        for(const [c,el] of [[chart,priceEl],[rsiChart,rsiEl],[macdChart,macdEl]]) {
            if(el.clientWidth>0&&el.clientHeight>0) c.resize(el.clientWidth,el.clientHeight);
        }
    });
    [priceEl,rsiEl,macdEl].forEach(el=>observer.observe(el));
    const system=window.matchMedia("(prefers-color-scheme: dark)");
    setTheme(system.matches);
    system.addEventListener?.("change",e=>setTheme(e.matches));
    document.getElementById("toggleDark").addEventListener("change",e=>setTheme(e.target.checked));
}

function setTheme(isDark) {
    dark=!!isDark;
    document.body.classList.toggle("light",!dark);
    const toggle=document.getElementById("toggleDark");
    if(toggle) toggle.checked=dark;
    const layout={background:{color:dark?"#0B0E11":"#F6F8FB"},textColor:dark?"#E8EAED":"#17212E"};
    const grid={vertLines:{color:dark?"rgba(255,255,255,.04)":"rgba(0,0,0,.06)"},
        horzLines:{color:dark?"rgba(255,255,255,.04)":"rgba(0,0,0,.06)"}};
    for(const c of [chart,rsiChart,macdChart]) if(c) c.applyOptions({layout,grid});
}

function rerender(redrawLines=true) {
    if(!priceSeries) return;
    priceSeries.setData(candles);
    rsiValues=calculateRSI(candles);
    rsiSeries.setData(rsiValues);
    const macd=calculateMACD(candles);
    macdSeries.setData(macd.macd);
    signalSeries.setData(macd.signal);
    histogramSeries.setData(macd.hist);
    if(redrawLines) drawLines();
}

function scheduleRedraw(redrawLines=false) {
    if(redrawLines) {
        if(redrawTimer) {clearTimeout(redrawTimer);redrawTimer=null;}
        rerender(true);
        lastRedraw=Date.now();
    } else if(!redrawTimer) {
        redrawTimer=setTimeout(()=>{
            redrawTimer=null;
            if(Date.now()-lastRedraw>=80) {rerender(false);lastRedraw=Date.now();}
        },120);
    }
}

function drawLines() {
    for(const series of trendSeries) chart.removeSeries(series);
    trendSeries=[];
    for(const line of staticLines) priceSeries.removePriceLine(line);
    staticLines=[];
    if(candles.length<20) return;
    if(document.getElementById("toggleTrendLines").checked) {
        for(const segment of findTrendSegments(candles)) {
            const end=candles[candles.length-1];
            if(end.time<=segment.p1.time) continue;
            const series=chart.addLineSeries({
                color:segment.color,lineWidth:1,lineStyle:LightweightCharts.LineStyle.Dashed,
                lastValueVisible:false,priceLineVisible:false,crosshairMarkerVisible:false
            });
            series.setData([{time:segment.p1.time,value:segment.p1.price},
                {time:end.time,value:segment.valueAt(end.time)}]);
            trendSeries.push(series);
        }
    }
    const recent=candles.slice(-15,-1);
    if(recent.length) {
        const levels=[Math.max(...recent.map(c=>c.high)),Math.min(...recent.map(c=>c.low))];
        for(const price of levels) staticLines.push(priceSeries.createPriceLine({
            price,color:dark?"rgba(255,255,255,.45)":"rgba(0,0,0,.35)",
            lineWidth:1,lineStyle:LightweightCharts.LineStyle.Solid,axisLabelVisible:true
        }));
    }
}

function notifyAlert(key,text,cb) {
    if(alreadyAlerted.has(key)) return;
    alreadyAlerted.add(key);
    if(alreadyAlerted.size>300) alreadyAlerted=new Set([...alreadyAlerted].slice(-200));
    if(window.AndroidBridge&&typeof cb==="function") cb();
    showAlertLog(text);
}

function checkBreaks() {
    if(candles.length<32) return;
    const completed=candles[candles.length-2],prev=candles[candles.length-3];
    for(const segment of findTrendSegments(candles)) {
        const oldDelta=prev.close-segment.valueAt(prev.time);
        const newDelta=completed.close-segment.valueAt(completed.time);
        if(oldDelta*newDelta<0) {
            const key=`${currentSymbol}:trend:${segment.kind}:${completed.time}`;
            notifyAlert(key,`شکست خط روند ${segment.kind} در ${currentSymbol}`,
                ()=>AndroidBridge.onTrendBreak(currentSymbol,segment.kind,1));
        }
    }
    const ri=rsiValues;
    const a=ri[ri.length-3]?.value, b=ri[ri.length-2]?.value;
    if(!Number.isFinite(a)||!Number.isFinite(b)) return;
    for(const level of [30,70]) {
        if((a<level&&b>=level)||(a>level&&b<=level)) {
            const key=`${currentSymbol}:rsi:${level}:${completed.time}`;
            notifyAlert(key,`RSI در ${currentSymbol} از سطح ${level} عبور کرد`,
                ()=>AndroidBridge.onRsiBreak(currentSymbol,String(level)));
        }
    }
}

function upsertCandle(bar,authoritative=false) {
    if(!bar || !Number.isFinite(bar.time)) return;
    const last=candles[candles.length-1];
    if(last&&bar.time<last.time) return; // Out-of-order network frame
    if(last&&bar.time===last.time) {
        // Authoritative OHLC stream takes priority over a quote-synthesized candle.
        if(last._server && !authoritative) return;
        candles[candles.length-1]={...bar,_server:authoritative};
        scheduleRedraw(false);
        return;
    }
    const newBar=!!last;
    candles.push({...bar,_server:authoritative});
    if(candles.length>MAX_BARS) candles.shift();
    scheduleRedraw(true);
    if(newBar&&historyLoaded) checkBreaks();
}

function logicalSymbol(symbol) {
    if(!symbol) return null;
    return SYMBOLS.find(key=>brokerSymbols[key]===symbol || key===symbol)||null;
}

function onBrokerSymbols(raw) {
    try {
        const map=JSON.parse(raw);
        for(const key of SYMBOLS) if(typeof map[key]==="string"&&map[key]) brokerSymbols[key]=map[key];
    } catch (_) {}
}

function onMarketHistory(raw) {
    try {
        const info=JSON.parse(raw);
        if(info.symbol!==currentSymbol) return;
        const newCandles=normalizeHistory(info.history);
        const previouslyPending=pendingFrames.slice();
        pendingFrames=[];
        const existing=candles.filter(c=>!newCandles.length||c.time>=newCandles[newCandles.length-1].time);
        const union=new Map(newCandles.map(c=>[c.time,c]));
        for(const c of existing) if(!union.has(c.time)) union.set(c.time,c);
        candles=[...union.values()].sort((a,b)=>a.time-b.time).slice(-MAX_BARS);
        historyLoaded=true;
        rerender(true);
        for(const frame of previouslyPending) processFrame(frame);
        if(candles.length) chart.timeScale().fitContent();
        updateMarketStatus();
    } catch (_) {onHistoryError("خطای پردازش تاریخچه");}
}

function onHistoryError(message) {
    historyLoaded=true; // live data remains available without history
    pendingFrames.splice(0).forEach(processFrame);
    showAlertLog(message);
    updateMarketStatus();
}

function synthesizeFromQuote(q,time,price) {
    const bucket=Math.floor(time/60)*60;
    const last=candles[candles.length-1];
    if(last && last.time>bucket) return null;
    if(last && last.time===bucket) {
        if(last._server) return null;
        return {time:bucket,open:last.open,high:Math.max(last.high,price),
            low:Math.min(last.low,price),close:price};
    }
    return {time:bucket,open:price,high:price,low:price,close:price};
}

function processFrame(frame) {
    if(!frame||typeof frame!=="object") return;
    const kind=String(frame.type||"").toUpperCase();
    const data=frame.data || frame;
    if(!data||Array.isArray(data)||typeof data!=="object") return;
    const logical=logicalSymbol(data.symbol);
    if(!logical) return;
    if(typeof data.marketOpen==="boolean") explicitClosed[logical]=!data.marketOpen;
    if(typeof data.marketClosed==="boolean") explicitClosed[logical]=data.marketClosed;
    if(kind==="QUOTE" || (!kind&&data.bid!=null)) {
        const bid=Number(data.bid),ask=Number(data.ask);
        if(!Number.isFinite(bid)||!Number.isFinite(ask)||bid<=0||ask<=0) return;
        const price=(bid+ask)/2;
        const timestamp=timeSeconds(data.timeMsc??data.time??data.timestamp);
        const t=Number.isFinite(timestamp)?timestamp:Math.floor(Date.now()/1000);
        lastQuoted[logical]={price,t};
        if(logical===currentSymbol) {
            lastTickAt=Date.now();
            document.getElementById("lastPrice").textContent=
                price.toLocaleString("en-US",{maximumFractionDigits:price>=10?3:6});
            const bar=synthesizeFromQuote(data,t,price);
            if(bar) upsertCandle(bar,false);
            updateMarketStatus();
        }
    } else if(kind==="CANDLE") {
        if(logical!==currentSymbol) return;
        const tf=String(data.timeframe||data.timeFrame||"M1").toUpperCase();
        if(tf!=="M1") return;
        const bar=normalizeCandle(data);
        if(bar) upsertCandle(bar,true);
    }
}

function onMarketData(raw) {
    let frame;
    try {frame=typeof raw==="string"?JSON.parse(raw):raw;} catch (_) {return;}
    const d=frame?.data??frame;
    const logical=logicalSymbol(d?.symbol);
    if(logical===currentSymbol && !historyLoaded) {
        if(pendingFrames.length<300) pendingFrames.push(frame);
        return;
    }
    processFrame(frame);
}

function onConnectionState(state) {
    connectionState=String(state);
    const el=document.getElementById("connectionStatus");
    if(el) {
        el.textContent=connectionState;
        el.style.color=connectionState==="متصل"?"#2ED573":
            connectionState.includes("قطع")||connectionState.includes("خطا")?"#FF4757":"var(--muted)";
    }
    updateMarketStatus();
}

function onRefreshStarted() {
    const btn=document.getElementById("refreshBtn");
    if(btn) {btn.classList.add("refreshing");setTimeout(()=>btn.classList.remove("refreshing"),700);}
}

function isWeekendMarketClosed(symbol,now=new Date()) {
    if(symbol==="BTCUSD") return false;
    const day=now.getUTCDay(), hour=now.getUTCHours();
    return day===6||(day===0&&hour<21);
}

function updateMarketStatus() {
    const el=document.getElementById("marketStatus");
    if(!el) return;
    const tick=lastQuoted[currentSymbol];
    const hasRecentTick=tick && (Date.now()/1000-tick.t)<100;
    const closedByFeed=explicitClosed[currentSymbol]===true;
    const closedByWeekend=isWeekendMarketClosed(currentSymbol)&&!hasRecentTick;
    const status=closedByFeed||closedByWeekend?"CLOSED":
        hasRecentTick&&connectionState==="متصل"?"LIVE":"NO DATA";
    el.textContent=status;
    el.className="market-status "+(status==="LIVE"?"live":status==="CLOSED"?"closed":"");
}

function switchSymbol(symbol) {
    if(!SYMBOLS.includes(symbol)) return;
    currentSymbol=symbol;
    historyLoaded=false;pendingFrames=[];candles=[];rsiValues=[];
    if(redrawTimer){clearTimeout(redrawTimer);redrawTimer=null;}
    rerender(true);
    document.getElementById("lastPrice").textContent="";
    updateMarketStatus();
    window.AndroidBridge?.selectSymbol(symbol);
}

function showAlertLog(message) {
    const log=document.getElementById("alertLog");
    if(!log) return;
    const item=document.createElement("div");
    item.className="alert-item";item.textContent=message;
    log.appendChild(item);
    setTimeout(()=>item.remove(),4000);
}

function requestRefresh() {
    window.AndroidBridge?.refresh();
}
function setupEvents() {
    initCharts();
    document.getElementById("symbolSelect").addEventListener("change",e=>switchSymbol(e.target.value));
    document.getElementById("settingsBtn").addEventListener("click",()=>{
        document.getElementById("settingsPanel").classList.remove("hidden");
    });
    document.getElementById("closeSettings").addEventListener("click",()=>{
        document.getElementById("settingsPanel").classList.add("hidden");
    });
    document.getElementById("connectionSettings").addEventListener("click",()=>{
        window.AndroidBridge?.configure();
    });
    document.getElementById("refreshBtn").addEventListener("click",requestRefresh);
    document.getElementById("toggleTrendLines").addEventListener("change",()=>drawLines());
    document.getElementById("toggleRsi").addEventListener("change",e=>{
        document.getElementById("rsiChart").classList.toggle("off",!e.target.checked);
    });
    document.getElementById("toggleMacd").addEventListener("change",e=>{
        document.getElementById("macdChart").classList.toggle("off",!e.target.checked);
    });
    const top=document.getElementById("topBar");
    let start=null;
    top.addEventListener("touchstart",e=>{start=e.touches[0]?.clientY??null;},{passive:true});
    top.addEventListener("touchend",e=>{
        if(start!==null && (e.changedTouches[0]?.clientY??start)-start>65) requestRefresh();
        start=null;
    },{passive:true});
    setInterval(updateMarketStatus,15000);
    updateMarketStatus();
}

if(typeof document!=="undefined") document.addEventListener("DOMContentLoaded",setupEvents);
if(typeof window!=="undefined") {
    Object.assign(window,{onMarketData,onMarketHistory,onHistoryError,
        onConnectionState,onRefreshStarted,onBrokerSymbols});
}
if(typeof module!=="undefined"&&module.exports) module.exports={
    timeSeconds,normalizeCandle,normalizeHistory,calculateRSI,calculateMACD,pivots,findTrendSegments,isWeekendMarketClosed
};
