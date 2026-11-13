package kr.tntlabs.ohmymusic.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.pow

/**
 * BPM 추정. macOS Analyzer.swift 의 estimateTempo 를 그대로 옮겼다:
 * 스펙트럴 플럭스 온셋 엔벨로프의 자기상관 + 120 BPM 중심 로그-가우시안 가중, 최근 7회 중앙값.
 */
class Tempo {
    private val fft = Fft(1024)
    private val hop = 256
    // vDSP hanningDenormalized(주기형) 창과 같은 모양
    private val window = FloatArray(fft.size) { (0.5 - 0.5 * cos(2 * PI * it / fft.size)).toFloat() }
    private val history = ArrayDeque<Float>()

    fun reset() = history.clear()

    /** 최근 버퍼에서 BPM 을 추정해 기록에 넣고, 기록의 중앙값을 돌려준다. */
    fun update(x: FloatArray, sampleRate: Double): Float? {
        val bpm = estimate(x, sampleRate) ?: return null
        history.addLast(bpm)
        if (history.size > 7) history.removeFirst()
        val sorted = history.sorted()
        return sorted[sorted.size / 2]
    }

    fun estimate(x: FloatArray, sr: Double): Float? {
        val n = fft.size
        if (x.size <= n * 4) return null
        val fps = sr / hop
        val frames = (x.size - n) / hop
        val bins = n / 2

        // 스펙트럴 플럭스 (로그 압축, 양의 변화량 합). vDSP 는 크기를 2배로 내므로 200 을 곱해 맞춘다
        val onset = FloatArray(frames)
        var prev = FloatArray(bins)
        val mag = FloatArray(n / 2 + 1)
        for (t in 0 until frames) {
            fft.magnitudes(x, t * hop, window, mag)
            val cur = FloatArray(bins) { ln1p(200f * mag[it]) }
            if (t > 0) {
                var flux = 0f
                for (k in 0 until bins) {
                    val d = cur[k] - prev[k]
                    if (d > 0) flux += d
                }
                onset[t] = flux
            }
            prev = cur
        }

        // 국소 평균 제거 후 반파 정류
        val win = maxOf(1, (fps * 0.4).toInt())
        val detrended = FloatArray(frames) { t ->
            val lo = maxOf(0, t - win)
            val hi = minOf(frames, t + win + 1)
            var s = 0f
            for (i in lo until hi) s += onset[i]
            maxOf(0f, onset[t] - s / (hi - lo))
        }

        val minLag = (fps * 60 / 210).toInt()
        val maxLag = minOf((fps * 60 / 55).toInt(), frames / 2)
        if (maxLag <= minLag + 2) return null

        fun ac(lag: Int): Float {
            if (lag >= frames) return 0f
            var s = 0f
            for (i in 0 until frames - lag) s += detrended[i] * detrended[i + lag]
            return s / (frames - lag)
        }

        val acs = FloatArray(maxLag * 2 + 1) { ac(it) }
        val scores = FloatArray(maxLag + 1)
        for (lag in minLag..maxLag) {
            val bpm = 60 * fps / lag
            val w = exp(-0.5 * (ln(bpm / 120) / ln(2.0) / 0.9).pow(2)).toFloat()
            scores[lag] = w * (acs[lag] + 0.5f * acs[minOf(lag * 2, acs.size - 1)])
        }
        val best = (minLag..maxLag).maxBy { scores[it] }
        if (scores[best] <= 0) return null

        // 포물선 보간으로 래그를 소수점까지
        var lag = best.toDouble()
        if (best in (minLag + 1) until maxLag) {
            val a = scores[best - 1]
            val b = scores[best]
            val c = scores[best + 1]
            val denom = a - 2 * b + c
            if (denom != 0f) lag += (0.5f * (a - c) / denom).toDouble()
        }
        var bpm = (60 * fps / lag).toFloat()
        while (bpm < 70) bpm *= 2
        while (bpm > 190) bpm /= 2
        return bpm
    }
}
