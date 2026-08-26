package com.uketab.app

enum class Tuning(val label: String, val openMidi: IntArray) {
    // 줄 순서: 인덱스 0 = 위쪽 줄(A), 3 = 아래쪽 줄(G)  → 타브 표기 순서
    HIGH_G("High-G (G4 C4 E4 A4)", intArrayOf(69, 64, 60, 67)),
    LOW_G("Low-G (G3 C4 E4 A4)", intArrayOf(69, 64, 60, 55)),
    BARITONE("Baritone (D3 G3 B3 E4)", intArrayOf(64, 59, 55, 50));
    val stringNames get() = openMidi.map { MusicXmlParser.noteName(it).dropLast(1) }
}

/** 타브 한 칸(하나의 시간 위치)에 찍힐 프렛들 */
data class TabColumn(
    val frets: IntArray,      // 줄별 프렛, -1 = 안 침
    val duration: Int,
    val measure: Int,
    val outOfRange: Boolean,
    val transposed: Int        // 옥타브 이동 횟수 (0이면 원음)
)

object TabConverter {
    const val MAX_FRET = 15

    fun convert(score: Score, tuning: Tuning, capo: Int = 0): List<TabColumn> {
        val result = mutableListOf<TabColumn>()
        var lastFrets: IntArray? = null
        var prevFretPos = 0 // 손 위치 추정용

        for (n in score.notes) {
            if (n.midi == null) {
                result.add(TabColumn(intArrayOf(-1, -1, -1, -1), n.duration, n.measure, false, 0))
                lastFrets = null
                continue
            }
            var midi = n.midi - capo
            var shifted = 0
            // 음역 밖이면 옥타브 이동
            val lowest = tuning.openMidi.min()
            val highest = tuning.openMidi.max() + MAX_FRET
            while (midi < lowest) { midi += 12; shifted++ }
            while (midi > highest) { midi -= 12; shifted-- }

            if (n.chord && lastFrets != null) {
                val used = lastFrets.indices.filter { lastFrets!![it] >= 0 }.toSet()
                val pick = bestString(midi, tuning, prevFretPos, used)
                val col = result.removeAt(result.size - 1)
                val frets = col.frets.copyOf()
                var oor = col.outOfRange
                if (pick != null) frets[pick.first] = pick.second else oor = true
                val newCol = TabColumn(frets, col.duration, col.measure, oor, col.transposed)
                result.add(newCol)
                lastFrets = frets
            } else {
                val pick = bestString(midi, tuning, prevFretPos, emptySet())
                val frets = intArrayOf(-1, -1, -1, -1)
                var oor = false
                if (pick != null) { frets[pick.first] = pick.second; if (pick.second > 0) prevFretPos = pick.second }
                else oor = true
                result.add(TabColumn(frets, n.duration, n.measure, oor, shifted))
                lastFrets = frets
            }
        }
        return result
    }

    /** 가장 낮은 프렛 & 이전 손 위치에 가까운 줄 선택 */
    private fun bestString(midi: Int, tuning: Tuning, handPos: Int, used: Set<Int>): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var bestCost = Int.MAX_VALUE
        for (s in tuning.openMidi.indices) {
            if (s in used) continue
            val fret = midi - tuning.openMidi[s]
            if (fret < 0 || fret > MAX_FRET) continue
            val cost = fret * 2 + (if (fret == 0) 0 else Math.abs(fret - handPos))
            if (cost < bestCost) { bestCost = cost; best = s to fret }
        }
        return best
    }

    /** ASCII 타브 렌더링 */
    fun render(score: Score, cols: List<TabColumn>, tuning: Tuning, columnsPerLine: Int = 16): String {
        val sb = StringBuilder()
        sb.append(score.title).append("   ").append("${score.beats}/${score.beatType}   ")
            .append(tuning.label).append("\n\n")
        val names = tuning.stringNames.map { it.padEnd(2) }
        var i = 0
        while (i < cols.size) {
            val lines = Array(4) { StringBuilder(names[it]).append("|") }
            var lastMeasure = cols[i].measure
            var count = 0
            while (i < cols.size && count < columnsPerLine) {
                val c = cols[i]
                if (c.measure != lastMeasure) {
                    lines.forEach { it.append("|") }
                    lastMeasure = c.measure
                }
                val cellW = (c.frets.maxOrNull()?.let { if (it >= 10) 3 else 2 } ?: 2) + spacing(c.duration, score.divisions)
                for (s in 0..3) {
                    val f = c.frets[s]
                    val txt = if (f < 0) "" else f.toString()
                    lines[s].append(txt.padEnd(cellW, '-'))
                }
                if (c.outOfRange) lines[0].setLength(lines[0].length) // 표시 없음, 아래 경고로 대체
                i++; count++
            }
            lines.forEach { it.append("|") }
            lines.forEach { sb.append(it).append("\n") }
            sb.append("\n")
        }
        val oor = cols.count { it.outOfRange }
        val shifted = cols.count { it.transposed != 0 }
        if (shifted > 0) sb.append("※ 음역을 맞추려고 ${shifted}개 음을 옥타브 이동했습니다.\n")
        if (oor > 0) sb.append("※ ${oor}개 음은 우쿨렐레로 연주할 수 없어 생략했습니다.\n")
        return sb.toString()
    }

    private fun spacing(duration: Int, divisions: Int): Int {
        if (divisions <= 0) return 1
        val quarters = duration.toDouble() / divisions
        return when {
            quarters >= 4 -> 7
            quarters >= 2 -> 5
            quarters >= 1 -> 3
            quarters >= 0.5 -> 2
            else -> 1
        }
    }
}
