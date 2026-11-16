package jp.kintore.timer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** 短く静かな通知音を、コードで合成して鳴らす。他のアプリの音（YouTubeなど）は止めずに重ねて鳴る。 */
object Sounds {
    private const val RATE = 44100
    private val handler = Handler(Looper.getMainLooper())

    // 開始：低い音から高い音へ（上がる2音）／終了：高い音から低い音へ（下がる2音）
    private val start by lazy { build(listOf(660.0 to 130, 880.0 to 230)) }
    private val end by lazy { build(listOf(740.0 to 130, 494.0 to 280)) }

    private fun build(notes: List<Pair<Double, Int>>): ShortArray {
        val out = ArrayList<Short>()
        for ((hz, ms) in notes) {
            val n = RATE * ms / 1000
            for (i in 0 until n) {
                val t = i.toDouble() / RATE
                val attack = minOf(1.0, t / 0.008)
                val decay = exp(-3.2 * i / n)
                val wave = sin(2 * PI * hz * t) + 0.25 * sin(2 * PI * hz * 2 * t)
                out.add((wave * attack * decay * 0.42 * Short.MAX_VALUE).toInt().toShort())
            }
            repeat(RATE / 50) { out.add(0) }  // 音のあいだの短い無音
        }
        return out.toShortArray()
    }

    private fun play(pcm: ShortArray) {
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC).build()
            track.write(pcm, 0, pcm.size)
            track.play()
            handler.postDelayed({ try { track.release() } catch (e: Exception) {} }, pcm.size * 1000L / RATE + 300)
        } catch (e: Exception) { /* 音が出せなくてもタイマーは止めない */ }
    }

    fun playStart() = play(start)
    fun playEnd() = play(end)
}
