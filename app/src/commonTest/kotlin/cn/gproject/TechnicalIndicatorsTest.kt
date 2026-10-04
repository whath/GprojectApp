package cn.gproject

import kotlinx.datetime.LocalDate
import kotlin.test.*

class TechnicalIndicatorsTest {
    private fun bars(closes: List<Double>): List<Candle> = closes.mapIndexed { index, close ->
        Candle(LocalDate.fromEpochDays(LocalDate(2025, 1, 1).toEpochDays() + index).toString(),
            close, close, close, close, 100.0, 100.0 * close, "test", "share")
    }

    @Test fun dailyAveragesHaveRequiredPeriodsAndCompleteWarmup() {
        assertEquals(listOf(3, 5, 8, 12, 15, 20, 125), DAILY_MA_PERIODS)
        val closes = (1..130).map { it.toDouble() }
        val averages = movingAverages(bars(closes), DAILY_MA_PERIODS)
        DAILY_MA_PERIODS.forEachIndexed { index, period ->
            val series = averages[index]
            assertEquals(closes.size, series.size)
            assertTrue(series.take(period - 1).all { it == null })
            assertEquals((period + 1) / 2.0, series[period - 1]!!, 1e-10)
            assertEquals((261 - period) / 2.0, series.last()!!, 1e-10)
        }
    }

    @Test fun macdNeedsThirtyFourBarsAndDoesNotFabricateZeroWarmup() {
        for (count in listOf(0, 1, 12, 26, 33)) {
            val result = calculateMacd(bars(List(count) { 50.0 }))
            assertEquals(count, result.size)
            assertTrue(result.all { it == null })
        }
        val result = calculateMacd(bars(List(40) { 50.0 }))
        assertTrue(result.take(33).all { it == null })
        result.drop(33).forEach { point ->
            assertNotNull(point)
            assertEquals(0.0, point.dif, 1e-10)
            assertEquals(0.0, point.dea, 1e-10)
            assertEquals(0.0, point.histogram, 1e-10)
        }
    }

    @Test fun macdUsesSmaSeedsAndDoubledHistogramWithExpectedImpulseResponse() {
        // Linear closes give EMA12(t)=t-5.5 and EMA26(t)=t-12.5 after each seed.
        val linear = calculateMacd(bars((1..40).map { it.toDouble() }))
        linear.drop(33).forEach { point ->
            assertEquals(7.0, point!!.dif, 1e-10)
            assertEquals(7.0, point.dea, 1e-10)
            assertEquals(0.0, point.histogram, 1e-10)
        }
        // After flat warm-up, a +13 close gives DIF=13*(2/13-2/27)=28/27.
        val impulse = calculateMacd(bars(List(34) { 50.0 } + 63.0)).last()!!
        assertEquals(28.0 / 27, impulse.dif, 1e-10)
        assertEquals(28.0 / 135, impulse.dea, 1e-10)
        assertEquals(224.0 / 135, impulse.histogram, 1e-10)
        val negative = calculateMacd(bars(List(34) { 50.0 } + 37.0)).last()!!
        assertEquals(-impulse.dif, negative.dif, 1e-10)
        assertEquals(-impulse.dea, negative.dea, 1e-10)
        assertEquals(-impulse.histogram, negative.histogram, 1e-10)
    }

    @Test fun appendingFutureBarsCannotChangeExistingIndicatorValues() {
        val original = bars(List(140) { index -> 20.0 + index % 13 + index * .07 })
        val extended = original + bars(List(165) { 1000.0 }).drop(original.size)
        val expectedMa = movingAverages(original, DAILY_MA_PERIODS)
        val actualMa = movingAverages(extended, DAILY_MA_PERIODS)
        expectedMa.indices.forEach { assertEquals(expectedMa[it], actualMa[it].take(original.size)) }
        assertEquals(calculateMacd(original), calculateMacd(extended).take(original.size))
    }

    @Test fun invalidClosesPeriodsAndDateOrderAreRejected() {
        val original = bars(List(40) { 20.0 })
        for (close in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0)) {
            val invalid = original.toMutableList().apply { this[10] = this[10].copy(close = close) }
            assertFailsWith<IllegalArgumentException> { movingAverages(invalid, DAILY_MA_PERIODS) }
            assertFailsWith<IllegalArgumentException> { calculateMacd(invalid) }
        }
        assertFailsWith<IllegalArgumentException> { movingAverages(original, listOf(0)) }
        assertFailsWith<IllegalArgumentException> { movingAverages(original.reversed(), DAILY_MA_PERIODS) }
        assertFailsWith<IllegalArgumentException> { calculateMacd(original + original.last()) }
        assertTrue(movingAverages(emptyList(), DAILY_MA_PERIODS).all { it.isEmpty() })
    }
}
