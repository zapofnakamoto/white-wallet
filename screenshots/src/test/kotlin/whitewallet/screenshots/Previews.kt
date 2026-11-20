package whitewallet.screenshots

import sergio.sastre.composable.preview.scanner.android.AndroidComposablePreviewScanner
import sergio.sastre.composable.preview.scanner.android.AndroidPreviewInfo
import sergio.sastre.composable.preview.scanner.core.preview.ComposablePreview
import sergio.sastre.composable.preview.scanner.core.preview.getAnnotation
import whitewallet.core.designsystem.CheckAll
import whitewallet.core.designsystem.Moment

/** Every preview of the screen modules (whitewallet.*), one per configuration of its [whitewallet.core.designsystem.WalletPreviews]. */
object Previews {
    /** When a frame is captured, from the moment the preview is first drawn: while a success picture moves, and once it is over. */
    const val MOVING_MILLIS = 600L
    const val SETTLED_MILLIS = 3_000L

    val all: List<ComposablePreview<AndroidPreviewInfo>> by lazy {
        AndroidComposablePreviewScanner()
            .scanPackageTrees("whitewallet")
            .includePrivatePreviews()
            .includeAnnotationInfoForAllOf(CheckAll::class.java, Moment::class.java)
            .getPreviews()
            .sortedBy { "${it.declaringClass}.${it.methodName}" }
    }

    /** `full`: every configuration; `check`: light, and every configuration of a [CheckAll] preview. */
    fun selected(preview: ComposablePreview<AndroidPreviewInfo>, set: String): Boolean =
        set == "full" || preview.previewInfo.name == "light" || preview.getAnnotation<CheckAll>() != null

    /** A [Moment] also while it moves; in the `check` set only a [Moment] that is also [CheckAll]. */
    fun frames(preview: ComposablePreview<AndroidPreviewInfo>, set: String): List<Long> {
        val moving = preview.getAnnotation<Moment>() != null && (set == "full" || preview.getAnnotation<CheckAll>() != null)
        return if (moving) listOf(MOVING_MILLIS, SETTLED_MILLIS) else listOf(SETTLED_MILLIS)
    }

    /** `ScanDoneFound.de.600ms.png`: the preview without its `Preview` suffix, the configuration and the moment. */
    fun fileName(preview: ComposablePreview<AndroidPreviewInfo>, millis: Long): String =
        "${preview.methodName.removeSuffix("Preview")}.${preview.previewInfo.name}.${millis}ms.png"
}
