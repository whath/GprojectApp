package cn.gproject

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*
import java.util.concurrent.atomic.AtomicInteger

class UniverseTest {
    @Test fun stockCatalogRejectsCodeAndIdentifierMismatch() = runBlocking {
        for(symbol in listOf("000001","123")) {
            val repo=MarketRepository(HttpClient(MockEngine { respond("""{"total":1,"items":[{"id":"CN.stock.600000","symbol":"$symbol","name":"错配"}]}""",HttpStatusCode.OK) }))
            try { repo.base="https://example.test";assertFailsWith<IllegalArgumentException> { repo.catalog() };Unit } finally { repo.close() }
        }
    }
    private fun rows(start: Int,count: Int)= (start until start+count).joinToString(",") { val code=it.toString().padStart(6,'0');"""{"id":"CN.stock.$code","name":"标的$it","symbol":"$code"}""" }
    @Test fun catalogExhaustsMoreThanOnePage() = runBlocking {
        val offsets=mutableListOf<Int>()
        val repo=MarketRepository(HttpClient(MockEngine { req ->
            assertEquals("500",req.url.parameters["limit"])
            assertEquals("CN",req.url.parameters["market"])
            val offset=req.url.parameters["offset"]!!.toInt();offsets+=offset
            respond("""{"total":501,"items":[${rows(offset,if(offset==0) 500 else 1)}]}""",HttpStatusCode.OK)
        }))
        try { repo.base="https://example.test";assertEquals(501,repo.catalog().size);assertEquals(listOf(0,500),offsets) } finally { repo.close() }
    }
    @Test fun duplicatedOrTruncatedPagesCannotClaimACompleteScan() = runBlocking {
        for(broken in listOf("duplicate","empty","changed")) {
            var calls=0
            val repo=MarketRepository(HttpClient(MockEngine {
                calls++
                respond(if(calls==1) """{"total":2,"items":[${rows(0,1)}]}""" else when(broken) {
                    "duplicate" -> """{"total":2,"items":[${rows(0,1)}]}"""
                    "empty" -> """{"total":2,"items":[]}"""
                    else -> """{"total":3,"items":[${rows(1,1)}]}"""
                },HttpStatusCode.OK)
            }))
            try { repo.base="https://example.test";assertFailsWith<IllegalArgumentException> { repo.catalog() };Unit } finally { repo.close() }
        }
    }
    @Test fun scanVisitsBeyondThirtyWithBoundedConcurrencyAndReportsMissingBars() = runBlocking {
        val candles=demoStock(Quote("demo","样本","688981")).candles.takeLast(100)
        val history=buildJsonObject { putJsonArray("items") { candles.forEach { b -> add(buildJsonObject {
            put("trade_date",b.date);put("open",b.open);put("high",b.high);put("low",b.low);put("close",b.close);put("volume",b.volume);put("volume_unit",b.volumeUnit);put("source",b.source)
        }) } } }.toString()
        val active=AtomicInteger();val peak=AtomicInteger();val barCalls=AtomicInteger()
        val repo=MarketRepository(HttpClient(MockEngine { req ->
            when(req.url.encodedPath) {
                "/v1/cn/rankings" -> respond("""{"trade_date":"${candles.last().date}","items":[]}""",HttpStatusCode.OK)
                "/v1/instruments" -> respond("""{"total":34,"items":[${rows(0,34)}]}""",HttpStatusCode.OK)
                else -> { assertEquals("250",req.url.parameters["limit"]);val n=active.incrementAndGet();peak.updateAndGet { maxOf(it,n) };barCalls.incrementAndGet();delay(3);active.decrementAndGet()
                    respond(if(req.url.encodedPath.endsWith("000033")) """{"items":[]}""" else history,HttpStatusCode.OK)
                }
            }
        }))
        try {
            repo.base="https://example.test"
            var last: ScanProgress?=null
            val results=repo.scanUniverse(Rules(aboveMa=false,rsiMin=0,rsiMax=100,volume=0.0)) { p,_ -> last=p }
            assertEquals(34,barCalls.get());assertEquals(33,results.size)
            assertEquals(34,last?.processed);assertEquals(1,last?.excluded);assertTrue(peak.get() in 1..4)
            assertTrue(results.any { it.quote.id=="CN.stock.000032" })
        } finally { repo.close() }
    }
    @Test fun membershipMatchesExactSymbolsAndRequiresCurrentSnapshot() = runBlocking {
        val repo=MarketRepository(HttpClient(MockEngine { req ->
            when(req.url.encodedPath) {
                "/v1/instruments" -> respond("""{"total":2,"items":[{"id":"CN.industry.THS_1","name":"板块一","symbol":"THS_1"},{"id":"CN.industry.THS_2","name":"板块二","symbol":"THS_2"}]}""",HttpStatusCode.OK)
                else -> {
                    assertEquals("2026-09-30",req.url.parameters["observed_date"])
                    respond("""{"trade_date":"${if(req.url.encodedPath.contains("THS_1")) "2026-09-30" else "2026-09-29"}","items":[{"symbol":"688981","name":"中芯国际"}],"task_status":[{"status":"complete"}]}""",HttpStatusCode.OK)
                }
            }
        }))
        try {
            repo.base="https://example.test"
            val index=repo.membershipIndex("2026-09-30")
            assertEquals("CN.industry.THS_1",index.bySymbol["688981"]!!.single().id)
            assertNull(index.bySymbol["688980"]);assertFalse(index.complete)
        } finally { repo.close() }
    }

    @Test fun cancellationStopsBeforeTheNextBatch() = runBlocking {
        val barCalls=AtomicInteger()
        val repo=MarketRepository(HttpClient(MockEngine { req ->
            respond(when(req.url.encodedPath) {
                "/v1/cn/rankings" -> """{"trade_date":"2026-09-30"}"""
                "/v1/instruments" -> """{"total":40,"items":[${rows(0,40)}]}"""
                else -> { barCalls.incrementAndGet();"""{"items":[]}""" }
            },HttpStatusCode.OK)
        }))
        try {
            repo.base="https://example.test"
            val job=launch { repo.scanUniverse(Rules()) { progress,_ -> if(progress.processed==4) cancel() } }
            job.join()
            assertTrue(job.isCancelled);assertEquals(4,barCalls.get())
        } finally { repo.close() }
    }
}
