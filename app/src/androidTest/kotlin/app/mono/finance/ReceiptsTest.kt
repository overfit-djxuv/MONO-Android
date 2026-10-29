package app.mono.finance

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.platform.app.InstrumentationRegistry
import app.mono.domain.ReceiptParser
import app.mono.finance.receipts.ReceiptOcr
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptsTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MonoApp

    @Test fun anEnglishReceiptRenderedAsAnImageIsReadOnThePhone() {
        val lines = listOf("VILLAGE GROCER", "Date: 2026-10-12", "Milk 1L          RM 7.90", "Bread            RM 9.00", "TOTAL            RM 16.90")
        val bmp = Bitmap.createBitmap(900, 700, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp).apply { drawColor(Color.WHITE) }
        val paint = Paint().apply { color = Color.BLACK; textSize = 56f; typeface = Typeface.MONOSPACE; isAntiAlias = true }
        lines.forEachIndexed { i, l -> canvas.drawText(l, 40f, 90f + i * 110f, paint) }
        val result = runBlocking { ReceiptOcr.recognize(bmp) }
        val parsed = ReceiptParser.parse(result.lines)
        assertEquals("OCR text was: ${result.text}", 1690L, parsed.totalCents)
        assertEquals("MYR", parsed.currencyCode)
    }

    @Test fun photosAreStoredPreparedAndRemoved() {
        val store = app.receiptStore
        val bmp = Bitmap.createBitmap(3000, 1500, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val data = store.prepared(bmp)
        assertTrue("capped in size", data.size < 400_000)
        val name = store.store(data, "../escape-test.jpg")
        try {
            assertEquals("a path in the name can't leave the folder", "escape-test.jpg", name)
            assertTrue(store.exists(name)); assertNotNull(store.thumbnail(name)); assertEquals(store.hash(data), store.hash(store.data(name)!!))
        } finally { store.delete(name) }
        assertTrue(!store.exists(name))
    }
}
