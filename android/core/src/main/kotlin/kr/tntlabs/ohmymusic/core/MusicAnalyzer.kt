package kr.tntlabs.ohmymusic.core

/**
 * 22.05kHz 모노 스트림을 받아 키와 BPM 을 낸다. 앱과 PC 채점 도구가 같은 경로를 쓴다.
 * 스레드 안전하지 않다. 한 스레드에서만 부른다.
 */
class MusicAnalyzer(private val model: KeyModel) {
    data class Result(val key: Key?, val bpm: Float?, val seconds: Double)

    private val features = KeyFeatures()
    private val tempo = Tempo()
    // 템포는 최근 10초 창으로 본다
    private val recent = FloatArray(KeyFeatures.SAMPLE_RATE * 10)
    private var recentLen = 0
    private var heardSamples = 0L

    fun reset() {
        features.reset()
        tempo.reset()
        recentLen = 0
        heardSamples = 0
    }

    fun append(x: FloatArray, count: Int = x.size) {
        features.append(x, count)
        heardSamples += count
        if (count >= recent.size) {
            System.arraycopy(x, count - recent.size, recent, 0, recent.size)
            recentLen = recent.size
        } else {
            val keep = minOf(recentLen, recent.size - count)
            System.arraycopy(recent, recentLen - keep, recent, 0, keep)
            System.arraycopy(x, 0, recent, keep, count)
            recentLen = keep + count
        }
    }

    val seconds: Double get() = heardSamples.toDouble() / KeyFeatures.SAMPLE_RATE

    /** 키만 추정한다. 5초(25프레임)가 쌓여야 나온다. */
    fun estimateKey(): Key? {
        val frames = features.snapshot()
        return if (frames.size >= 25) Key.fromProbabilities(model.probabilities(frames)) else null
    }

    /** 지금까지 들은 것으로 추정한다. 키는 5초(25프레임), BPM 은 5초가 쌓여야 나온다. */
    fun estimate(): Result {
        val key = estimateKey()
        val bpm = if (recentLen >= KeyFeatures.SAMPLE_RATE * 5) {
            tempo.update(recent.copyOf(recentLen), KeyFeatures.SAMPLE_RATE.toDouble())
        } else null
        return Result(key, bpm, seconds)
    }
}
