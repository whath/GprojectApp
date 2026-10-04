package cn.gproject

import androidx.compose.ui.test.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WorkflowIntegrationTest {
    @Test fun failedSectionDoesNotBlockOthersAndRefreshRetainsSnapshot() = runDesktopComposeUiTest {
        val failBoards=AtomicBoolean(false)
        val repository=MarketRepository(HttpClient(MockEngine { request ->
            when(request.url.encodedPath) {
                "/v1/cn/rankings" -> if(failBoards.get()) respond("failed",HttpStatusCode.ServiceUnavailable) else respond("""{"trade_date":"2026-09-30","items":[{"id":"CN.industry.THS_1","name":"半导体","price_change_pct":1.2}]}""",HttpStatusCode.OK)
                "/v1/cn/lhb" -> respond("failed",HttpStatusCode.ServiceUnavailable)
                "/v1/us/ai" -> respond("""{"items":[{"symbol":"NVDA","group":"算力","close":100,"trade_date":"2026-10-02","source":"test"}]}""",HttpStatusCode.OK)
                else -> respond("unavailable",HttpStatusCode.NotFound)
            }
        }))
        val model=MarketViewModel(repository,MonitorSettings(),WorkspaceSettings())
        try {
            runOnIdle { model.connect("https://example.test","test-only-token") }
            waitUntil(timeoutMillis=5000) { !model.ui.value.loading && model.ui.value.us.isNotEmpty() }
            runOnIdle {
                assertEquals("半导体",model.ui.value.boards.single().name)
                assertTrue(model.ui.value.sectionErrors.containsKey("龙虎榜"))
                failBoards.set(true);model.refresh()
            }
            waitUntil(timeoutMillis=5000) { !model.ui.value.loading && model.ui.value.sectionErrors.containsKey("板块") }
            runOnIdle {
                assertEquals("2026-09-30",model.ui.value.boards.single().date)
                assertEquals("2026-10-02",model.ui.value.us.single().date)
                model.disconnect()
                assertFalse(model.ui.value.hasConnection)
                assertTrue(model.ui.value.boards.isEmpty())
                assertNull(model.ui.value.stock)
            }
        } finally { model.close() }
    }
}

