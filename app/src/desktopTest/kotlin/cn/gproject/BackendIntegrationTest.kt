package cn.gproject

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class BackendIntegrationTest {
    private val versionA = "a".repeat(64)
    private val versionB = "b".repeat(64)
    private fun rows(start: Int, count: Int) = (start until start + count).joinToString(",") {
        val code = it.toString().padStart(6, '0')
        """{"id":"CN.stock.$code","symbol":"$code","name":"stock$it"}"""
    }

    @Test fun catalogKeepsTheFirstSnapshotAcrossPages() = runBlocking {
        val repository = MarketRepository(HttpClient(MockEngine { request ->
            val offset = request.url.parameters["offset"]!!.toInt()
            assertEquals(if (offset == 0) null else versionA, request.url.parameters["snapshot_id"])
            respond("""{"snapshot_id":"$versionA","total":501,"items":[${rows(offset, if (offset == 0) 500 else 1)}]}""", HttpStatusCode.OK)
        }), requestSpacingMillis = 0)
        try { repository.base = "https://example.test"; assertEquals(501, repository.catalog().size) }
        finally { repository.close() }
    }

    @Test fun expiredSnapshotRestartsOnceWithoutKeepingOldRows() = runBlocking {
        var restarted = false
        val repository = MarketRepository(HttpClient(MockEngine { request ->
            val offset = request.url.parameters["offset"]!!.toInt()
            if (offset > 0 && !restarted) return@MockEngine respond("{}", HttpStatusCode.Conflict)
            if (request.url.parameters["refresh"] == "true") restarted = true
            val version = if (restarted) versionB else versionA
            if (offset > 0) assertEquals(versionB, request.url.parameters["snapshot_id"])
            respond("""{"snapshot_id":"$version","total":501,"items":[${rows(offset, if (offset == 0) 500 else 1)}]}""", HttpStatusCode.OK)
        }), requestSpacingMillis = 0)
        try {
            repository.base = "https://example.test"
            val all = repository.catalog()
            assertTrue(restarted); assertEquals(501, all.map { it.str("id") }.distinct().size)
        } finally { repository.close() }
    }

    @Test fun membershipUsesItsOwnSnapshotAcrossFiveHundredRows() = runBlocking {
        val repository = MarketRepository(HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/v1/instruments") return@MockEngine respond(
                """{"snapshot_id":"$versionA","total":1,"items":[{"id":"CN.industry.THS_881121","symbol":"THS_881121","name":"industry"}]}""", HttpStatusCode.OK)
            val offset = request.url.parameters["offset"]!!.toInt()
            assertEquals(if (offset == 0) null else versionB, request.url.parameters["snapshot_id"])
            respond("""{"snapshot_id":"$versionB","trade_date":"2026-10-14","task_status":[{"status":"complete"}],"items":[${rows(offset, if (offset == 0) 500 else 1)}]}""", HttpStatusCode.OK)
        }), requestSpacingMillis = 0)
        try {
            repository.base = "https://example.test"
            val index = repository.membershipIndex("2026-10-14")
            assertTrue(index.complete); assertEquals(501, index.bySymbol.size)
        } finally { repository.close() }
    }

    @Test fun htmlRateLimitIsRetriedButAuthenticationIsNot() = runBlocking {
        var calls = 0
        val repository = MarketRepository(HttpClient(MockEngine { request ->
            assertEquals("Bearer test-only-token", request.headers[HttpHeaders.Authorization])
            calls++
            when (calls) {
                1 -> respond("<html>busy</html>", HttpStatusCode.TooManyRequests)
                2 -> respond("{}", HttpStatusCode.OK)
                else -> respond("private diagnostic", HttpStatusCode.Unauthorized)
            }
        }), requestSpacingMillis = 0)
        try {
            repository.base = "https://example.test"; repository.token = "test-only-token"
            repository.get("/v1/status")
            assertEquals(2, calls)
            assertEquals(401, assertFailsWith<ApiFailure> { repository.get("/v1/status") }.status)
            assertEquals(3, calls)
        } finally { repository.close() }
    }

    @Test fun liveBackendContractWhenExplicitlyConfigured() = runBlocking {
        val base = System.getenv("GPROJECT_API_BASE")
        val token = System.getenv("GPROJECT_API_TOKEN")
        org.junit.Assume.assumeTrue("Live API credentials not supplied", base != null && token != null)
        val date = System.getenv("GPROJECT_API_TEST_DATE") ?: error("Explicit validation date required")
        val repository = MarketRepository()
        try {
            repository.base = requireNotNull(base); repository.token = requireNotNull(token)
            assertEquals("akshare", repository.get("/v1/capabilities").str("provider"))
            val catalog = repository.catalog()
            assertTrue(catalog.size > 500); assertEquals(catalog.size, catalog.map { it.str("id") }.distinct().size)
            val detail = repository.stockDetail(Quote("CN.stock.000001", "平安银行", "000001"), date, null)
            assertTrue(detail.candles.size >= 250)
            assertEquals(date, detail.candles.last().date)
            assertNull(detail.funds?.error); assertTrue(detail.funds!!.rows.size >= 100)
            assertEquals(241,detail.minutes.size);assertNull(detail.minuteFeed?.error)
            assertEquals(date,detail.metrics?.date);assertNull(detail.metrics?.error)
            assertTrue(detail.metrics!!.totalCap!! > 0);assertNotNull(detail.metrics!!.turnover)
            assertEquals(7,repository.referenceIndices().size)
            assertEquals(11,repository.usSectorQuotes().size)
            assertTrue(repository.disclosures().leaders.isNotEmpty())
            val prior = repository.get("/v1/cn/lhb",mapOf("trade_date" to "2026-10-14","limit" to "1","refresh" to "true")).rows().first()
            val seats = repository.seatFeed(prior.str("symbol"),"2026-10-14")
            assertNull(seats.error);assertTrue(seats.items.isNotEmpty())
            assertTrue(seats.items.any { it.direction == "买入" } && seats.items.any { it.direction == "卖出" })
            assertTrue(repository.limitUpFeed().items.isNotEmpty())
            assertTrue(repository.officialAgenda().items.isNotEmpty())
            val news = repository.events()
            assertTrue(news.updatedAt.isNotBlank()); assertTrue(news.events.isNotEmpty())
            assertEquals(date, repository.get("/v1/cn/rankings", mapOf("trade_date" to date, "classification_source" to "ths")).str("trade_date"))
            assertTrue(repository.get("/v1/cn/lhb", mapOf("trade_date" to date)).rows().isNotEmpty())
            assertTrue(repository.get("/v1/us/ai").rows().isNotEmpty())
            assertEquals(501, assertFailsWith<ApiFailure> { repository.get("/v1/cn/boards/CN.industry.THS_881121/members", mapOf("observed_date" to date)) }.status)
            assertEquals(501, assertFailsWith<ApiFailure> { repository.get("/v1/cn/boards/CN.industry.THS_881121/flows") }.status)
        } finally { repository.close() }
    }
}
