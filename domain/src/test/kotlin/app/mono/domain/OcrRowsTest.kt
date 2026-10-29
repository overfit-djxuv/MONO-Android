package app.mono.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrRowsTest {
    @Test fun rowsJoinNameAndPriceOnTheSamePrintedRow() {
        val boxes = listOf(OcrBox("RM 7.90", 0.7, 0.50, 0.02), OcrBox("Milk 1L", 0.1, 0.495, 0.02), OcrBox("TOTAL", 0.1, 0.70, 0.02), OcrBox("Shop", 0.3, 0.1, 0.03))
        assertEquals(listOf("Shop", "Milk 1L  RM 7.90", "TOTAL"), OcrRows.rows(boxes))
    }
}
