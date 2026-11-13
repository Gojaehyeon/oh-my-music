package kr.tntlabs.ohmymusic.eval

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kr.tntlabs.ohmymusic.core.KeyFeatures
import kr.tntlabs.ohmymusic.core.KeyModel
import kr.tntlabs.ohmymusic.core.MusicAnalyzer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors

/**
 * 표준입력으로 오디오 경로를 한 줄씩 받아, 앱과 같은 스트리밍 경로(2초씩)로 분석한 결과를 출력한다.
 * 출력: 경로 \t 20초 시점 키 \t 끝 시점 키 \t 끝 시점 BPM   (키 = 0~23 인덱스, 없으면 -1)
 * 사용: build/install/eval/bin/eval <keynet.onnx> < files.txt
 */
fun main(args: Array<String>) {
    val env = OrtEnvironment.getEnvironment()
    val session = env.createSession(args[0], OrtSession.SessionOptions())
    val model = KeyModel { frames ->
        val buf = FloatBuffer.allocate(frames.size * KeyFeatures.BINS)
        frames.forEach { buf.put(it) }
        buf.flip()
        OnnxTensor.createTensor(env, buf, longArrayOf(1, frames.size.toLong(), KeyFeatures.BINS.toLong())).use { t ->
            session.run(mapOf("spec" to t)).use { out ->
                @Suppress("UNCHECKED_CAST")
                (out[0].value as Array<FloatArray>)[0]
            }
        }
    }
    val paths = generateSequence(::readLine).filter { it.isNotBlank() }.toList()
    val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    val lines = paths.map { path ->
        pool.submit<String> {
            val x = decode(path)
            val analyzer = MusicAnalyzer(model)
            val chunk = KeyFeatures.SAMPLE_RATE * 2
            var at20 = -1
            var i = 0
            // 키 채점에는 20초 시점과 끝 시점만 필요하다(앱은 2초마다 estimate 를 부른다)
            while (i < x.size) {
                val n = minOf(chunk, x.size - i)
                analyzer.append(x.copyOfRange(i, i + n))
                i += n
                if (at20 < 0 && analyzer.seconds >= 20) at20 = analyzer.estimateKey()?.index() ?: -1
            }
            val last = analyzer.estimate()
            "$path\t$at20\t${last.key?.index() ?: -1}\t${last.bpm ?: -1f}"
        }
    }
    lines.forEach { println(it.get()) }
    pool.shutdown()
}

private fun kr.tntlabs.ohmymusic.core.Key.index() = tonic + if (minor) 12 else 0

/** prep.py 와 같은 디코딩: ffmpeg 로 22.05kHz 모노 float32. */
private fun decode(path: String): FloatArray {
    val p = ProcessBuilder("ffmpeg", "-v", "quiet", "-i", path, "-ac", "1", "-ar", "22050", "-f", "f32le", "-")
        .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val bytes = p.inputStream.readBytes()
    p.waitFor()
    val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    return FloatArray(fb.remaining()).also { fb.get(it) }
}
