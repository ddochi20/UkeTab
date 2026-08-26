package com.uketab.app

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.util.zip.ZipInputStream

/** 하나의 음(또는 쉼표). midi == null 이면 쉼표. */
data class NoteEvent(
    val midi: Int?,          // MIDI 번호 (C4 = 60)
    val duration: Int,       // divisions 단위 길이
    val measure: Int,
    val chord: Boolean,      // 앞 음과 동시에 울리는 화음 음인지
    val name: String         // 표시용 (예: "C4")
)

data class Score(
    val title: String,
    val divisions: Int,      // 4분음표 하나의 divisions 수
    val beats: Int,
    val beatType: Int,
    val notes: List<NoteEvent>
)

object MusicXmlParser {

    /** .musicxml / .xml / .mxl(압축) 모두 처리 */
    fun parse(input: InputStream, fileName: String): Score {
        return if (fileName.lowercase().endsWith(".mxl")) parseMxl(input) else parseXml(input)
    }

    private fun parseMxl(input: InputStream): Score {
        val zip = ZipInputStream(input)
        var entry = zip.nextEntry
        var best: ByteArray? = null
        while (entry != null) {
            val n = entry.name.lowercase()
            if (!entry.isDirectory && !n.startsWith("meta-inf") && (n.endsWith(".xml") || n.endsWith(".musicxml"))) {
                best = zip.readBytes()
                if (!n.contains("container")) break
            }
            entry = zip.nextEntry
        }
        requireNotNull(best) { "mxl 안에서 악보 XML을 찾지 못했습니다." }
        return parseXml(best.inputStream())
    }

    private fun parseXml(input: InputStream): Score {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val p = factory.newPullParser()
        p.setInput(input, null)
        // DOCTYPE 참조로 외부 접속 방지
        try { p.setFeature("http://xmlpull.org/v1/doc/features.html#process-docdecl", false) } catch (_: Exception) {}

        val notes = mutableListOf<NoteEvent>()
        var title = ""
        var divisions = 1
        var beats = 4
        var beatType = 4
        var measure = 0
        var firstPartOnly = true
        var partCount = 0
        var inPart = false

        // 현재 note 상태
        var inNote = false
        var step = ""; var alter = 0; var octave = 4
        var isRest = false; var isChord = false; var dur = 0
        var text = ""
        var path = ArrayList<String>()

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    path.add(p.name)
                    when (p.name) {
                        "part" -> { partCount++; inPart = partCount == 1 || !firstPartOnly }
                        "measure" -> if (inPart) measure++
                        "note" -> { inNote = true; step = ""; alter = 0; octave = 4; isRest = false; isChord = false; dur = 0 }
                        "rest" -> if (inNote) isRest = true
                        "chord" -> if (inNote) isChord = true
                    }
                }
                XmlPullParser.TEXT -> text = p.text ?: ""
                XmlPullParser.END_TAG -> {
                    val parent = if (path.size >= 2) path[path.size - 2] else ""
                    when (p.name) {
                        "work-title", "movement-title" -> if (title.isBlank()) title = text.trim()
                        "divisions" -> divisions = text.trim().toIntOrNull() ?: divisions
                        "beats" -> beats = text.trim().toIntOrNull() ?: beats
                        "beat-type" -> beatType = text.trim().toIntOrNull() ?: beatType
                        "step" -> if (inNote) step = text.trim()
                        "alter" -> if (inNote) alter = text.trim().toDoubleOrNull()?.toInt() ?: 0
                        "octave" -> if (inNote) octave = text.trim().toIntOrNull() ?: 4
                        "duration" -> if (inNote && parent == "note") dur = text.trim().toIntOrNull() ?: 0
                        "note" -> {
                            if (inPart && inNote) {
                                if (isRest) {
                                    notes.add(NoteEvent(null, dur, measure, false, "rest"))
                                } else if (step.isNotEmpty()) {
                                    val midi = toMidi(step, alter, octave)
                                    notes.add(NoteEvent(midi, dur, measure, isChord, noteName(midi)))
                                }
                            }
                            inNote = false
                        }
                    }
                    path.removeAt(path.size - 1)
                    text = ""
                }
            }
            ev = p.next()
        }
        require(notes.isNotEmpty()) { "악보에서 음표를 찾지 못했습니다." }
        return Score(title.ifBlank { "Untitled" }, divisions, beats, beatType, notes)
    }

    private val stepSemis = mapOf("C" to 0, "D" to 2, "E" to 4, "F" to 5, "G" to 7, "A" to 9, "B" to 11)

    fun toMidi(step: String, alter: Int, octave: Int): Int =
        12 * (octave + 1) + (stepSemis[step] ?: 0) + alter

    private val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    fun noteName(midi: Int): String = names[((midi % 12) + 12) % 12] + (midi / 12 - 1)
}
