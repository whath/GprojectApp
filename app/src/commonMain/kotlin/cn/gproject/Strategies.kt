package cn.gproject

import kotlin.math.*

enum class StrategyPreset(val title: String, val summary: String) {
    CUSTOM("自定义指标", "均线、RSI、量比及突破条件同时满足"),
    TOP_RISK("顶部风险观察", "上涨背景下，阻力附近出现看跌蜡烛结构"),
    DOWNTREND("下跌趋势警戒", "均线走弱，近期高点与低点同步下移"),
    BASE_WATCH("回调横盘观察", "上升趋势内回调缩量，等待突破确认"),
    BREAKOUT("放量突破确认", "收盘越过前期高点，量能与趋势共同确认"),
    BOTTOM_WATCH("底部反转观察", "下跌背景下，支撑附近出现看涨蜡烛结构"),
}

data class StrategyParameters(
    val fast: Int = 20, val slow: Int = 125,
    val lookback: Int = 20, val baseDays: Int = 10,
    val breakoutVolume: Double = 1.5, val contractionVolume: Double = .8,
    val rangePct: Double = 8.0, val breakoutBufferPct: Double = .5,
    val nearLevelPct: Double = 3.0, val shadowRatio: Double = 2.0,
)

data class SetupHit(
    val preset: StrategyPreset, val status: String, val evidence: List<String>,
    val confirmation: String, val invalidation: String, val date: String,
)

/** Explicit, causal operational definitions; thresholds are app conventions, not book quotations. */
object StrategyEngine {
    fun evaluate(bars: List<Bar>, p: StrategyParameters): List<SetupHit>? {
        if (listOf(p.breakoutVolume,p.contractionVolume,p.rangePct,p.breakoutBufferPct,p.nearLevelPct,p.shadowRatio).any { !it.isFinite() }) return null
        if (p.fast !in DAILY_MA_PERIODS || p.slow !in DAILY_MA_PERIODS || p.slow <= p.fast || p.lookback < 5 || p.baseDays < 3 ||
            p.breakoutVolume <= 0 || p.contractionVolume <= 0 || p.rangePct <= 0 ||
            p.breakoutBufferPct < 0 || p.nearLevelPct < 0 || p.shadowRatio < 1) return null
        if (bars.size < maxOf(p.slow + 5, p.lookback + p.baseDays + 1, 25)) return null
        if (bars.any { it.source.isBlank() || it.volumeUnit.isBlank() || it.volumeUnit == "unknown" } || bars.map { it.source }.distinct().size != 1 || bars.map { it.volumeUnit }.distinct().size != 1 ||
            bars.zipWithNext().any { it.first.date >= it.second.date }) return null
        if (bars.any { b ->
            val o = b.open; val l = b.low
            o == null || l == null || !o.isFinite() || !l.isFinite() || !b.close.isFinite() || !b.high.isFinite() ||
                l <= 0 || b.high < max(o,b.close) || l > min(o,b.close) || b.volume == null || !b.volume.isFinite() || b.volume < 0
        }) return null
        fun ma(n: Int, offset: Int = 0) = bars.subList(bars.size-offset-n, bars.size-offset).map { it.close }.average()
        val last = bars.last()
        val prior = bars.dropLast(1).takeLast(p.lookback)
        val resistance = prior.maxOf { it.high }
        val support = prior.minOf { it.low!! }
        val avgVolume = bars.dropLast(1).takeLast(20).map { it.volume!! }.average()
        if (avgVolume <= 0) return null
        val ratio = last.volume!! / avgVolume
        val fast = ma(p.fast); val slow = ma(p.slow)
        val rising = fast > ma(p.fast,5)
        val slowRising = slow > ma(p.slow,5)
        val hits = mutableListOf<SetupHit>()
        fun bearish(index: Int): String? {
            val a = bars[index-1]; val b = bars[index]
            val body = abs(b.close-b.open!!); val span = b.high-b.low!!
            val upper = b.high-max(b.open,b.close); val lower = min(b.open,b.close)-b.low
            return when {
                a.close > a.open!! && b.close < b.open && b.open >= a.close && b.close <= a.open && body > abs(a.close-a.open) -> "看跌吞没"
                span > 0 && body/span in .05.. .35 && upper >= body*p.shadowRatio && lower <= body*.5 -> "流星线结构"
                else -> null
            }
        }
        fun bullish(index: Int): String? {
            val a = bars[index-1]; val b = bars[index]
            val body = abs(b.close-b.open!!); val span = b.high-b.low!!
            val lower = min(b.open,b.close)-b.low; val upper = b.high-max(b.open,b.close)
            return when {
                a.close < a.open!! && b.close > b.open && b.open <= a.close && b.close >= a.open && body > abs(a.close-a.open) -> "看涨吞没"
                span > 0 && body/span in .05.. .35 && lower >= body*p.shadowRatio && upper <= body*.5 -> "锤子线结构"
                else -> null
            }
        }
        for (offset in 0..1) {
            val i = bars.lastIndex-offset
            val formation = bars[i]
            val before = bars.subList(i-20,i)
            val upContext = bars[i-1].close > bars[i-6].close && bars[i-1].close > before.map { it.close }.average()
            val downContext = bars[i-1].close < bars[i-6].close && bars[i-1].close < before.map { it.close }.average()
            val bear = bearish(i)
            val bull = bullish(i)
            val nearHigh = formation.high >= before.maxOf { it.high }*(1-p.nearLevelPct/100)
            val nearLow = formation.low!! <= before.minOf { it.low!! }*(1+p.nearLevelPct/100)
            if (bear != null && upContext && nearHigh && (offset == 0 || last.close < formation.low)) {
                hits += SetupHit(StrategyPreset.TOP_RISK, if (offset == 1) "已跌破形态低点" else "待后续确认",
                    listOf("此前短期价格上行，接近前高", "$bear · ${formation.date}", "当前量比 ${decimal(ratio)}"),
                    "后续收盘跌破 ${decimal(formation.low)} 才确认转弱", "收盘越过形态高点 ${decimal(formation.high)}，该顶部结构失效",last.date)
                break
            }
            if (bull != null && downContext && nearLow && (offset == 0 || last.close > formation.high)) {
                hits += SetupHit(StrategyPreset.BOTTOM_WATCH, if (offset == 1) "已越过形态高点" else "待后续确认",
                    listOf("此前短期价格下行，接近前低", "$bull · ${formation.date}", "当前量比 ${decimal(ratio)}"),
                    "后续收盘越过 ${decimal(formation.high)}，再观察量能", "收盘跌破形态低点 ${decimal(formation.low)}，反转结构失效", last.date)
                break
            }
        }
        val recent = bars.takeLast(10); val older = bars.dropLast(10).takeLast(10)
        if (last.close < fast && fast < slow && !rising && fast < ma(p.fast,5) && slow < ma(p.slow,5) &&
            recent.maxOf { it.high } < older.maxOf { it.high } && recent.minOf { it.low!! } < older.minOf { it.low!! }) {
            hits += SetupHit(StrategyPreset.DOWNTREND,"趋势转弱",
                listOf("收盘 < MA${p.fast} < MA${p.slow}", "两条均线相较 5 根前下降", "相邻 10 根窗口高点、低点均下移"),
                "继续观察前低 ${decimal(support)} 是否守住", "收盘收复 MA${p.fast} ${decimal(fast)}，需重新评估",last.date)
        }
        val base = bars.dropLast(1).takeLast(p.baseDays)
        val baseHigh = base.maxOf { it.high }; val baseLow = base.minOf { it.low!! }
        val range = (baseHigh/baseLow-1)*100
        val preBase = bars.dropLast(p.baseDays+1).takeLast(20)
        val preBaseVolume = preBase.map { it.volume!! }.average()
        val contraction = if (preBaseVolume > 0) base.map { it.volume!! }.average()/preBaseVolume else Double.POSITIVE_INFINITY
        val priorPeak = preBase.maxOf { it.high }
        val retracement = (1-baseLow/priorPeak)*100
        if (slowRising && fast > slow && range <= p.rangePct && contraction <= p.contractionVolume &&
            retracement in 3.0..15.0 && baseLow >= slow*.98 && last.close >= baseLow && last.close <= baseHigh*(1+p.breakoutBufferPct/100)) {
            hits += SetupHit(StrategyPreset.BASE_WATCH,"等待突破，不预判主升",
                listOf("MA${p.slow} 上行；MA${p.fast} 在其上方", "整理 ${p.baseDays} 根振幅 ${decimal(range)}%", "整理量 / 前 20 根均量 ${decimal(contraction)}", "距整理前高点回撤 ${decimal(retracement)}%"),
                "收盘越过 ${decimal(baseHigh*(1+p.breakoutBufferPct/100))} 且量比 ≥ ${decimal(p.breakoutVolume)}，再观察突破", "收盘跌破整理低点 ${decimal(baseLow)}，整理结构失效",last.date)
        }
        if (last.close > resistance*(1+p.breakoutBufferPct/100) && ratio >= p.breakoutVolume &&
            last.close > fast && fast > slow && rising && slowRising && last.close > last.open!! &&
            (last.high-last.close) <= (last.high-last.low!!)*.3) {
            hits += SetupHit(StrategyPreset.BREAKOUT,"收盘与量能确认",
                listOf("收盘突破此前 ${p.lookback} 根高点 ${decimal(resistance)} + ${decimal(p.breakoutBufferPct,1)}%", "量比 ${decimal(ratio)} ≥ ${decimal(p.breakoutVolume)}", "收盘 > MA${p.fast} > MA${p.slow}，均线上行"),
                "后续关注突破位的回踩承接；不等同于已进入主升", "收盘回落至原阻力 ${decimal(resistance)} 下方，警惕假突破",last.date)
        }
        return hits
    }
}
