package com.uketab.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/** 안드로이드 MediaCodec으로 음원을 디코딩해 22050Hz 모노 float 배열로 만든다. */
object AudioDecoder {
    const val TARGET_SR = 22050

    fun decode(ctx: Context, uri: Uri, maxSeconds: Int = 600, onProgress: (Float) -> Unit = {}): FloatArray {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        var track = -1; var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; fmt = f; break }
        }
        require(track >= 0 && fmt != null) { "오디오 트랙을 찾지 못했습니다." }
        val fmt: MediaFormat = fmt
        ex.selectTrack(track)
        val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L

        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()

        var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmEncoding = if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) else 2

        val mono = ArrayList<Float>(sr * 60)
        val info = MediaCodec.BufferInfo()
        var inputDone = false; var outputDone = false
        val maxSamples = maxSeconds.toLong() * sr

        while (!outputDone) {
            if (!inputDone) {
                val idx = codec.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    val buf = codec.getInputBuffer(idx)!!
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                    } else {
                        codec.queueInputBuffer(idx, 0, n, ex.sampleTime, 0)
                        if (durationUs > 0) onProgress(ex.sampleTime.toFloat() / durationUs)
                        ex.advance()
                    }
                }
            }
            val oidx = codec.dequeueOutputBuffer(info, 10_000)
            when {
                oidx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                }
                oidx >= 0 -> {
                    val buf = codec.getOutputBuffer(oidx)!!
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    buf.order(ByteOrder.LITTLE_ENDIAN)
                    if (pcmEncoding == 4) { // float
                        val fb = buf.asFloatBuffer()
                        val frames = fb.remaining() / ch
                        for (i in 0 until frames) { var s = 0f; for (c in 0 until ch) s += fb.get(); mono.add(s / ch) }
                    } else {
                        val sb = buf.asShortBuffer()
                        val frames = sb.remaining() / ch
                        for (i in 0 until frames) { var s = 0f; for (c in 0 until ch) s += sb.get(); mono.add(s / ch / 32768f) }
                    }
                    codec.releaseOutputBuffer(oidx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    if (mono.size >= maxSamples) outputDone = true
                }
            }
        }
        codec.stop(); codec.release(); ex.release()
        require(mono.isNotEmpty()) { "디코딩 결과가 비어 있습니다." }
        return resample(mono.toFloatArray(), sr, TARGET_SR)
    }

    /** 간단한 저역통과 + 선형보간 리샘플 */
    private fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to) return x
        var src = x
        if (from > to) {
            // 다운샘플 전 간단한 이동평균 저역통과 (에일리어싱 완화)
            val k = maxOf(1, from / to)
            if (k > 1) {
                val y = FloatArray(x.size); var acc = 0f
                for (i in x.indices) { acc += x[i]; if (i >= k) acc -= x[i - k]; y[i] = acc / k }
                src = y
            }
        }
        val ratio = from.toDouble() / to
        val n = (src.size / ratio).toInt()
        val out = FloatArray(n)
        for (i in 0 until n) {
            val pos = i * ratio; val i0 = pos.toInt(); val frac = (pos - i0).toFloat()
            val a = src[i0]; val b = if (i0 + 1 < src.size) src[i0 + 1] else a
            out[i] = a + (b - a) * frac
        }
        return out
    }
}
