package whitewallet.lnurlcash.client

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import okhttp3.Interceptor

/**
 * For developers: every request behaves as on a bad connection ([profile]), to see how the app does for people on
 * one. The delay comes before a request goes out, and a dropped request never reaches the mint, so nothing is spent
 * or changed by it: the wallet sees what it sees when the phone has no connection.
 *
 * Constructed without a profile, requests wait (up to [WAIT_MILLIS]) until the first is set, so the first requests
 * after a start already behave as chosen.
 */
public class NetworkConditions(
    initial: Profile? = Profile.Normal,
    private val random: Random = Random.Default,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    public enum class Profile(public val delayMillis: LongRange, public val dropPercent: Int) {
        Normal(0L..0L, 0),

        /** Every request takes 0.2 s longer: a refresh, about 20 requests in a row, a few seconds. */
        Slow(200L..200L, 0),

        /** Every request takes 6 s longer. */
        VerySlow(6_000L..6_000L, 0),

        /** Requests take 0.3 to 4 s longer, and one in five never reaches the mint. */
        Flaky(300L..4_000L, 20),
    }

    private val known = CountDownLatch(if (initial == null) 1 else 0)

    @Volatile
    public var profile: Profile = initial ?: Profile.Normal
        set(value) {
            field = value
            known.countDown()
        }

    /** An OkHttp interceptor that delays or drops requests as [profile] says. Add it after [RequestLog.interceptor]. */
    public fun interceptor(): Interceptor = Interceptor { chain ->
        known.await(WAIT_MILLIS, TimeUnit.MILLISECONDS)
        val p = profile
        if (p.delayMillis.last > 0) sleep(random.nextLong(p.delayMillis.first, p.delayMillis.last + 1))
        if (chain.call().isCanceled()) throw IOException("Canceled")
        if (random.nextInt(100) < p.dropPercent) throw IOException("simulated: no connection")
        chain.proceed(chain.request())
    }

    private companion object {
        const val WAIT_MILLIS = 5_000L
    }
}
