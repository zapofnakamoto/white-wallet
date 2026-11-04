package whitewallet.core.designsystem

import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The locale's own pattern for [skeleton] (field order, 12/24-hour, month names), e.g. "jm" for a time of day. */
fun localTime(millis: Long, skeleton: String): String {
    val locale = Locale.getDefault()
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(Date(millis))
}

/** A moment as briefly as it stays clear: the time for today, the day and time this year, the full date otherwise. */
fun recentTime(millis: Long, now: Long = System.currentTimeMillis()): String {
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val sameYear = then.get(Calendar.YEAR) == today.get(Calendar.YEAR)
    val skeleton =
        when {
            sameYear && then.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) -> "jm"
            sameYear -> "dMMMjm"
            else -> "yMMMdjm"
        }
    return localTime(millis, skeleton)
}

/**
 * A duration as a count of one unit, for a sentence that has a plural per unit (so translations never assemble a sentence from a unit
 * word). [exact]: the largest unit it is a whole number of, for a brand's configured duration; [atLeast]: the largest unit it reaches,
 * rounded down, for a time that passed.
 */
data class WholeDuration(val unit: Unit, val count: Int) {
    enum class Unit(val millis: Long) {
        Seconds(1_000),
        Minutes(60_000),
        Hours(3_600_000),
        Days(86_400_000),
    }

    companion object {
        fun exact(millis: Long, units: List<Unit> = Unit.entries): WholeDuration {
            val unit = units.sortedDescending().firstOrNull { millis % it.millis == 0L } ?: units.min()
            return WholeDuration(unit, ((millis + unit.millis - 1) / unit.millis).toInt())
        }

        fun atLeast(millis: Long, units: List<Unit> = Unit.entries): WholeDuration {
            val unit = units.sortedDescending().firstOrNull { millis >= it.millis } ?: units.min()
            return WholeDuration(unit, (millis / unit.millis).toInt().coerceAtLeast(1))
        }
    }
}
