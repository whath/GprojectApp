package cn.gproject

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.runBlocking

class RepositoryTest {
    @Test
    fun monitoringRequestsUseAuthenticatedClassificationAndBoundedWindows() = runBlocking {
        val repository = MarketRepository(HttpClient(MockEngine { request ->
            assertEquals("Bearer test-only-token",request.headers[HttpHeaders.Authorization])
            when (request.url.encodedPath) {
                "/v1/events" -> {
                    assertEquals("100",request.url.parameters["limit"])
                    respond("""{"updated_at":"2026-10-02T00:00:00Z","items":[]}""",HttpStatusCode.OK)
                }
                "/v1/cn/boards/CN.industry.THS_1/flows" -> {
                    assertEquals("20",request.url.parameters["limit"])
                    assertEquals("ths",request.url.parameters["classification_source"])
                    respond("""{"board_id":"CN.industry.THS_1","classification_source":"ths","currency":"CNY","unit":"yuan","scope":"main","source":"test","updated_at":"2026-10-02T00:00:00Z","trade_dates":["2026-09-30"],"items":[{"trade_date":"2026-09-30","net":-123}]}""",HttpStatusCode.OK)
                }
                else -> error("Unexpected path")
            }
        }))
        try {
            repository.base="https://example.test"; repository.token="test-only-token"
            assertTrue(repository.events().events.isEmpty())
            val flow=repository.flows("CN.industry.THS_1")
            assertEquals(-123.0,flow.rows.single().net)
            assertNull(flow.rows.single().inflow)
            assertNull(summarizeFlows(flow,5).net)
        } finally { repository.close() }
    }

    @Test
    fun optionalEndpointsReportMissingWithoutInventingDemoData() = runBlocking {
        for (status in listOf(HttpStatusCode.NotFound,HttpStatusCode.NotImplemented)) {
            val repository=MarketRepository(HttpClient(MockEngine { respond("not available",status) }))
            try {
                repository.base="https://example.test"
                assertEquals("资金流接口待接入",assertFailsWith<IllegalStateException> { repository.flows("CN.industry.THS_1") }.message)
                assertEquals("热点事件接口待接入",assertFailsWith<IllegalStateException> { repository.events() }.message)
            } finally { repository.close() }
        }
    }

    @Test
    fun monitoringAuthenticationErrorsAreNotMisreportedAsMissingEndpoints() = runBlocking {
        val repository=MarketRepository(HttpClient(MockEngine { respond("private diagnostics",HttpStatusCode.Unauthorized) }))
        try {
            repository.base="https://example.test"
            assertEquals(401,assertFailsWith<ApiFailure> { repository.events() }.status)
            assertEquals(401,assertFailsWith<ApiFailure> { repository.flows("CN.industry.THS_1") }.status)
        } finally { repository.close() }
    }

    @Test
    fun stockHistoryUsesBoundedDailyContractAndPreservesUnits() = runBlocking {
        val repository = MarketRepository(HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("/flows")) return@MockEngine respond("not available", HttpStatusCode.NotFound)
            assertEquals("/v1/bars/CN.stock.688981", request.url.encodedPath)
            assertEquals("2000", request.url.parameters["limit"])
            assertEquals("2026-09-30", request.url.parameters["end"])
            respond("""{"adjustment":"none","instrument":{"currency":"CNY"},"items":[{"trade_date":"2026-09-30","open":10,"high":12,"low":9,"close":11,"volume":15,"volume_unit":"lot_100_shares","amount":16500,"source":"test","change_pct":1.2}]}""", HttpStatusCode.OK)
        }))
        try {
            repository.base = "https://example.test"
            val detail = repository.stockDetail(Quote("CN.stock.688981", "中芯国际", "688981"), "2026-09-30", null)
            assertEquals(10.0, detail.candles.single().open)
            assertEquals("lot_100_shares", detail.candles.single().volumeUnit)
            assertEquals(1.2, detail.candles.single().change)
            assertTrue(detail.minutes.isEmpty())
            assertFalse(detail.loading)
        } finally { repository.close() }
    }

    @Test
    fun incompleteOhlcIsNeverFabricated() = runBlocking {
        val repository = MarketRepository(HttpClient(MockEngine {
            respond("""{"adjustment":"none","items":[{"trade_date":"2026-09-30","high":12,"low":9,"close":11,"source":"test"}]}""", HttpStatusCode.OK)
        }))
        try {
            repository.base = "https://example.test"
            assertFailsWith<IllegalArgumentException> {
                repository.stockDetail(Quote("CN.stock.688981", "中芯国际", "688981"), "2026-09-30", null)
            }
            Unit
        } finally { repository.close() }
    }

    @Test
    fun authenticatedRequestParsesNullableApiFields() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("Bearer test-only-token", request.headers[HttpHeaders.Authorization])
            assertEquals("/v1/cn/rankings", request.url.encodedPath)
            assertEquals("ths", request.url.parameters["classification_source"])
            respond(
                """{"trade_date":"2026-09-29","items":[{"id":"CN.industry.THS_881121","name":"测试行业","price_change_pct":null}]}""",
                HttpStatusCode.OK,
            )
        }
        val repository = MarketRepository(HttpClient(engine))
        try {
            repository.base = "https://example.test/"
            repository.token = "test-only-token"
            val response =
                repository.get("/v1/cn/rankings", mapOf("classification_source" to "ths"))
            assertEquals("2026-09-29", response.str("trade_date"))
            assertNull(response.rows().single().num("price_change_pct"))
        } finally {
            repository.close()
        }
    }

    @Test
    fun unauthorizedResponseDoesNotExposeResponseBody() = runBlocking {
        val repository =
            MarketRepository(
                HttpClient(
                    MockEngine { respond("private diagnostics", HttpStatusCode.Unauthorized) }
                )
            )
        try {
            repository.base = "https://example.test"
            repository.token = "test-only-token"
            val error = assertFailsWith<IllegalStateException> { repository.get("/v1/status") }
            assertEquals("令牌无效或已过期，请重新连接", error.message)
        } finally {
            repository.close()
        }
    }
}
