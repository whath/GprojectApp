package cn.gproject

import kotlin.test.*

class CandlesTest {
    private fun bar(date: String, open: Double, close: Double, volume: Double? = 10.0, source: String = "test") =
        Candle(date, open, maxOf(open,close)+2, minOf(open,close)-2, close, volume, 100.0, source, "share")

    @Test fun calendarWeeksCrossYearsAndPreserveOhlc() {
        val result = aggregateCandles(listOf(bar("2026-01-05", 15.0,16.0),bar("2025-12-29",10.0,11.0),bar("2026-01-02",12.0,14.0)), ChartPeriod.WEEK)
        assertEquals(2,result.size)
        assertEquals(10.0,result[0].open)
        assertEquals(14.0,result[0].close)
        assertEquals(16.0,result[0].high)
        assertEquals(8.0,result[0].low)
        assertEquals(20.0,result[0].volume)
        assertEquals(200.0,result[0].amount)
        assertNull(result[0].change)
    }

    @Test fun calendarMonthsDoNotUseFixedTradingDayCounts() {
        val bars = listOf(bar("2026-01-30",10.0,12.0),bar("2026-02-02",12.0,13.0),bar("2026-02-27",13.0,15.0))
        val result = aggregateCandles(bars,ChartPeriod.MONTH)
        assertEquals(2,result.size)
        assertEquals(12.0,result[1].open)
        assertEquals(15.0,result[1].close)
    }

    @Test fun missingVolumeAndMixedUnitsStayUnknown() {
        val a = bar("2026-01-05",10.0,11.0)
        val b = bar("2026-01-06",11.0,12.0,null)
        assertNull(aggregateCandles(listOf(a,b),ChartPeriod.WEEK).single().volume)
        assertNull(aggregateCandles(listOf(a,b.copy(volume=15.0,volumeUnit="lot_100_shares")),ChartPeriod.WEEK).single().volume)
    }

    @Test fun rejectDuplicateDatesAndMixedSources() {
        val a = bar("2026-01-05",10.0,11.0)
        assertFailsWith<IllegalArgumentException> { aggregateCandles(listOf(a,a),ChartPeriod.DAY) }
        assertFailsWith<IllegalArgumentException> { aggregateCandles(listOf(a,a.copy(date="2026-01-06",source="other")),ChartPeriod.MONTH) }
        assertTrue(aggregateCandles(emptyList(),ChartPeriod.MONTH).isEmpty())
    }
}
