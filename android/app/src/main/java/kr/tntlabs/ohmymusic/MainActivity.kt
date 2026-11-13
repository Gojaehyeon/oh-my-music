package kr.tntlabs.ohmymusic

import android.Manifest
import android.app.Activity
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Accent = Color(0xFF7C5CFF)
private val Muted = Color(0xFF8A8A92)

class MainActivity : ComponentActivity() {
    private var pendingSource = Source.PLAYBACK

    private val projectionConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            CaptureService.start(this, Source.PLAYBACK, result.resultCode, result.data)
        } else {
            AnalysisState.update { it.copy(error = "화면 공유를 허용해야 이 기기에서 재생 중인 소리를 들을 수 있습니다.") }
        }
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] != true) {
            AnalysisState.update { it.copy(error = "소리를 분석하려면 마이크(오디오 녹음) 권한이 필요합니다.") }
            return@registerForActivityResult
        }
        launchCapture(pendingSource)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent)) {
                val state by AnalysisState.state.collectAsState()
                val source = remember { mutableStateOf(state.source) }
                Screen(
                    state = state,
                    source = if (state.running) state.source else source.value,
                    onSource = { source.value = it },
                    onStart = { start(source.value) },
                    onStop = { CaptureService.stop(this) },
                )
            }
        }
    }

    private fun start(source: Source) {
        AnalysisState.update { it.copy(error = null) }
        pendingSource = source
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.launch(needed.toTypedArray())
    }

    private fun launchCapture(source: Source) {
        if (source == Source.MICROPHONE) {
            CaptureService.start(this, Source.MICROPHONE)
        } else {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            // 앱 하나만 공유하면 그 앱 소리만 들린다. 어떤 음악 앱이든 듣도록 전체 화면 공유로 바로 묻는다(Android 14+)
            val intent = if (Build.VERSION.SDK_INT >= 34) {
                mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else mpm.createScreenCaptureIntent()
            projectionConsent.launch(intent)
        }
    }
}

@Composable
private fun Screen(state: UiState, source: Source, onSource: (Source) -> Unit, onStart: () -> Unit, onStop: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF16161A)).safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("OH MY MUSIC", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        Spacer(Modifier.weight(1f))

        val key = state.key
        Text(
            text = if (state.running && state.listening) key?.short ?: "…" else "—",
            color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Bold,
        )
        Text(
            text = state.bpm?.let { "${Math.round(it)} BPM" } ?: " ",
            color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = key?.takeIf { state.listening }?.let { "${it.long} · 카멜롯 ${it.camelot} · 확신도 ${(it.confidence * 100).toInt()}%" } ?: " ",
            color = Muted, fontSize = 15.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = when {
                !state.running -> "시작을 누르면 분석합니다"
                !state.listening -> "음악을 기다리는 중"
                else -> "${state.heardSeconds.toInt()}초째 듣는 중"
            },
            color = Muted, fontSize = 14.sp,
        )

        Spacer(Modifier.weight(1f))
        state.error?.let {
            Text(it, color = Color(0xFFFF8A80), fontSize = 14.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SourceChip("이 기기 소리", source == Source.PLAYBACK, !state.running) { onSource(Source.PLAYBACK) }
            SourceChip("주변 소리(마이크)", source == Source.MICROPHONE, !state.running) { onSource(Source.MICROPHONE) }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (source == Source.PLAYBACK) "다른 앱에서 재생 중인 음악을 직접 듣습니다. 일부 앱은 이 기능을 막아 두었으니, 그럴 때는 마이크를 쓰세요."
            else "주변 스피커에서 나오는 음악을 마이크로 듣습니다. 조용한 곳에서 더 정확합니다.",
            color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = if (state.running) onStop else onStart,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (state.running) Color(0xFF2C2C31) else Accent),
        ) {
            Text(if (state.running) "정지" else "시작", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
}

@Composable
private fun SourceChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, enabled = enabled, label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent, selectedLabelColor = Color.White),
    )
}
