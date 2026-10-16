package cn.gproject

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class RevisionRepositoryTest {
    @Test fun fiveDayRankingNeverAcceptsUnadjustedStockAsAdjustedReturn() {
        val data=Json.parseToJsonElement("""{"period":"5d","period_sessions":5,"trade_date":"2026-10-15","eligible_count":1,"expected_count":10,"items":[{"id":"CN.stock.000001","name":"平安银行","symbol":"000001","trade_date":"2026-10-15","source":"test","adjustment":"none","close":10,"change_5d_pct":5,"rank":1}]}""").jsonObject
        assertFailsWith<IllegalArgumentException> { parseDailyRanking(data) }
    }

    @Test fun rankingPagesKeepOneVersionAndDoNotCombineRefreshWithSnapshot(): Unit = runBlocking {
        val version="a".repeat(64);var calls=0
        val api=MarketRepository(HttpClient(MockEngine { request ->
            val offset=request.url.parameters["offset"]!!.toInt()
            if(offset==0) assertEquals("true",request.url.parameters["refresh"])
            else { assertNull(request.url.parameters["refresh"]);assertEquals(version,request.url.parameters["snapshot_id"]) }
            calls++
            respond("""{"snapshot_id":"$version","total":2,"items":[{"id":"$offset"}]}""",HttpStatusCode.OK)
        }),requestSpacingMillis=0)
        try { api.base="https://example.test";api.token="test-only";assertEquals(2,api.dailyPages("/v1/list",mapOf("refresh" to "true")).rows().size);assertEquals(2,calls) }
        finally { api.close() }
    }

    @Test fun macroYieldsRejectPriceReturnUnits(): Unit = runBlocking {
        val api=MarketRepository(HttpClient(MockEngine {
            respond("""{"items":[{"asset_id":"us10y","name":"10年期美债收益率","value":5.1,"unit":"percent","change_unit":"percent","data_date":"2026-10-15","source_age_days":1}]}""",HttpStatusCode.OK)
        }),requestSpacingMillis=0)
        try { api.base="https://example.test";assertFailsWith<IllegalArgumentException> { api.dailyMacro() } }
        finally { api.close() }
    }

    @Test fun dailyDetailRejectsHistoryBeyondThirtySessions(): Unit = runBlocking {
        val rows=(1..31).joinToString(",") { """{"trade_date":"2026-10-15","open":10,"high":11,"low":9,"close":10,"source":"test"}""" }
        val api=MarketRepository(HttpClient(MockEngine { request ->
            assertEquals("30",request.url.parameters["limit"])
            respond("""{"instrument":{"id":"CN.stock.000001","currency":"CNY"},"adjustment":"none","window_sessions":30,"items":[$rows]}""",HttpStatusCode.OK)
        }),requestSpacingMillis=0)
        try { api.base="https://example.test";assertFailsWith<IllegalArgumentException> { api.dailyDetail(DailyAsset("CN.stock.000001","平安银行","000001",date="2026-10-15"),DailyOverview()) } }
        finally { api.close() }
    }

    @Test fun watchPersistenceDoesNotCarryModelConfigurationOrCredentials() {
        val encoded=encodeDailyWatch(listOf(DailyAsset("CN.stock.000001","平安银行","000001"),DailyAsset("US.stock.NVDA","NVDA","NVDA",periodSessions=1)))
        assertFalse(encoded.contains("token"));assertFalse(encoded.contains("ai"));assertFalse(encoded.contains("model"))
        assertEquals(2,Json.parseToJsonElement(encoded).jsonObject["stocks"]!!.jsonArray.size)
    }
}
