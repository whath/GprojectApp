package cn.gproject

import kotlinx.datetime.Instant
import kotlinx.serialization.json.*
import kotlin.test.*

class IntelligenceTest {
    @Test fun eventParserUsesLatestRevisionAndRejectsUnsafeOrUnroutedSources() {
        val event="""{"id":"event-1","title":"测试事件","summary":"测试","markets":["US","GOLD"],"importance":"high","kind":"news","occurs_at":"2026-10-02T00:00:00Z","published_at":"2026-10-02T00:00:00Z","expires_at":"2026-10-03T00:00:00Z","updated_at":"2026-10-02T01:00:00Z","source":"test","url":"https://example.test/news"}"""
        fun parse(e: String)=parseEvents(Json.parseToJsonElement("""{"updated_at":"2026-10-02T02:00:00Z","items":[$e]}""").jsonObject)
        val revised=event.replace("01:00:00Z","02:00:00Z").replace("测试事件","更新事件")
        assertEquals("更新事件",parse("$revised,$event").events.single().title)
        assertFailsWith<IllegalArgumentException> { parse(event.replace("https://","http://")) }
        assertFailsWith<IllegalArgumentException> { parse(event.replace("[\"US\",\"GOLD\"]","[]")) }
        assertFailsWith<IllegalArgumentException> { parse(event.replace("2026-10-03","2026-10-01")) }
    }
    private val json = """{"board_id":"CN.industry.THS_1","classification_source":"ths","currency":"CNY","unit":"yuan","scope":"main","source":"test","updated_at":"2026-10-02T00:00:00Z","trade_dates":["2026-09-24","2026-09-25","2026-09-28","2026-09-29","2026-09-30"],"items":[{"trade_date":"2026-09-24","inflow":150,"outflow":50,"net":100},{"trade_date":"2026-09-25","net":-20},{"trade_date":"2026-09-28","net":-30},{"trade_date":"2026-09-29","net":-40},{"trade_date":"2026-09-30","net":10}]}"""
    private fun series(text: String=json) = parseFlows(Json.parseToJsonElement(text).jsonObject,"CN.industry.THS_1")
    @Test fun flowTotalsSeparatePositiveAndNegativeNetAndDoNotInferGross() {
        val data = series(); val summary = summarizeFlows(data,5)
        assertEquals(20.0,summary.net)
        assertEquals(110.0,summary.positive)
        assertEquals(90.0,summary.negative)
        assertNull(data.rows[1].inflow)
        assertEquals(0,summary.outflowStreak)
    }
    @Test fun missingTradingDaysAndFailuresInvalidateTotalsAndBreakStreaks() {
        val data=series()
        assertNull(summarizeFlows(data.copy(rows=data.rows.drop(1)),5).net)
        assertNull(summarizeFlows(data,10).net)
        assertNull(summarizeFlows(data.copy(error="更新失败"),5).net)
        val negative = data.copy(rows=data.rows.map { it.copy(net=-20.0) })
        assertEquals(5,summarizeFlows(negative,5).outflowStreak)
        assertEquals(1,summarizeFlows(negative.copy(rows=negative.rows.filterIndexed { i,_ -> i!=3 }),5).outflowStreak)
    }
    @Test fun incompatibleClassificationUnitsAndNetAreRejected() {
        assertFailsWith<IllegalArgumentException> { series(json.replace("ths","eastmoney")) }
        assertFailsWith<IllegalArgumentException> { series(json.replace("yuan","wan_yuan")) }
        assertFailsWith<IllegalArgumentException> { series(json.replace("\"net\":100","\"net\":200")) }
    }
    @Test fun eventsRouteByMarketExpireAndRetainRevisionIdentity() {
        val now=Instant.parse("2026-10-02T00:00:00Z")
        val feed=demoEvents(now)
        assertEquals(1,eventsFor(feed,MarketScope.US,now).size)
        assertEquals(eventsFor(feed,MarketScope.US,now).single().id,eventsFor(feed,MarketScope.GOLD,now).single().id)
        assertTrue(eventsFor(feed,MarketScope.JP,now).none { MarketScope.KR in it.markets })
        assertTrue(eventsFor(feed,MarketScope.US,Instant.parse("2026-10-05T00:00:00Z")).isEmpty())
        val event=feed.events.first()
        assertNotEquals(event.revision,event.copy(updatedAt=now.toString()).revision)
    }
}
