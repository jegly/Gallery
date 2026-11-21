package com.gal.util

import com.gal.model.DateGroup
import com.gal.model.Media
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Groups by calendar day using [pickDate] (defaults to [Media.dateTaken], the capture time).
 * Days appear in the order they first occur in the list and items keep their incoming order,
 * so a newest-first list gives newest day first and an oldest-first list gives oldest first.
 */
fun List<Media>.groupedByDay(
    now: Date = Date(),
    pickDate: (Media) -> Date = { it.dateTaken },
): List<DateGroup> {
    if (isEmpty()) return emptyList()
    val sdfThisYear = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
    val sdfOlder = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    val cal = Calendar.getInstance()

    fun startOfDay(d: Date): Long {
        cal.time = d
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    val today = startOfDay(now)
    val yesterday = today - 24L * 60 * 60 * 1000
    cal.time = now
    val thisYear = cal.get(Calendar.YEAR)

    // groupBy keeps first-occurrence key order (LinkedHashMap).
    return groupBy { startOfDay(pickDate(it)) }
        .map { (key, items) ->
            val label = when (key) {
                today -> "Today"
                yesterday -> "Yesterday"
                else -> {
                    val d = Date(key)
                    cal.time = d
                    if (cal.get(Calendar.YEAR) == thisYear) sdfThisYear.format(d)
                    else sdfOlder.format(d)
                }
            }
            DateGroup(label, items)
        }
}
