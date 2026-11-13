package com.codeair.mhe.runtime

/**
 * Phase 5: pure quiet-hours + delivery policy for local notifications.
 *
 * No Android framework types here on purpose: the window math is unit
 * testable without a device, and NotificationCoordinator applies the
 * verdict at post time.
 */
object NotificationPolicy {
    enum class Delivery { ALERT, SILENT }

    /** Default quiet window: 10pm to 7am. */
    const val QUIET_START_HOUR = 22
    const val QUIET_END_HOUR = 7

    /**
     * True when [hourOfDay] (0-23) falls inside [startHour, endHour).
     * Overnight windows (start > end) wrap past midnight; start == end
     * means quiet hours are off.
     */
    fun isQuietHour(
        hourOfDay: Int,
        startHour: Int = QUIET_START_HOUR,
        endHour: Int = QUIET_END_HOUR,
    ): Boolean {
        if (startHour == endHour) return false
        return if (startHour < endHour) {
            hourOfDay in startHour until endHour
        } else {
            hourOfDay >= startHour || hourOfDay < endHour
        }
    }

    /**
     * Failures always alert, so a broken run is never silently missed;
     * anything else goes silent during quiet hours.
     */
    fun deliveryFor(failed: Boolean, quiet: Boolean): Delivery =
        if (failed || !quiet) Delivery.ALERT else Delivery.SILENT
}
