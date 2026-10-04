package cn.gproject

import kotlin.test.*

class ChartTest {
    @Test fun priceRangeIncludesAveragesBeyondCandleExtremes() {
        val (low,high)=chartPriceBounds(listOf(20.0,21.0,25.0,18.0))
        assertTrue(low<18 && high>25)
        assertTrue(low.isFinite() && high.isFinite())
    }
    @Test fun flatPriceHasNonzeroScale() {
        for (price in listOf(.01,10.0,100000.0)) {
            val (low,high)=chartPriceBounds(List(30) { price })
            assertTrue(low<price && high>price)
            assertTrue(high-low>0)
        }
    }
}
