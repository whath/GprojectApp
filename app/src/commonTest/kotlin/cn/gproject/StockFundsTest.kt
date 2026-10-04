package cn.gproject

import kotlinx.serialization.json.*
import kotlin.test.*

class StockFundsTest {
    private val instrumentId = "CN.stock.688981"
    private fun bar(date: String) = Candle(date, 10.0, 12.0, 9.0, 11.0, 100.0, null, "test", "share")
    private fun series(rows: List<StockFundDay>) = StockFundSeries(instrumentId, rows, "test", "CNY", "2026-10-01T00:00:00Z")
    private fun json(items: String = """[{"trade_date":"2026-09-30","net":-30}]""") = Json.parseToJsonElement("""
        {"instrument_id":"$instrumentId","scope":"main","currency":"CNY","unit":"yuan","source":"test","updated_at":"2026-10-01T00:00:00Z","items":$items}
    """).jsonObject

    @Test fun nullableGrossFlowsRemainUnknownAndTrueZeroNetIsPreserved() {
        val result = parseStockFunds(json("""[{"trade_date":"2026-09-30","net":0}]"""), instrumentId, "2026-09-30")
        assertEquals(0.0, result.rows.single().net)
        assertNull(result.rows.single().inflow)
        assertNull(result.rows.single().outflow)
        assertEquals("test", result.source)
    }

    @Test fun rejectsWrongInstrumentUnitsScopeSourceAndFutureDates() {
        val base = json()
        for ((key, value) in listOf("instrument_id" to "CN.stock.600036", "currency" to "USD", "unit" to "wan_yuan", "scope" to "all", "source" to "")) {
            val invalid = JsonObject(base + (key to JsonPrimitive(value)))
            assertFailsWith<IllegalArgumentException>(key) { parseStockFunds(invalid, instrumentId, "2026-09-30") }
        }
        assertFailsWith<IllegalArgumentException> { parseStockFunds(base, "CN.industry.THS_1", "2026-09-30") }
        assertFailsWith<IllegalArgumentException> { parseStockFunds(base, instrumentId, "2026-09-29") }
        assertFailsWith<IllegalArgumentException> { parseStockFunds(json("""[{"trade_date":"2026-09-30","source":"other","net":1}]"""), instrumentId, "2026-09-30") }
    }

    @Test fun rejectsDuplicateMissingNonfiniteAndContradictoryAmounts() {
        val invalidItems = listOf(
            """[{"trade_date":"2026-09-30","net":1},{"trade_date":"2026-09-30","net":2}]""",
            """[{"trade_date":"2026-09-30","net":null}]""",
            """[{"trade_date":"2026-09-30","net":"NaN"}]""",
            """[{"trade_date":"2026-09-30","net":1,"inflow":"invalid"}]""",
            """[{"trade_date":"2026-09-30","net":10,"inflow":-1}]""",
            """[{"trade_date":"2026-09-30","net":100,"inflow":20,"outflow":10}]""",
        )
        invalidItems.forEach { assertFailsWith<IllegalArgumentException> { parseStockFunds(json(it), instrumentId, "2026-09-30") } }
    }

    @Test fun dailyAlignmentPreservesGapsInsteadOfShiftingOrZeroFilling() {
        val bars = listOf(bar("2026-09-28"), bar("2026-09-29"), bar("2026-09-30"))
        val flows = series(listOf(StockFundDay("2026-09-30", null, null, -12.0), StockFundDay("2026-09-28", null, null, 0.0)))
        assertEquals(listOf(0.0, null, -12.0), alignedStockFunds(bars, bars, ChartPeriod.DAY, flows))
        assertEquals(listOf(null, null, null), alignedStockFunds(bars, bars, ChartPeriod.DAY, null))
        assertEquals(listOf(null, null, null), alignedStockFunds(bars, bars, ChartPeriod.INTRADAY, flows))
    }

    @Test fun weeklyAndMonthlyAggregationRequiresEveryObservedSessionIncludingCrossYearWeek() {
        val bars = listOf(bar("2025-12-29"), bar("2026-01-02"), bar("2026-01-05"))
        val partial = series(listOf(StockFundDay("2025-12-29", null, null, 10.0), StockFundDay("2026-01-05", null, null, -3.0)))
        val full = partial.copy(rows = partial.rows + StockFundDay("2026-01-02", null, null, -4.0))
        assertEquals(listOf(null, -3.0), alignedStockFunds(bars, aggregateCandles(bars, ChartPeriod.WEEK), ChartPeriod.WEEK, partial))
        assertEquals(listOf(6.0, -3.0), alignedStockFunds(bars, aggregateCandles(bars, ChartPeriod.WEEK), ChartPeriod.WEEK, full))
        assertEquals(listOf(10.0, null), alignedStockFunds(bars, aggregateCandles(bars, ChartPeriod.MONTH), ChartPeriod.MONTH, partial))
        assertEquals(listOf(10.0, -7.0), alignedStockFunds(bars, aggregateCandles(bars, ChartPeriod.MONTH), ChartPeriod.MONTH, full))
    }

    @Test fun simulationFundsAreExplicitlyLabeledAndCoverTheSameCandleDates() {
        val detail = demoStock(Quote("demo0", "中芯国际", "688981"))
        val funds = assertNotNull(detail.funds)
        assertEquals("模拟个股主力资金", funds.source)
        assertEquals(detail.candles.map { it.date }, funds.rows.map { it.date })
        funds.rows.forEach { assertEquals(it.net, it.inflow!! - it.outflow!!, 0.000001) }
        val us = demoStock(Quote("US.stock.NVDA", "NVDA", "NVDA"))
        assertTrue(assertNotNull(us.funds).rows.isEmpty())
    }
}
