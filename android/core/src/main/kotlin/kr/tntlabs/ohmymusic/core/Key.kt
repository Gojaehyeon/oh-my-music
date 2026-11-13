package kr.tntlabs.ohmymusic.core

/** 조성. tonic 0 = C. 표기 규칙은 macOS 앱(Analyzer.Key)과 같다. */
data class Key(val tonic: Int, val minor: Boolean, val confidence: Float) {
    val short: String get() = NAMES[tonic] + if (minor) "m" else ""
    val long: String get() = NAMES[tonic] + if (minor) " minor" else " major"
    val camelot: String
        get() {
            val majorPc = if (minor) (tonic + 3) % 12 else tonic
            return "${(majorPc * 7 + 7) % 12 + 1}${if (minor) "A" else "B"}"
        }

    companion object {
        val NAMES = listOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")

        /** 모델 출력(0~11 장조 C..B, 12~23 단조)에서 가장 확률이 높은 조성. */
        fun fromProbabilities(p: FloatArray): Key {
            val best = p.indices.maxBy { p[it] }
            return Key(best % 12, best >= 12, p[best])
        }
    }
}

/** 키 분류 모델. 입력은 [KeyFeatures.snapshot] 프레임, 출력은 24개 조성 확률. */
fun interface KeyModel {
    fun probabilities(frames: List<FloatArray>): FloatArray
}
