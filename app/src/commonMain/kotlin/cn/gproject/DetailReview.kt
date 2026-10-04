package cn.gproject

internal data class DetailReview(val ready: Boolean, val setups: List<SetupHit>)

/** Uses the same causal rules as the radar, with risk conditions shown first. */
internal fun reviewDetail(candles: List<Candle>, parameters: StrategyParameters): DetailReview {
    val bars = candles.map { Bar(it.date, it.close, it.high, it.volume, it.source, it.open, it.low, it.volumeUnit) }
    val setups = StrategyEngine.evaluate(bars, parameters) ?: return DetailReview(false, emptyList())
    return DetailReview(true, orderedReviewSetups(setups))
}

internal fun detailDateNote(quoteDate: String, candleDate: String?): String? = when {
    candleDate == null || quoteDate.isBlank() || quoteDate == candleDate -> null
    else -> "入口快照 $quoteDate；当前价量取自 $candleDate，两者日期不同。"
}
