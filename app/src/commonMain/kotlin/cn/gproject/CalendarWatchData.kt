package cn.gproject

import kotlinx.datetime.*
import kotlinx.serialization.json.*

data class ImportantEvent(val id: String,val title: String,val country: String,val category: String,
    val start: String,val end: String,val time: String?,val zone: String,val precision: String,
    val issuer: String,val url: String,val official: Boolean,val stale: Boolean)
data class ImportantAgenda(val items: List<ImportantEvent> = emptyList(),val note: String = "",val collectedAt: String = "")
data class WatchClose(val asset: DailyAsset,val status: String,val currency: String = "",val changeBasis: String = "")

internal fun parseImportantAgenda(data: JsonObject): ImportantAgenda {
    val known=mapOf("official/nbs/release-calendar/v1" to "www.stats.gov.cn","official/bea/release-calendar/v1" to "www.bea.gov",
        "official/bls/release-calendar/v1" to "www.bls.gov","official/federalreserve/fomc-calendar" to "www.federalreserve.gov",
        "wallstreetcn/macro-calendar/v1" to "wallstreetcn.com")
    val meetingHosts=setOf("www.mofcom.gov.cn","www.gov.cn","www.news.cn","www.npc.gov.cn","www.cppcc.gov.cn")
    val rows=data.rows().map { row ->
        val source=row.str("source");val url=row.str("source_page");val host=Regex("^https://([^/:?#]+)(?:/|$)").find(url)?.groupValues?.get(1)
        require(host!=null && (known[source]==host || source=="official/cn/meeting-announcements/v1" && host in meetingHosts)) { "日程来源未通过校验" }
        val country=row.str("country_code");val category=row.str("category");val precision=row.str("time_precision")
        require(country in setOf("CN","US") && category in setOf("meeting","release") && row.intValue("importance")>=3)
        require(row.str("schedule_status")=="announced" && precision in setOf("date","minute"))
        val start=checkedDate(row.str("start_date"));val end=checkedDate(row.str("end_date"));require(start<=end)
        val time=row.str("occurs_at").ifBlank { null }
        if(precision=="date") require(time==null) else { require(time!=null);Instant.parse(time) }
        val official=row.str("authority")=="official";require(official==source.startsWith("official/"))
        ImportantEvent(row.str("id"),row.str("title"),country,category,start,end,time,row.str("timezone"),precision,
            row.str("issuer"),url,official,row.flag("fetch_stale"))
    }
    require(rows.map { it.id }.distinct().size==rows.size)
    val sources=(data["sources"] as? JsonArray).orEmpty().map { it.jsonObject }
    val failed=sources.filter { it.str("status") in setOf("unavailable","retained") }.map { it.str("source") }
    val names=mapOf("bls" to "BLS 就业/通胀日程","cn-meetings" to "中国会议公告发现","bea" to "BEA 日程","nbs" to "国家统计局日程","fed" to "美联储日程")
    val note=if(failed.isEmpty()) "" else "${failed.joinToString("、") { names[it] ?: it }}更新未完成；仅展示已取得的日程，补充来源会单独标明。"
    return ImportantAgenda(rows,note,sources.map { it.str("collected_at") }.filter { it.isNotBlank() }.minOrNull().orEmpty())
}

internal suspend fun MarketRepository.importantAgenda(): ImportantAgenda {
    val today=Clock.System.now().toLocalDateTime(TimeZone.of("Asia/Shanghai")).date
    return parseImportantAgenda(get("/v1/important-calendar",mapOf("start" to today.toString(),"end" to today.plus(DatePeriod(days=90)).toString(),"limit" to "1000")))
}

internal fun parseWatchCloses(data: JsonObject,ids: List<String>): Map<String,WatchClose> {
    val rows=data.rows();require(rows.size==ids.size && rows.map { it.str("id") }.toSet()==ids.toSet())
    return rows.associate { row ->
        val id=checkedAssetId(row.str("id"));val status=row.str("status")
        require(status in setOf("current","stale","unavailable","not_found") && row.str("price_type")=="stored_daily_close" && row.str("adjustment")=="none")
        val close=row.num("close");val change=row.num("change_1d_pct");val requested=checkedDate(row.str("requested_date"))
        val day=row.str("trade_date");val source=row.str("source");val currency=row.str("currency")
        if(status in setOf("current","stale")) {
            checkedDate(day);require(close!=null && close>0 && source.isNotBlank() && day<=requested)
            require(currency==if(id.startsWith("CN.")) "CNY" else "USD")
            require(if(status=="current") day==requested else day<requested && change==null)
        } else require(close==null && change==null)
        id to WatchClose(DailyAsset(id,row.str("name"),row.str("symbol"),close,change,day,source,periodSessions=1),status,currency,row.str("change_basis"))
    }
}

internal suspend fun MarketRepository.watchCloses(items: List<DailyAsset>,overview: DailyOverview): Map<String,WatchClose> {
    val ids=items.map { checkedAssetId(it.id) };require(ids.size in 1..100 && ids.distinct().size==ids.size)
    return parseWatchCloses(get("/v1/watch-quotes",buildMap {
        put("ids",ids.joinToString(","));if(overview.cn.isNotBlank()) put("cn_date",overview.cn);if(overview.us.isNotBlank()) put("us_date",overview.us)
    }),ids)
}
