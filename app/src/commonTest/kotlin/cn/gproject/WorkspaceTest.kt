package cn.gproject

import kotlin.test.*

class WorkspaceTest {
    @Test fun watchlistPersistsIdentityPinsAndProviderWithoutDemoEntries() {
        val config=WorkspaceSettings(listOf(WatchedStock("CN.stock.688981","中芯国际","688981",true,"2026-10-03T00:00:00Z"),WatchedStock("demo0","演示","688981")),AiConfiguration("国内模型","https://example.test/v1","model-id"))
        val encoded=encodeWorkspace(config)
        assertFalse(encoded.contains("demo0"))
        assertFalse(encoded.contains("api_key"))
        val decoded=decodeWorkspace(encoded)
        assertEquals(config.stocks.first(),decoded.stocks.single())
        assertEquals(config.ai,decoded.ai)
    }
    @Test fun pinnedStocksLeadAndRemovingDoesNotAffectOtherEntries() {
        val older=WatchedStock("CN.stock.000001","旧","000001",true,"2026-10-01")
        val newer=WatchedStock("CN.stock.000002","新","000002",false,"2026-10-03")
        assertEquals(older,orderedWatchlist(listOf(newer,older)).first())
        assertEquals(newer,orderedWatchlist(listOf(newer,older.copy(pinned=false))).first())
    }
    @Test fun aiEndpointRejectsEmbeddedCredentialsQueriesAndInsecureTransport() {
        for(url in listOf("http://example.test/v1","https://user:pass@example.test","https://example.test?key=secret","https://example.test/#key")) {
            assertNotNull(validateAiConfiguration(AiConfiguration(baseUrl=url,model="model")))
        }
        assertNull(validateAiConfiguration(AiConfiguration(baseUrl="https://example.test:443/compatible-mode/v1",model="deployment-id")))
        assertNotNull(validateAiConfiguration(AiConfiguration(baseUrl="https://example.test",model=" ")))
    }
}
