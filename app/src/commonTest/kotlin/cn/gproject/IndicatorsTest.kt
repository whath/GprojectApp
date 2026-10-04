package cn.gproject

import kotlin.test.*

class IndicatorsTest {
    @Test fun ma125Requires125DailyObservations() {
        val data=demoStock(Quote("demo","测试","688981")).candles.map { Bar(it.date,it.close,it.high,it.volume,it.source,it.open,it.low,it.volumeUnit) }
        assertNull(Indicators.calculate(quote,data.takeLast(124),Rules(ma=125)))
        val signal=assertNotNull(Indicators.calculate(quote,data.takeLast(125),Rules(ma=125)))
        assertEquals(data.takeLast(125).map { it.close }.average(),signal.ma,1e-9)
    }
    private val quote = Quote("CN.stock.000001", "测试", "000001")

    private fun bars() =
        (1..80).map {
            Bar(
                "2026-${((it-1)/28+1).toString().padStart(2,'0')}-${((it-1)%28+1).toString().padStart(2,'0')}",
                it.toDouble() + 10,
                it.toDouble() + 10.5,
                100.0,
                "fixture",
                volumeUnit="share",
            )
        }

    @Test
    fun volumeRatiosRequireConsistentKnownUnitsAndSource() {
        assertNull(Indicators.calculate(quote,bars().map { it.copy(source="") },Rules()))
        assertNull(Indicators.calculate(quote,bars().map { it.copy(volumeUnit="unknown") },Rules()))
        assertNull(Indicators.calculate(quote,bars().dropLast(1)+bars().last().copy(volumeUnit="lot_100_shares"),Rules()))
    }

    @Test
    fun trendAndVolumeExcludeTodayFromBaseline() {
        val data = bars().toMutableList()
        data[data.lastIndex] = data.last().copy(volume = 250.0)
        val s = assertNotNull(Indicators.calculate(quote, data, Rules()))
        assertEquals(80.5, s.ma)
        assertEquals(100.0, s.rsi)
        assertEquals(2.5, s.volume)
        assertTrue(s.breakout)
        assertFalse(Indicators.matches(s, Rules()))
        assertTrue(Indicators.matches(s, Rules(rsiMax = 100, breakout = true)))
    }

    @Test
    fun flatPricesHaveNeutralRsi() {
        val s =
            assertNotNull(
                Indicators.calculate(
                    quote,
                    bars().map { it.copy(close = 20.0, high = 20.0) },
                    Rules(),
                )
            )
        assertEquals(50.0, s.rsi)
        assertFalse(s.breakout)
        assertFalse(Indicators.matches(s, Rules()))
        assertTrue(Indicators.matches(s, Rules(aboveMa = false)))
    }

    @Test
    fun invalidHistoryIsExcluded() {
        assertNull(Indicators.calculate(quote, bars().take(14), Rules()))
        assertNull(
            Indicators.calculate(
                quote,
                bars().mapIndexed { i, b -> if (i == 60) b.copy(volume = null) else b },
                Rules(),
            )
        )
        assertNull(
            Indicators.calculate(
                quote,
                bars().mapIndexed { i, b -> if (i == 60) b.copy(source = "other") else b },
                Rules(),
            )
        )
        assertNull(Indicators.calculate(quote, bars().reversed(), Rules()))
        assertNull(Indicators.calculate(quote, bars().map { it.copy(volume = 0.0) }, Rules()))
    }

    @Test
    fun fallingPricesHaveZeroRsi() {
        val s =
            assertNotNull(
                Indicators.calculate(
                    quote,
                    bars().mapIndexed { i, b -> b.copy(close = 100.0 - i, high = 100.5 - i) },
                    Rules(),
                )
            )
        assertEquals(0.0, s.rsi)
        assertFalse(s.breakout)
    }

    @Test
    fun numberFormattingPreservesSignAndPadding() {
        assertEquals("−0.05", decimal(-.05))
        assertEquals("1.00", decimal(.999))
        assertEquals("—", decimal(null))
    }
}
