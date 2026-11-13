package kr.tntlabs.ohmymusic

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kr.tntlabs.ohmymusic.core.Key

/** 소리를 어디서 듣는지. */
enum class Source { PLAYBACK, MICROPHONE }

/** 캡처 서비스가 갱신하고 화면이 구독하는 현재 상태. */
data class UiState(
    val running: Boolean = false,
    val source: Source = Source.PLAYBACK,
    val key: Key? = null,
    val bpm: Float? = null,
    val listening: Boolean = false,
    val heardSeconds: Double = 0.0,
    val error: String? = null,
)

object AnalysisState {
    private val mutable = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutable

    fun update(transform: (UiState) -> UiState) {
        mutable.value = transform(mutable.value)
    }
}
