package kr.tntlabs.ohmymusic.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln1p
import kotlin.math.pow

/**
 * 키 분류 모델의 입력 특징. eval/prep.py · eval/keynet.py · macOS KeyDetector.swift 와 정확히 같아야 한다:
 * 22.05kHz 모노 → 8192점 Hann FFT, hop 2205(10fps) → 반음당 3칸 삼각 필터(MIDI 36~95)
 * → 2프레임 평균(5fps) → log1p. 최근 [maxFrames] 프레임만 남긴다.
 */
class KeyFeatures(private val maxFrames: Int = MAX_FRAMES) {
    companion object {
        const val SAMPLE_RATE = 22050
        const val FFT_SIZE = 8192
        const val HOP = 2205
        const val MIDI_LO = 36
        const val MIDI_HI = 96
        const val BINS_PER_SEMITONE = 3
        const val BINS = (MIDI_HI - MIDI_LO) * BINS_PER_SEMITONE
        /** 5fps 기준 2분. 길수록 안정적이고 곡 중간 전조에는 느리게 반응한다. */
        const val MAX_FRAMES = 600
        const val FPS = 5
    }

    private class Filter(val start: Int, val weights: FloatArray)

    private val fft = Fft(FFT_SIZE)
    // numpy.hanning 과 같은 대칭 창
    private val window = FloatArray(FFT_SIZE) { (0.5 - 0.5 * cos(2 * PI * it / (FFT_SIZE - 1))).toFloat() }
    private val filters = makeFilters()
    private val mag = FloatArray(FFT_SIZE / 2 + 1)

    private var pending = FloatArray(FFT_SIZE * 4)
    private var pendingLen = 0
    private var pendingFrame: FloatArray? = null
    private val frames = ArrayDeque<FloatArray>()

    val frameCount: Int get() = frames.size
    val seconds: Double get() = frames.size.toDouble() / FPS

    fun reset() {
        pendingLen = 0
        pendingFrame = null
        frames.clear()
    }

    /** 22.05kHz 모노 샘플을 이어 붙이고 새 프레임을 계산한다. */
    fun append(x: FloatArray, count: Int = x.size) {
        if (pendingLen + count > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, pendingLen + count))
        System.arraycopy(x, 0, pending, pendingLen, count)
        pendingLen += count
        var offset = 0
        while (offset + FFT_SIZE <= pendingLen) {
            fft.magnitudes(pending, offset, window, mag)
            val frame = FloatArray(BINS)
            for ((i, f) in filters.withIndex()) {
                var s = 0f
                for (j in f.weights.indices) s += mag[f.start + j] * f.weights[j]
                frame[i] = s
            }
            push(frame)
            offset += HOP
        }
        if (offset > 0) {
            System.arraycopy(pending, offset, pending, 0, pendingLen - offset)
            pendingLen -= offset
        }
    }

    /** 모델 입력: 시간순 프레임 목록(각 BINS 칸). */
    fun snapshot(): List<FloatArray> = frames.toList()

    private fun push(frame: FloatArray) {
        val first = pendingFrame ?: run { pendingFrame = frame; return }
        pendingFrame = null
        frames.addLast(FloatArray(BINS) { ln1p((first[it] + frame[it]) / 2) })
        while (frames.size > maxFrames) frames.removeFirst()
    }

    /** prep.py 의 filterbank() 와 같은 삼각 필터. 필요한 범위(MIDI 36~95)만 만든다. */
    private fun makeFilters(): List<Filter> {
        val n = FFT_SIZE
        val bps = BINS_PER_SEMITONE
        val freqs = DoubleArray(n / 2 + 1) { it * SAMPLE_RATE.toDouble() / n }
        return (0 until BINS).map { i ->
            val c = 440 * 2.0.pow((MIDI_LO - 69 + (i - bps / 2).toDouble() / bps) / 12)
            val lo = c * 2.0.pow(-1.0 / (12 * bps))
            val hi = c * 2.0.pow(1.0 / (12 * bps))
            val w = DoubleArray(freqs.size) { k ->
                val f = freqs[k]
                maxOf(0.0, 1 - abs(f - c) / (if (f < c) c - lo else hi - c))
            }
            if (w.sum() == 0.0) w[freqs.indices.minBy { abs(freqs[it] - c) }] = 1.0
            val sum = w.sum()
            val first = w.indexOfFirst { it > 0 }
            val last = w.indexOfLast { it > 0 }
            Filter(first, FloatArray(last - first + 1) { (w[first + it] / sum).toFloat() })
        }
    }
}
