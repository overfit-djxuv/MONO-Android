package app.mono.finance.receipts

import android.graphics.Bitmap
import app.mono.domain.OcrBox
import app.mono.domain.OcrRows
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Reads receipt text on the phone with the Latin-script model bundled in the app. Nothing is sent anywhere. */
object ReceiptOcr {
    class Result(val lines: List<String>, val text: String)

    suspend fun recognize(image: Bitmap): Result {
        val recogniser = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val text = recogniser.process(InputImage.fromBitmap(image, 0)).await()
            val h = image.height.toDouble().coerceAtLeast(1.0)
            val boxes = text.textBlocks.flatMap { it.lines }.mapNotNull { l ->
                val r = l.boundingBox ?: return@mapNotNull null
                // ML Kit already groups words into lines; rows() then joins a name and its price printed far apart.
                OcrBox(l.text, r.left.toDouble() / image.width, (r.top + r.bottom) / 2.0 / h, r.height() / h)
            }
            return Result(OcrRows.rows(boxes), text.text)
        } finally { recogniser.close() }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
    }
}
