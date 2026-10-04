package cn.gproject

import kotlin.test.*

class WorkflowTest {
    @Test fun marketDatesRemainIndependent() {
        assertEquals("2026-10-02",stockHistoryEnd(Quote("US.stock.NVDA","Nvidia","NVDA",date="2026-10-02"),"2026-09-30"))
        assertEquals("2026-09-30",stockHistoryEnd(Quote("CN.stock.688981","中芯国际","688981"),"2026-09-30"))
        assertEquals("",stockHistoryEnd(Quote("US.stock.NVDA","Nvidia","NVDA"),"2026-09-30"))
    }
    @Test fun crossEntryIdentityDoesNotDuplicateDemoStocks() {
        val quote=Quote("CN.stock.688981","中芯国际","688981")
        assertEquals("demo0",normalizedPoolId(quote,true))
        assertTrue(poolContains(listOf(WatchedStock("demo0",quote.name,quote.symbol)),quote))
        assertFalse(eligibleForPool(Quote("CN.stock.000001","错配","688981"),false))
        assertFalse(eligibleForPool(Quote("US.stock.NVDA","Nvidia","NVDA"),false))
    }
    @Test fun searchAcceptsWhitespaceAndPartialCodes() {
        assertTrue(matchesSearch("中芯国际","688981"," 芯 "))
        assertTrue(matchesSearch("中芯国际","688981","898"))
        assertTrue(matchesSearch("Nvidia","NVDA","nv"))
        assertFalse(matchesSearch("中芯国际","688981","银行"))
    }
}
