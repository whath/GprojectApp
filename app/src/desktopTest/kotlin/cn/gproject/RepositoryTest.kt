package cn.gproject

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.runBlocking

class RepositoryTest {
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
