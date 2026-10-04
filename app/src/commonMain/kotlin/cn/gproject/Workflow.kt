package cn.gproject

import kotlinx.datetime.LocalDate

enum class ScanPhase { IDLE, RUNNING, COMPLETE, STOPPED, FAILED }
internal fun matchesSearch(name: String,symbol: String,query: String): Boolean = query.trim().let { it.isEmpty() || name.contains(it,true) || symbol.contains(it,true) }
internal fun poolContains(stocks: List<WatchedStock>,quote: Quote)=stocks.any { it.symbol==quote.symbol }
internal fun eligibleForPool(quote: Quote,demo: Boolean)=quote.symbol.matches(Regex("[0-9]{6}")) && (demo || quote.id=="CN.stock.${quote.symbol}")
internal fun normalizedPoolId(quote: Quote,demo: Boolean): String {
    if(!demo) return quote.id
    val index=listOf("688981","300308","300750","600036","300059","002594").indexOf(quote.symbol)
    return if(index>=0) "demo$index" else "demo.stock.${quote.symbol}"
}
internal fun stockHistoryEnd(quote: Quote,cnDate: String): String {
    val candidate=if(quote.id.startsWith("US.")) quote.date else cnDate
    return candidate.takeIf { runCatching { LocalDate.parse(it) }.isSuccess }.orEmpty()
}
