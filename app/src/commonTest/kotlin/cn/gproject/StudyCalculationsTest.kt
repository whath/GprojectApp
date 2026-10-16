package cn.gproject

import kotlinx.datetime.*
import kotlin.math.*
import kotlin.test.*

class StudyCalculationsTest {
    private fun bars(closes: List<Double>)=closes.mapIndexed { i,p -> Candle(LocalDate(2025,1,1).plus(DatePeriod(days=i)).toString(),p,p,p,p,100.0,null,"fixture","share") }

    @Test fun kdjHasNineBarWarmupFiftySeedAndUnclippedJ() {
        val flat=calculateKdj(bars(List(12) { 10.0 }))
        assertTrue(flat.take(8).all { it==null });assertEquals(KdjPoint(50.0,50.0,50.0),flat[8])
        val rising=calculateKdj(bars((1..12).map { it.toDouble() }))
        assertEquals(200.0/3,rising[8]!!.k,1e-10)
        assertEquals(500.0/9,rising[8]!!.d,1e-10)
        assertTrue(rising[9]!!.j>100,"J must not be silently clamped")
        val flatBars=bars(List(40) { 10.0 });val studies=dailyStudies(flatBars)
        assertTrue(studyMeaning("macd",flatBars,studies,39).contains("平衡"))
        val fading=studies.copy(macd=studies.macd.mapIndexed { i,p -> if(i==38) MacdPoint(2.0,1.0,2.0) else if(i==39) MacdPoint(1.5,1.0,1.0) else p })
        assertTrue(studyMeaning("macd",flatBars,fading,39).contains("上行动量减弱"))

    }

    @Test fun rsiUsesWilderSeedFlatFiftyAndFourteenDeltas() {
        assertTrue(calculateRsi(bars(List(14) { 10.0 })).all { it==null })
        assertEquals(50.0,calculateRsi(bars(List(15) { 10.0 })).last())
        assertEquals(100.0,calculateRsi(bars((1..20).map { it.toDouble() })).last())
        assertEquals(0.0,calculateRsi(bars((20 downTo 1).map { it.toDouble() })).last())
        val points=bars(List(15) { 40.0+it%2 }+41.0)
        val result=calculateRsi(points)
        assertEquals(50.0,result[14]!!,1e-10)
        assertEquals(100.0*(7.5/14),result[15]!!,1e-10)
    }

    @Test fun bollUsesTwentyBarPopulationDeviation() {
        val points=calculateBoll(bars((1..21).map { it.toDouble() }))
        assertTrue(points.take(19).all { it==null })
        assertEquals(10.5,points[19]!!.middle,1e-10)
        assertEquals(10.5+2*sqrt(33.25),points[19]!!.upper,1e-10)
        assertEquals(10.5-2*sqrt(33.25),points[19]!!.lower,1e-10)
    }

    @Test fun studiesNeverReadFutureBarsOrRestartOnZoom() {
        val full=bars(List(160) { 20+sin(it*.3)*3+it*.03 })
        val prefix=full.take(90);val data=dailyStudies(full);val expected=dailyStudies(prefix)
        assertEquals(expected.kdj,data.kdj.take(90));assertEquals(expected.macd,data.macd.take(90))
        assertEquals(expected.rsi,data.rsi.take(90));assertEquals(expected.boll,data.boll.take(90))
        assertEquals(studyMeaning("macd",prefix,expected,89),studyMeaning("macd",full,data,89))
        val narrowed=chartWindow(full.size,70,90,zoom=3f)
        assertNotNull(data.macd[narrowed.start],"Zooming to 30 visible bars must retain warmed MACD")
    }

    @Test fun volumeRatioExcludesCurrentAndRejectsMissingOrChangedUnits() {
        val points=bars(List(21) { 20.0 }).toMutableList();points[20]=points[20].copy(volume=200.0)
        assertEquals(2.0,priorVolumeRatio(points,20));assertNull(priorVolumeRatio(points,19))
        val missing=points.toMutableList();missing[3]=missing[3].copy(volume=null);assertNull(priorVolumeRatio(missing,20))
        val changed=points.toMutableList();changed[3]=changed[3].copy(volumeUnit="lot");assertNull(priorVolumeRatio(changed,20))
    }
}
