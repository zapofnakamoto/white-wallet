package whitewallet.core.wallet

import kotlin.coroutines.cancellation.CancellationException

/**
 * runCatching for suspend code: a failure becomes a [Result], but a cancellation goes on. runCatching would catch it too, and the coroutine
 * would carry on as if it hadn't been cancelled (detekt's SuspendFunSwallowedCancellation).
 */
@Suppress("TooGenericExceptionCaught") // Catches what runCatching catches, cancellation apart.
inline fun <T> runSuspendCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
