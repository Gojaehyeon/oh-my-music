package kr.tntlabs.ohmymusic.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 실수 입력의 크기 스펙트럼(0..N/2). numpy.abs(numpy.fft.rfft(x)) 와 같은 크기(배율 보정 없음). */
class Fft(val size: Int) {
    private val bits = Integer.numberOfTrailingZeros(size)
    private val cosT = DoubleArray(size / 2) { cos(2 * PI * it / size) }
    private val sinT = DoubleArray(size / 2) { -sin(2 * PI * it / size) }
    private val rev = IntArray(size) { Integer.reverse(it) ushr (32 - bits) }
    private val re = DoubleArray(size)
    private val im = DoubleArray(size)

    init {
        require(size > 1 && size and (size - 1) == 0) { "FFT 크기는 2의 거듭제곱이어야 합니다" }
    }

    /** x[offset until offset+size] 에 window 를 곱해 변환한다. 결과 길이 size/2+1. */
    fun magnitudes(x: FloatArray, offset: Int, window: FloatArray?, out: FloatArray = FloatArray(size / 2 + 1)): FloatArray {
        for (i in 0 until size) {
            val v = x[offset + i].toDouble() * (window?.get(i)?.toDouble() ?: 1.0)
            re[rev[i]] = v
            im[rev[i]] = 0.0
        }
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var start = 0
            while (start < size) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosT[k]
                    val wi = sinT[k]
                    val tr = re[j + half] * wr - im[j + half] * wi
                    val ti = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - tr
                    im[j + half] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                start += len
            }
            len *= 2
        }
        for (k in 0..size / 2) out[k] = sqrt(re[k] * re[k] + im[k] * im[k]).toFloat()
        return out
    }
}
