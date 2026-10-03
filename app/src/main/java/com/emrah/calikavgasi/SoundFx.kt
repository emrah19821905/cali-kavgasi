package com.emrah.calikavgasi

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Ses dosyası gerektirmez: efektler kodla üretilip SoundPool'a yüklenir. */
class SoundFx(private val ctx: Context) {
    var enabled = true
    private val rate = 22050
    private val ids = HashMap<String, Int>()
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ).build()

    init {
        add("shoot", sweep(900f, 300f, .09f, .5f))
        add("hit", sweep(220f, 120f, .1f, .7f, .5f))
        add("kill", sweep(300f, 1000f, .28f, .6f))
        add("super", sweep(200f, 1300f, .45f, .7f, .15f))
        add("over", sweep(700f, 120f, .7f, .6f))
        add("click", sweep(650f, 650f, .05f, .5f))
    }

    fun play(name: String, vol: Float = 1f) {
        if (!enabled) return
        ids[name]?.let { pool.play(it, vol, vol, 1, 0, 1f) }
    }

    fun release() = pool.release()

    private fun add(name: String, pcm: ShortArray) {
        val f = File(ctx.cacheDir, "$name.wav")
        f.writeBytes(wav(pcm))
        ids[name] = pool.load(f.absolutePath, 1)
    }

    private fun sweep(f0: Float, f1: Float, dur: Float, vol: Float, noise: Float = 0f): ShortArray {
        val n = (rate * dur).toInt()
        val out = ShortArray(n)
        val r = Random(1)
        var ph = 0.0
        for (i in 0 until n) {
            val t = i / n.toFloat()
            ph += 2 * PI * (f0 + (f1 - f0) * t) / rate
            val v = sin(ph).toFloat() * (1 - noise) + (r.nextFloat() * 2 - 1) * noise
            val env = (1 - t) * (1 - t)
            out[i] = (v * env * vol * 32767).toInt().toShort()
        }
        return out
    }

    private fun wav(pcm: ShortArray): ByteArray {
        val bb = ByteBuffer.allocate(44 + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + pcm.size * 2)
        bb.put("WAVE".toByteArray()); bb.put("fmt ".toByteArray())
        bb.putInt(16); bb.putShort(1); bb.putShort(1)
        bb.putInt(rate); bb.putInt(rate * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(pcm.size * 2)
        for (s in pcm) bb.putShort(s)
        return bb.array()
    }
}
