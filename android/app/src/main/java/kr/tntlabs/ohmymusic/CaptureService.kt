package kr.tntlabs.ohmymusic

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import kr.tntlabs.ohmymusic.core.KeyFeatures
import kr.tntlabs.ohmymusic.core.MusicAnalyzer
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * 소리를 받아 2초마다 키·BPM 을 추정하고, 상단 알림과 [AnalysisState] 를 갱신하는 포그라운드 서비스.
 * - PLAYBACK: 다른 앱의 재생음(MediaProjection + AudioPlaybackCapture, 사용자 동의 필요)
 * - MICROPHONE: 주변 소리
 */
class CaptureService : Service() {
    companion object {
        private const val TAG = "OhMyMusic"
        private const val CHANNEL = "analysis"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "kr.tntlabs.ohmymusic.STOP"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_PROJECTION = "projection"
        private const val TICK_SECONDS = 2
        private const val SILENCE_RMS = 1e-4f
        private const val SILENCE_RESET_SECONDS = 4

        fun start(context: Context, source: Source, resultCode: Int = Activity.RESULT_CANCELED, projection: Intent? = null) {
            val intent = Intent(context, CaptureService::class.java)
                .putExtra(EXTRA_SOURCE, source.name)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_PROJECTION, projection)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CaptureService::class.java).setAction(ACTION_STOP))
        }
    }

    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        val source = intent?.getStringExtra(EXTRA_SOURCE)?.let { Source.valueOf(it) } ?: Source.PLAYBACK

        createChannel()
        // Android 14+: MediaProjection 을 얻기 전에 해당 유형으로 포그라운드에 들어가야 한다
        val type = if (source == Source.PLAYBACK) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("음악을 기다리는 중", null), type)

        try {
            record = if (source == Source.PLAYBACK) {
                val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_PROJECTION, Intent::class.java) }
                val code = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
                requireNotNull(data) { "화면 공유 동의가 없습니다" }
                val mpm = getSystemService(MediaProjectionManager::class.java)
                val mp = requireNotNull(mpm.getMediaProjection(code, data)) { "MediaProjection 을 얻지 못했습니다" }
                mp.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() = stopSelf()
                }, null)
                projection = mp
                playbackRecord(mp)
            } else {
                microphoneRecord()
            }
        } catch (e: Exception) {
            Log.e(TAG, "캡처 시작 실패", e)
            AnalysisState.update { it.copy(running = false, error = "소리를 받지 못했습니다: ${e.message}") }
            stopSelf()
            return START_NOT_STICKY
        }

        AnalysisState.update { UiState(running = true, source = source) }
        running = true
        worker = Thread({ loop(record!!) }, "ohmymusic-capture").also { it.start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        // 읽기 대기 중인 스레드를 깨우려면 녹음을 먼저 멈춘다
        record?.let { runCatching { it.stop() } }
        worker?.join(1000)
        record?.release()
        projection?.stop()
        AnalysisState.update { it.copy(running = false, listening = false) }
        super.onDestroy()
    }

    private fun format(rate: Int): AudioFormat = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
        .setSampleRate(rate)
        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
        .build()

    private fun bufferBytes(rate: Int) = maxOf(
        AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT),
        rate * 4, // 1초
    )

    @SuppressLint("MissingPermission") // RECORD_AUDIO 는 시작 전에 화면에서 받는다
    private fun playbackRecord(mp: MediaProjection): AudioRecord {
        val config = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        return AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(format(KeyFeatures.SAMPLE_RATE))
            .setBufferSizeInBytes(bufferBytes(KeyFeatures.SAMPLE_RATE))
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun microphoneRecord(): AudioRecord = AudioRecord.Builder()
        .setAudioSource(MediaRecorder.AudioSource.MIC)
        .setAudioFormat(format(KeyFeatures.SAMPLE_RATE))
        .setBufferSizeInBytes(bufferBytes(KeyFeatures.SAMPLE_RATE))
        .build()

    private fun loop(rec: AudioRecord) {
        val model = OnnxKeyModel(this)
        val analyzer = MusicAnalyzer(model)
        // 모델 입력은 22.05kHz 여야 한다. 기기가 다른 비율을 주면 선형 보간으로 맞춘다
        val inRate = rec.format.sampleRate
        val resampler = if (inRate != KeyFeatures.SAMPLE_RATE) LinearResampler(inRate, KeyFeatures.SAMPLE_RATE) else null
        Log.i(TAG, "캡처 시작: ${inRate}Hz")
        val read = FloatArray(inRate / 10)
        val tick = FloatArray(KeyFeatures.SAMPLE_RATE * TICK_SECONDS)
        var tickLen = 0
        var silentSeconds = 0
        var listening = false
        rec.startRecording()
        try {
            while (running) {
                val n = rec.read(read, 0, read.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) continue
                val chunk = resampler?.process(read, n) ?: read.copyOf(n)
                var i = 0
                while (i < chunk.size) {
                    val take = minOf(chunk.size - i, tick.size - tickLen)
                    System.arraycopy(chunk, i, tick, tickLen, take)
                    tickLen += take
                    i += take
                    if (tickLen < tick.size) continue
                    tickLen = 0

                    if (rms(tick) < SILENCE_RMS) {
                        silentSeconds += TICK_SECONDS
                        if (silentSeconds > SILENCE_RESET_SECONDS && listening) {
                            listening = false
                            analyzer.reset()
                            publish(null, false)
                        }
                        continue
                    }
                    silentSeconds = 0
                    listening = true
                    analyzer.append(tick)
                    publish(analyzer.estimate(), true)
                }
            }
        } finally {
            model.close()
        }
    }

    private fun publish(result: MusicAnalyzer.Result?, listening: Boolean) {
        AnalysisState.update {
            it.copy(key = result?.key, bpm = result?.bpm, listening = listening, heardSeconds = result?.seconds ?: 0.0)
        }
        val title = if (result == null) "음악을 기다리는 중" else {
            val key = result.key?.short ?: "…"
            val bpm = result.bpm?.let { "${Math.round(it)} BPM" } ?: "… BPM"
            "$key · $bpm"
        }
        val text = result?.key?.let { "${it.long} · 카멜롯 ${it.camelot} · 확신도 ${(it.confidence * 100).toInt()}%" }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(title, text))
    }

    private fun notification(title: String, text: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "정지", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, "분석 결과", NotificationManager.IMPORTANCE_LOW)
        channel.description = "지금 나오는 음악의 키와 BPM"
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun rms(x: FloatArray): Float {
        var s = 0.0
        for (v in x) s += v * v
        return sqrt(s / x.size).toFloat()
    }
}

/** 기기가 22.05kHz 를 주지 않을 때만 쓰는 대체 리샘플러(선형 보간). */
private class LinearResampler(inRate: Int, outRate: Int) {
    private val step = inRate.toDouble() / outRate
    private var pos = 0.0
    private var last = 0f

    fun process(x: FloatArray, n: Int): FloatArray {
        val out = ArrayList<Float>((n / step).toInt() + 2)
        // pos 는 이전 블록 마지막 샘플(last)을 -1 위치로 보는 좌표
        while (pos < n - 1) {
            val i = floor(pos).toInt()
            val f = (pos - i).toFloat()
            val a = if (i < 0) last else x[i]
            val b = x[i + 1]
            out.add(a + (b - a) * f)
            pos += step
        }
        pos -= n
        last = x[n - 1]
        return out.toFloatArray()
    }
}
