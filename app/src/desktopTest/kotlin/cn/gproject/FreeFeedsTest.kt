package cn.gproject

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class FreeFeedsTest {
    @Test fun minutePricesPreserveExplicitDateAndRejectWrongTimezone() {
        val wire="""{"instrument_id":"CN.stock.000001","trade_date":"2026-10-15","timezone":"Asia/Shanghai","period":"1m","adjustment":"none","source":"akshare/test","items":[{"timestamp":"2026-10-15T09:30:00+08:00","open":null,"close":10.1}]}"""
        assertEquals(10.1, parseMinutes(Json.parseToJsonElement(wire).jsonObject,"CN.stock.000001","2026-10-15").points.single().price)
        assertFailsWith<IllegalArgumentException> { parseMinutes(Json.parseToJsonElement(wire.replace("+08:00","+09:00")).jsonObject,"CN.stock.000001","2026-10-15") }
    }

    @Test fun valuationDoesNotRelabelOldOrFutureDataAndRetainsNegativePe() {
        val wire="""{"instrument_id":"CN.stock.000001","currency":"CNY","amount_unit":"yuan","source":"akshare/test","items":[{"trade_date":"2026-10-14","pe_ttm":-5}]}"""
        val data=Json.parseToJsonElement(wire).jsonObject
        assertEquals("2026-10-14",parseMetrics(data,"CN.stock.000001","2026-10-15").date)
        assertEquals(-5.0,parseMetrics(data,"CN.stock.000001","2026-10-15").peTtm)
        assertFailsWith<IllegalArgumentException> { parseMetrics(data,"CN.stock.000001","2026-10-13") }
    }

    @Test fun lhbTraversesAllPagesWithPinnedDateAndSnapshot() = runBlocking {
        val version="a".repeat(64)
        var calls=0
        val repository=MarketRepository(HttpClient(MockEngine { request ->
            val offset=request.url.parameters["offset"]!!.toInt()
            if(offset>0) { assertEquals(version,request.url.parameters["snapshot_id"]);assertEquals("2026-10-15",request.url.parameters["trade_date"]) }
            calls++
            val count=if(offset==0) 500 else 1
            val rows=(offset until offset+count).joinToString(",") { """{"key":"$it","symbol":"000001","name":"sample","reason":"reason$it"}""" }
            respond("""{"trade_date":"2026-10-15","snapshot_id":"$version","total":501,"items":[$rows]}""",HttpStatusCode.OK)
        }),requestSpacingMillis=0)
        try { repository.base="https://example.test";assertEquals(501,repository.disclosures().leaders.size);assertEquals(2,calls) }
        finally { repository.close() }
    }

    @Test fun missingOptionalSeriesCannotEraseDailyChart() = runBlocking {
        val repository=MarketRepository(HttpClient(MockEngine { request ->
            if(request.url.encodedPath.startsWith("/v1/bars/")) respond("""{"adjustment":"none","instrument":{"currency":"CNY"},"items":[{"trade_date":"2026-10-15","open":10,"high":12,"low":9,"close":11,"volume":1,"volume_unit":"share","source":"test"}]}""",HttpStatusCode.OK)
            else respond("unavailable",HttpStatusCode.ServiceUnavailable)
        }),requestSpacingMillis=0)
        try {
            repository.base="https://example.test"
            val detail=repository.stockDetail(Quote("CN.stock.000001","sample","000001"),"2026-10-15",null)
            assertEquals(1,detail.candles.size);assertTrue(detail.minutes.isEmpty());assertNotNull(detail.minuteFeed?.error);assertNotNull(detail.metrics?.error)
            Unit
        } finally { repository.close() }
    }
}
