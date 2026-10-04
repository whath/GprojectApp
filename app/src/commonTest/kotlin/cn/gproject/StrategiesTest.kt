package cn.gproject

import kotlin.test.*
import kotlinx.datetime.LocalDate

class StrategiesTest {
    @Test fun longTrendRequiresFull125DayBaselineAndFiveEarlierBars() {
        val data=fixture(0)
        assertNull(StrategyEngine.evaluate(data.takeLast(129),StrategyParameters()))
        assertNotNull(StrategyEngine.evaluate(data.takeLast(130),StrategyParameters()))
        assertNotNull(StrategyEngine.evaluate(data,StrategyParameters(fast=3,slow=8)))
        assertNull(StrategyEngine.evaluate(data,StrategyParameters(fast=10,slow=60)))
    }
    private fun fixture(scenario: Int): List<Bar> = demoScenario(List(280) { LocalDate.fromEpochDays(20000+it).toString() },scenario)
        .map { Bar(it.date,it.close,it.high,it.volume,it.source,it.open,it.low,it.volumeUnit) }
    private fun hits(data: List<Bar>,params: StrategyParameters=StrategyParameters()) = assertNotNull(StrategyEngine.evaluate(data,params))

    @Test fun fiveScenariosHaveDistinctExplainableSignals() {
        listOf(StrategyPreset.TOP_RISK,StrategyPreset.DOWNTREND,StrategyPreset.BASE_WATCH,StrategyPreset.BREAKOUT,StrategyPreset.BOTTOM_WATCH).forEachIndexed { i,preset ->
            val hit = hits(fixture(i)).single { it.preset==preset }
            assertTrue(hit.evidence.isNotEmpty())
            assertTrue(hit.invalidation.isNotBlank())
            if(preset==StrategyPreset.BASE_WATCH) assertTrue(hit.status.contains("不预判"))
        }
    }

    @Test fun bearishShapeNeedsTrendContextAndLaterConfirmationIsCausal() {
        val data = fixture(0)
        val initial = hits(data).single { it.preset==StrategyPreset.TOP_RISK }
        assertEquals("待后续确认",initial.status)
        val shape = data.last()
        val next = shape.copy(date=LocalDate.parse(shape.date).let { LocalDate.fromEpochDays(it.toEpochDays()+1).toString() },open=shape.close,close=shape.low!!-.5,low=shape.low-1)
        assertEquals("已跌破形态低点",hits(data+next).single { it.preset==StrategyPreset.TOP_RISK }.status)
        // A bearish-looking last candle after a falling history is not a top reversal.
        val falling = fixture(1).toMutableList()
        falling[falling.lastIndex] = shape.copy(date=falling.last().date)
        assertTrue(hits(falling).none { it.preset==StrategyPreset.TOP_RISK })
    }

    @Test fun breakoutNeedsVolumeAndExcludesTodayFromBaseline() {
        val bars = fixture(3)
        assertTrue(hits(bars).any { it.preset==StrategyPreset.BREAKOUT })
        assertTrue(hits(bars.dropLast(1)+bars.last().copy(volume=110000.0)).none { it.preset==StrategyPreset.BREAKOUT })
        assertTrue(hits(bars,StrategyParameters(breakoutVolume=2.01)).none { it.preset==StrategyPreset.BREAKOUT })
        val signal = assertNotNull(Indicators.calculate(Quote("x","x","x"),bars,Rules(preset=StrategyPreset.BREAKOUT)))
        assertEquals(2.0,signal.volume)
        assertTrue(Indicators.matches(signal,Rules(preset=StrategyPreset.BREAKOUT,rsiMax=0))) // custom conditions do not veto a preset
    }

    @Test fun incompleteOrInconsistentOhlcNeverCreatesASetup() {
        val bars = fixture(0)
        assertNull(StrategyEngine.evaluate(bars.takeLast(20),StrategyParameters()))
        assertNull(StrategyEngine.evaluate(bars.dropLast(1)+bars.last().copy(open=null),StrategyParameters()))
        assertNull(StrategyEngine.evaluate(bars.dropLast(1)+bars.last().copy(source="other"),StrategyParameters()))
        assertNull(StrategyEngine.evaluate(bars.dropLast(1)+bars.last().copy(volumeUnit="share"),StrategyParameters()))
        assertNull(StrategyEngine.evaluate(bars,StrategyParameters(rangePct=Double.NaN)))
    }

    @Test fun parametersChangeSelection() {
        assertTrue(hits(fixture(2)).any { it.preset==StrategyPreset.BASE_WATCH })
        assertTrue(hits(fixture(2),StrategyParameters(contractionVolume=.2)).none { it.preset==StrategyPreset.BASE_WATCH })
        assertTrue(hits(fixture(3),StrategyParameters(breakoutBufferPct=5.0)).none { it.preset==StrategyPreset.BREAKOUT })
    }
}
