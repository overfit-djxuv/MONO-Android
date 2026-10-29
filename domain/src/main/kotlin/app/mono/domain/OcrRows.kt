package app.mono.domain

import kotlin.math.abs
import kotlin.math.max

/** A piece of recognised text with its position on the photo (y grows downward, 0 = top). */
data class OcrBox(val text: String, val minX: Double, val midY: Double, val height: Double)

object OcrRows {
    /** Joins text fragments that sit on the same printed row (an item name on the left, its price on the right) into one line, top to bottom. */
    fun rows(boxes: List<OcrBox>): List<String> {
        val rows = ArrayList<MutableList<OcrBox>>()
        for (b in boxes.sortedBy { it.midY }) {
            val hit = rows.indexOfLast { abs(it[0].midY - b.midY) < max(b.height, it[0].height) * 0.6 }
            if (hit >= 0) rows[hit].add(b) else rows.add(mutableListOf(b))
        }
        return rows.map { r -> r.sortedBy { it.minX }.joinToString("  ") { it.text } }
    }
}
