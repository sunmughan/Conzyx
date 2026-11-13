package com.codeair.mhe.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPolicyTest {

    @Test
    fun `overnight window covers late night and early morning`() {
        assertTrue(NotificationPolicy.isQuietHour(22))
        assertTrue(NotificationPolicy.isQuietHour(23))
        assertTrue(NotificationPolicy.isQuietHour(3))
        assertFalse(NotificationPolicy.isQuietHour(7))
        assertFalse(NotificationPolicy.isQuietHour(12))
    }

    @Test
    fun `day window does not wrap past midnight`() {
        assertTrue(NotificationPolicy.isQuietHour(10, 9, 17))
        assertFalse(NotificationPolicy.isQuietHour(8, 9, 17))
        assertFalse(NotificationPolicy.isQuietHour(17, 9, 17))
    }

    @Test
    fun `equal start and end means quiet hours off`() {
        assertFalse(NotificationPolicy.isQuietHour(12, 8, 8))
    }

    @Test
    fun `failures always alert`() {
        assertEquals(NotificationPolicy.Delivery.ALERT, NotificationPolicy.deliveryFor(true, true))
        assertEquals(NotificationPolicy.Delivery.ALERT, NotificationPolicy.deliveryFor(true, false))
    }

    @Test
    fun `quiet non-failures go silent, daytime posts alert`() {
        assertEquals(NotificationPolicy.Delivery.SILENT, NotificationPolicy.deliveryFor(false, true))
        assertEquals(NotificationPolicy.Delivery.ALERT, NotificationPolicy.deliveryFor(false, false))
    }
}
