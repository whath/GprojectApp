package cn.gproject

/** Daily chart defaults. A period counts available trading sessions, never calendar days. */
internal val DAILY_MA_PERIODS = listOf(3, 5, 8, 12, 15, 20, 125)

/**
 * Returns one SMA series per requested period, each aligned with [bars].
 * Input must be oldest first. A period N needs N closes; earlier points remain null.
 * The current close is included, and future bars never affect an existing value.
 */
internal fun movingAverages(bars: List<Candle>, periods: List<Int>): List<List<Double?>> {
    require(periods.all { it > 0 }) { "均线周期必须大于 0" }
    validateIndicatorCloses(bars)
    return periods.map { period ->
        bars.indices.map { index ->
            if (index + 1 < period) null
            else (index + 1 - period..index).sumOf { bars[it].close / period }
        }
    }
}

internal data class MacdPoint(val dif: Double, val dea: Double, val histogram: Double)

/**
 * MACD(12,26,9), aligned with the oldest-first input series.
 * EMA_N is seeded with the SMA of its first N observations, then uses alpha=2/(N+1).
 * DIF=EMA12(close)-EMA26(close); DEA=EMA9(DIF); histogram=2*(DIF-DEA).
 * DEA therefore first becomes available on bar 34 (index 33). Earlier complete
 * MACD points remain null; missing warm-up values are never presented as zeros.
 * The doubled histogram is this app's explicit domestic-chart display convention.
 * Values depend on the supplied history and can differ from platforms with other
 * EMA seeds or a longer warm-up history. No future observation is read.
 */
internal fun calculateMacd(bars: List<Candle>): List<MacdPoint?> {
    validateIndicatorCloses(bars)
    if (bars.size < 34) return List(bars.size) { null }
    val closes = bars.map { it.close }
    val fast = seededEma(closes, 12)
    val slow = seededEma(closes, 26)
    val dif = (25 until bars.size).map { fast[it]!! - slow[it]!! }
    val dea = seededEma(dif, 9)
    return bars.indices.map { index ->
        if (index < 33) null
        else {
            val difference = dif[index - 25]
            val signal = dea[index - 25]!!
            MacdPoint(difference, signal, 2 * (difference - signal))
        }
    }
}

private fun seededEma(values: List<Double>, period: Int): List<Double?> {
    val result = MutableList<Double?>(values.size) { null }
    if (values.size < period) return result
    var previous = values.take(period).sumOf { it / period }
    result[period - 1] = previous
    val alpha = 2.0 / (period + 1)
    for (index in period until values.size) {
        previous = alpha * values[index] + (1 - alpha) * previous
        result[index] = previous
    }
    return result
}

private fun validateIndicatorCloses(bars: List<Candle>) {
    require(bars.all { it.close.isFinite() && it.close > 0 }) { "收盘价必须为正有限数值" }
    require(bars.zipWithNext().all { (before, after) -> before.date < after.date }) { "指标行情必须按日期升序且不可重复" }
}
