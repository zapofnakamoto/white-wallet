package whitewallet.core.designsystem

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.FileOutputStream
import kotlin.math.min
import kotlinx.coroutines.channels.Channel

/** How wide a printed page's content is laid out, and how wide at most it comes out on paper (15 cm). */
private val PAGE_WIDTH = 360.dp
private const val PRINTED_MAX_WIDTH_POINTS = 425f

/** Pixels per dp of a printed page: about 640 dpi, so the QR code and text stay sharp on paper. */
private const val PRINT_DENSITY = 4f

/**
 * While [printing], draws [page] for paper, unseen: in the brand's light theme on white, at a fixed size and print
 * density, with no semantics (it is not on screen). Once drawn, it hands the picture to [onPrinted], once.
 */
@Composable
fun PrintPage(printing: Boolean, onPrinted: (Bitmap) -> Unit, page: @Composable ColumnScope.() -> Unit) {
    if (!printing) return
    val layer = rememberGraphicsLayer()
    val printed by rememberUpdatedState(onPrinted)
    val drawn = remember { Channel<Unit>(Channel.CONFLATED) }
    Box(Modifier.size(0.dp).clearAndSetSemantics {}) {
        CompositionLocalProvider(LocalDensity provides Density(PRINT_DENSITY, fontScale = 1f)) {
            Box(
                Modifier
                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                    .requiredWidth(PAGE_WIDTH)
                    // Recorded, not drawn: nothing shows on screen.
                    .drawWithContent {
                        layer.record { this@drawWithContent.drawContent() }
                        drawn.trySend(Unit)
                    },
            ) {
                LightWalletTheme {
                    CompositionLocalProvider(LocalContentColor provides Color.Black) {
                        Column(
                            Modifier.background(Color.White).padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            content = page,
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(layer) {
        drawn.receive()
        // A software copy: a PDF page can't draw a hardware bitmap.
        printed(layer.toImageBitmap().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false))
    }
}

/** Opens Android's print dialog for [bitmap] as one page, as wide as it fits up to 15 cm, at the top. */
fun printBitmap(context: Context, jobName: String, bitmap: Bitmap) {
    val attributes = PrintAttributes.Builder().setColorMode(PrintAttributes.COLOR_MODE_COLOR).build()
    context.getSystemService(PrintManager::class.java).print(jobName, BitmapDocument(context, jobName, bitmap), attributes)
}

private class BitmapDocument(private val context: Context, private val name: String, private val bitmap: Bitmap) : PrintDocumentAdapter() {
    private var attributes: PrintAttributes? = null

    override fun onLayout(old: PrintAttributes?, new: PrintAttributes, cancel: CancellationSignal, callback: LayoutResultCallback, extras: Bundle?) {
        if (cancel.isCanceled) return callback.onLayoutCancelled()
        attributes = new
        val info = PrintDocumentInfo.Builder("$name.pdf").setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(1).build()
        callback.onLayoutFinished(info, new != old)
    }

    override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor, cancel: CancellationSignal, callback: WriteResultCallback) {
        val pdf = PrintedPdfDocument(context, attributes ?: return callback.onWriteFailed(null))
        try {
            val page = pdf.startPage(0)
            val box = page.info.contentRect
            val scale = min(min(box.width().toFloat(), PRINTED_MAX_WIDTH_POINTS) / bitmap.width, box.height().toFloat() / bitmap.height)
            val matrix = Matrix().apply {
                postScale(scale, scale)
                postTranslate(box.left + (box.width() - bitmap.width * scale) / 2, box.top.toFloat())
            }
            page.canvas.drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            pdf.finishPage(page)
            if (cancel.isCanceled) return callback.onWriteCancelled()
            FileOutputStream(destination.fileDescriptor).use(pdf::writeTo)
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.message)
        } finally {
            pdf.close()
        }
    }
}
