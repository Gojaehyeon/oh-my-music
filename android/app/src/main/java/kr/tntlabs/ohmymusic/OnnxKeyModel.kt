package kr.tntlabs.ohmymusic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import kr.tntlabs.ohmymusic.core.KeyFeatures
import kr.tntlabs.ohmymusic.core.KeyModel
import java.nio.FloatBuffer

/** assets/keynet.onnx (eval/export_onnx.py 로 만든 5개 앙상블) 를 ONNX Runtime 으로 돌린다. */
class OnnxKeyModel(context: Context) : KeyModel, AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession =
        env.createSession(context.assets.open("keynet.onnx").use { it.readBytes() }, OrtSession.SessionOptions())

    override fun probabilities(frames: List<FloatArray>): FloatArray {
        val buf = FloatBuffer.allocate(frames.size * KeyFeatures.BINS)
        frames.forEach { buf.put(it) }
        buf.flip()
        OnnxTensor.createTensor(env, buf, longArrayOf(1, frames.size.toLong(), KeyFeatures.BINS.toLong())).use { input ->
            session.run(mapOf("spec" to input)).use { out ->
                @Suppress("UNCHECKED_CAST")
                return (out[0].value as Array<FloatArray>)[0]
            }
        }
    }

    override fun close() = session.close()
}
