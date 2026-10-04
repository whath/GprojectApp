package cn.gproject

import kotlinx.datetime.LocalDate
import kotlin.test.*

class DetailReviewTest {
    private fun candles(scenario: Int) = demoScenario(List(280) { LocalDate.fromEpochDays(20000 + it).toString() }, scenario)

    @Test fun detailRespectsRadarParametersAndRetainsConfirmationAndInvalidation() {
        val data = candles(3)
        val review = reviewDetail(data, StrategyParameters())
        assertTrue(review.ready)
        val breakout = review.setups.single { it.preset == StrategyPreset.BREAKOUT }
        assertTrue(breakout.confirmation.isNotBlank())
        assertTrue(breakout.invalidation.isNotBlank())
        assertEquals(data.last().date, breakout.date)
        assertTrue(reviewDetail(data, StrategyParameters(breakoutVolume = 2.01)).setups.none { it.preset == StrategyPreset.BREAKOUT })
    }

    @Test fun missingVolumeOrInsufficientHistoryIsNotASafeOrNoSignalResult() {
        val data = candles(0)
        assertFalse(reviewDetail(data.takeLast(20), StrategyParameters()).ready)
        assertFalse(reviewDetail(data.dropLast(1) + data.last().copy(volume = null), StrategyParameters()).ready)
        assertFalse(reviewDetail(data.dropLast(1) + data.last().copy(volumeUnit = "share"), StrategyParameters()).ready)
        assertTrue(reviewDetail(data, StrategyParameters()).ready)
    }

    @Test fun dateMismatchIsDisclosedWithoutClaimingAStaleTradingSession() {
        assertNull(detailDateNote("2026-09-30", "2026-09-30"))
        assertNull(detailDateNote("", "2026-09-30"))
        assertNull(detailDateNote("2026-09-30", null))
        assertEquals("入口快照 2026-09-30；当前价量取自 2026-09-29，两者日期不同。", detailDateNote("2026-09-30", "2026-09-29"))
    }
}
