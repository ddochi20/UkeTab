package com.uketab.app

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Spotify basic-pitch (ICASSP 2022) 모델을 TensorFlow Lite로 기기에서 직접 실행해
 * 음원에서 음표를 추출하고, 멜로디(최고음)만 골라 Score로 만든다.
 */
class BasicPitch(ctx: Context) {
    companion object {
        const val SR = 22050
        const val FFT_HOP = 256
        const val N_SAMPLES = SR * 2 - FFT_HOP      // 43844
        const val N_FRAMES = 172
        const val N_BINS = 88
        const val MIDI_OFFSET = 21
        const val N_OVERLAP_FRAMES = 30
        const val OVERLAP_LEN = N_OVERLAP_FRAMES * FFT_HOP
        const val HOP = N_SAMPLES - OVERLAP_LEN     // 36164
        val FPS: Double = SR.toDouble() / FFT_HOP   // 86.13
    }

    data class Detected(val startFrame: Int, val endFrame: Int, val midi: Int, val amplitude: Float)

    private val interpreter: Interpreter
    private val noteIdx: Int
    private val onsetIdx: Int
    private val contourIdx: Int

    init {
        val afd = ctx.assets.openFd("nmp.tflite")
        val model = FileInputStream(afd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        val opts = Interpreter.Options().setNumThreads(4)
        interpreter = Interpreter(model, opts)
        noteIdx = interpreter.getOutputIndex("StatefulPartitionedCall:1")
        onsetIdx = interpreter.getOutputIndex("StatefulPartitionedCall:2")
        contourIdx = interpreter.getOutputIndex("StatefulPartitionedCall:0")
    }

    fun close() = interpreter.close()

    /** 모델 실행 → (note[nFrames][88], onset[nFrames][88]) */
    fun infer(audio: FloatArray, onProgress: (Float) -> Unit = {}): Pair<Array<FloatArray>, Array<FloatArray>> {
        val originalLen = audio.size
        val padded = FloatArray(OVERLAP_LEN / 2 + audio.size)
        System.arraycopy(audio, 0, padded, OVERLAP_LEN / 2, audio.size)

        val nWindows = (padded.size + HOP - 1) / HOP
        val nOlap = N_OVERLAP_FRAMES / 2
        val keep = N_FRAMES - 2 * nOlap
        val totalFrames = floor(originalLen * (FPS / SR)).toInt()
        val notes = Array(totalFrames) { FloatArray(N_BINS) }
        val onsets = Array(totalFrames) { FloatArray(N_BINS) }

        val input = ByteBuffer.allocateDirect(4 * N_SAMPLES).order(ByteOrder.nativeOrder())
        val outNote = Array(1) { Array(N_FRAMES) { FloatArray(N_BINS) } }
        val outOnset = Array(1) { Array(N_FRAMES) { FloatArray(N_BINS) } }
        val outContour = Array(1) { Array(N_FRAMES) { FloatArray(264) } }
        val outputs = HashMap<Int, Any>().apply {
            put(noteIdx, outNote); put(onsetIdx, outOnset); put(contourIdx, outContour)
        }

        var frameCursor = 0
        for (w in 0 until nWindows) {
            input.rewind()
            val start = w * HOP
            for (i in 0 until N_SAMPLES) {
                val p = start + i
                input.putFloat(if (p < padded.size) padded[p] else 0f)
            }
            input.rewind()
            interpreter.runForMultipleInputsOutputs(arrayOf<Any>(input), outputs)
            for (f in 0 until keep) {
                val t = frameCursor + f
                if (t >= totalFrames) break
                System.arraycopy(outNote[0][f + nOlap], 0, notes[t], 0, N_BINS)
                System.arraycopy(outOnset[0][f + nOlap], 0, onsets[t], 0, N_BINS)
            }
            frameCursor += keep
            onProgress((w + 1).toFloat() / nWindows)
            if (frameCursor >= totalFrames) break
        }
        return notes to onsets
    }

    /** basic_pitch.note_creation.output_to_notes_polyphonic 이식 */
    fun notesFromOutput(
        framesIn: Array<FloatArray>, onsetsIn: Array<FloatArray>,
        onsetThresh: Float = 0.5f, frameThresh: Float = 0.3f, minNoteLen: Int = 11,
        minMidi: Int = 40, maxMidi: Int = 96, energyTol: Int = 11, melodiaTrick: Boolean = true
    ): List<Detected> {
        val nFrames = framesIn.size
        if (nFrames < 3) return emptyList()
        val frames = Array(nFrames) { framesIn[it].copyOf() }
        var onsets = Array(nFrames) { onsetsIn[it].copyOf() }

        // 주파수 범위 제한
        val minIdx = (minMidi - MIDI_OFFSET).coerceIn(0, N_BINS)
        val maxIdx = (maxMidi - MIDI_OFFSET).coerceIn(0, N_BINS)
        for (t in 0 until nFrames) for (b in 0 until N_BINS) if (b < minIdx || b >= maxIdx) { frames[t][b] = 0f; onsets[t][b] = 0f }

        // infer onsets from frame differences
        onsets = inferOnsets(onsets, frames)

        // local maxima along time
        val onsetList = ArrayList<IntArray>()
        for (t in 1 until nFrames - 1) for (b in 0 until N_BINS) {
            val v = onsets[t][b]
            if (v > onsets[t - 1][b] && v > onsets[t + 1][b] && v >= onsetThresh) onsetList.add(intArrayOf(t, b))
        }
        onsetList.reverse() // backwards in time

        val remaining = Array(nFrames) { frames[it].copyOf() }
        val events = ArrayList<Detected>()

        for (o in onsetList) {
            val start = o[0]; val b = o[1]
            if (start >= nFrames - 1) continue
            var i = start + 1; var k = 0
            while (i < nFrames - 1 && k < energyTol) {
                if (remaining[i][b] < frameThresh) k++ else k = 0
                i++
            }
            i -= k
            if (i - start <= minNoteLen) continue
            for (t in start until i) {
                remaining[t][b] = 0f
                if (b < N_BINS - 1) remaining[t][b + 1] = 0f
                if (b > 0) remaining[t][b - 1] = 0f
            }
            var sum = 0f; for (t in start until i) sum += frames[t][b]
            events.add(Detected(start, i, b + MIDI_OFFSET, sum / (i - start)))
        }

        if (melodiaTrick) {
            while (true) {
                var best = 0f; var bt = 0; var bb = 0
                for (t in 0 until nFrames) for (b in 0 until N_BINS) if (remaining[t][b] > best) { best = remaining[t][b]; bt = t; bb = b }
                if (best <= frameThresh) break
                remaining[bt][bb] = 0f
                var i = bt + 1; var k = 0
                while (i < nFrames - 1 && k < energyTol) {
                    if (remaining[i][bb] < frameThresh) k++ else k = 0
                    remaining[i][bb] = 0f
                    if (bb < N_BINS - 1) remaining[i][bb + 1] = 0f
                    if (bb > 0) remaining[i][bb - 1] = 0f
                    i++
                }
                val iEnd = i - 1 - k
                i = bt - 1; k = 0
                while (i > 0 && k < energyTol) {
                    if (remaining[i][bb] < frameThresh) k++ else k = 0
                    remaining[i][bb] = 0f
                    if (bb < N_BINS - 1) remaining[i][bb + 1] = 0f
                    if (bb > 0) remaining[i][bb - 1] = 0f
                    i--
                }
                val iStart = i + 1 + k
                if (iEnd - iStart <= minNoteLen) continue
                var sum = 0f; for (t in iStart until iEnd) sum += frames[t][bb]
                events.add(Detected(iStart, iEnd, bb + MIDI_OFFSET, sum / (iEnd - iStart)))
            }
        }
        return events.sortedBy { it.startFrame }
    }

    private fun inferOnsets(onsets: Array<FloatArray>, frames: Array<FloatArray>, nDiff: Int = 2): Array<FloatArray> {
        val n = frames.size
        val diff = Array(n) { FloatArray(N_BINS) }
        var maxDiff = 0f; var maxOnset = 0f
        for (t in 0 until n) for (b in 0 until N_BINS) {
            var m = Float.MAX_VALUE
            for (d in 1..nDiff) { val prev = if (t - d >= 0) frames[t - d][b] else 0f; m = min(m, frames[t][b] - prev) }
            if (m < 0f || t < nDiff) m = 0f
            diff[t][b] = m; maxDiff = max(maxDiff, m); maxOnset = max(maxOnset, onsets[t][b])
        }
        if (maxDiff <= 0f) return onsets
        val out = Array(n) { FloatArray(N_BINS) }
        for (t in 0 until n) for (b in 0 until N_BINS) out[t][b] = max(onsets[t][b], maxOnset * diff[t][b] / maxDiff)
        return out
    }

    /** 온셋 세기 자기상관으로 템포(BPM) 추정 */
    fun estimateTempo(onsets: Array<FloatArray>): Double {
        val n = onsets.size
        if (n < FPS * 4) return 120.0
        val strength = FloatArray(n) { t -> var s = 0f; for (b in 0 until N_BINS) s += onsets[t][b]; s }
        val mean = strength.average().toFloat()
        for (i in strength.indices) strength[i] -= mean
        val minLag = (60.0 / 200 * FPS).toInt(); val maxLag = (60.0 / 50 * FPS).toInt()
        var bestLag = (60.0 / 120 * FPS).toInt(); var best = Float.NEGATIVE_INFINITY
        for (lag in minLag..maxLag) {
            var acc = 0f
            for (t in lag until n) acc += strength[t] * strength[t - lag]
            // 120 BPM 근처를 약간 선호 (librosa와 유사)
            val bpm = 60.0 * FPS / lag
            val prior = Math.exp(-0.5 * Math.pow(Math.log(bpm / 120.0) / 0.9, 2.0)).toFloat()
            val score = acc / (n - lag) * prior
            if (score > best) { best = score; bestLag = lag }
        }
        var bpm = 60.0 * FPS / bestLag
        while (bpm < 70) bpm *= 2
        while (bpm > 170) bpm /= 2
        return bpm
    }

    /** 이벤트 → 멜로디(최고음) → 16분음표 양자화 → Score (4/4, divisions=4) */
    fun toMelodyScore(events: List<Detected>, bpm: Double, title: String, minAmp: Float = 0.25f): Score {
        val notes = events.filter { it.amplitude >= minAmp }
        require(notes.isNotEmpty()) { "음을 찾지 못했습니다." }
        val framesPer16th = FPS * 60.0 / bpm / 4
        val total = (notes.maxOf { it.endFrame } / framesPer16th).toInt() + 1
        val gridPitch = IntArray(total) { -1 }
        val gridOnset = IntArray(total) { -1 }
        for (e in notes) {
            val a = (e.startFrame / framesPer16th).roundToInt()
            val b = max(a + 1, (e.endFrame / framesPer16th).roundToInt())
            for (i in a until min(b, total)) {
                if (gridPitch[i] < 0 || e.midi > gridPitch[i]) { gridPitch[i] = e.midi; gridOnset[i] = e.startFrame }
            }
        }
        // 연속 셀 병합 → (pitch, length)
        val seq = ArrayList<IntArray>() // [pitch(-1=rest), len]
        for (i in 0 until total) {
            val p = gridPitch[i]; val o = gridOnset[i]
            if (seq.isNotEmpty() && seq.last()[0] == p && seq.last()[2] == o) seq.last()[1]++
            else seq.add(intArrayOf(p, 1, o))
        }
        while (seq.isNotEmpty() && seq.first()[0] < 0) seq.removeAt(0)

        val out = ArrayList<NoteEvent>()
        var measure = 1; var pos = 0
        for (s in seq) {
            var len = s[1]
            while (len > 0) {
                val d = min(len, 16 - pos)
                if (s[0] < 0) out.add(NoteEvent(null, d, measure, false, "rest"))
                else out.add(NoteEvent(s[0], d, measure, false, MusicXmlParser.noteName(s[0])))
                pos += d; len -= d
                if (pos >= 16) { pos = 0; measure++ }
            }
        }
        return Score("$title (${bpm.roundToInt()} BPM)", 4, 4, 4, out)
    }
}
