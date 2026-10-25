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
    val skeleton = when {
        sameYear && then.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) -> "jm"
        sameYear -> "dMMMjm"
        else -> "yMMMdjm"
    }
    return localTime(millis, skeleton)
}
