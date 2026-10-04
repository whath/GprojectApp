package cn.gproject

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlin.test.*

class StockFundsRepositoryTest {
    private val quote = Quote("CN.stock.688981", "中芯国际", "688981")
    private val bars = """{"adjustment":"none","instrument":{"currency":"CNY"},"items":[{"trade_date":"2026-09-30","open":10,"high":12,"low":9,"close":11,"volume":15,"volume_unit":"share","source":"test"}]}"""
    private val funds = """{"instrument_id":"CN.stock.688981","currency":"CNY","unit":"yuan","scope":"main","source":"fund-test","updated_at":"2026-10-01T00:00:00Z","items":[{"trade_date":"2026-09-30","net":-100}]}"""

    @Test fun independentHistoryAndFundsRequestsRunConcurrentlyWithSameEndDate() = runBlocking {
        val barsStarted = CompletableDeferred<Unit>()
        val fundsStarted = CompletableDeferred<Unit>()
        val repository = MarketRepository(HttpClient(MockEngine { request ->
            assertEquals("Bearer test-only-token", request.headers[HttpHeaders.Authorization])
            assertEquals("2026-09-30", request.url.parameters["end"])
            assertEquals("2000", request.url.parameters["limit"])
            when (request.url.encodedPath) {
                "/v1/bars/CN.stock.688981" -> { barsStarted.complete(Unit); fundsStarted.await(); respond(bars, HttpStatusCode.OK) }
                "/v1/cn/stocks/CN.stock.688981/flows" -> { fundsStarted.complete(Unit); barsStarted.await(); respond(funds, HttpStatusCode.OK) }
                else -> error("Unexpected path")
            }
        }))
        try {
            repository.base = "https://example.test"; repository.token = "test-only-token"
            val detail = withTimeout(5000) { repository.stockDetail(quote, "2026-09-30", null) }
            assertEquals(11.0, detail.candles.single().close)
            assertEquals(-100.0, detail.funds!!.rows.single().net)
            assertNull(detail.funds!!.error)
        } finally { repository.close() }
    }

    @Test fun missingOrUnauthorizedFundsNeverEraseSuccessfulCandlesOrInventData() = runBlocking {
        for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.NotImplemented, HttpStatusCode.Unauthorized)) {
            val repository = MarketRepository(HttpClient(MockEngine { request ->
                if (request.url.encodedPath.endsWith("/flows")) respond("private diagnostic", status)
                else respond(bars, HttpStatusCode.OK)
            }))
            try {
                repository.base = "https://example.test"
                val detail = repository.stockDetail(quote, "2026-09-30", null)
                assertEquals(1, detail.candles.size)
                assertFalse(detail.loading)
                assertNull(detail.error)
                val result = assertNotNull(detail.funds)
                assertTrue(result.rows.isEmpty())
                assertFalse(result.error!!.contains("private diagnostic"))
                if (status == HttpStatusCode.Unauthorized) assertEquals("令牌无效或已过期，请重新连接", result.error)
                else assertEquals("个股主力资金接口待接入", result.error)
            } finally { repository.close() }
        }
    }

    @Test fun unsupportedMarketAndInvalidDatesDoNotMakeFundsRequests() = runBlocking {
        val repository = MarketRepository(HttpClient(MockEngine { error("No request expected") }))
        try {
            repository.base = "https://example.test"
            assertEquals("当前仅支持 A 股个股主力资金", repository.stockFunds("US.stock.NVDA", "2026-09-30").error)
            assertEquals("当前仅支持 A 股个股主力资金", repository.stockFunds("CN.industry.THS_1", "2026-09-30").error)
            assertEquals("日线截至日期无效，主力资金未请求", repository.stockFunds(quote.id, "").error)
        } finally { repository.close() }
    }

    @Test fun cancellingHistoryAlsoCancelsPendingOptionalFunds() = runBlocking {
        val fundsStarted = CompletableDeferred<Unit>()
        val fundsCancelled = CompletableDeferred<Unit>()
        val repository = MarketRepository(HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("/flows")) {
                fundsStarted.complete(Unit)
                try { awaitCancellation() } finally { fundsCancelled.complete(Unit) }
            } else respond(bars, HttpStatusCode.OK)
        }))
        try {
            repository.base = "https://example.test"
            val job = launch { repository.stockDetail(quote, "2026-09-30", null) }
            withTimeout(5000) { fundsStarted.await() }
            job.cancelAndJoin()
            withTimeout(5000) { fundsCancelled.await() }
            assertTrue(job.isCancelled)
        } finally { repository.close() }
    }
}
