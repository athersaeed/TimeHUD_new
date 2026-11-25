package com.boringutils.timehud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationGrantPolicyTest {
    private val api32 = 32
    private val api33 = 33

    @Test fun notification_grant_requests_runtime_permission_on_api_33_when_missing() {
        assertEquals(
            NotificationGrantAction.REQUEST_RUNTIME_PERMISSION,
            NotificationGrantPolicy.actionFor(api33, runtimePermissionGranted = false)
        )
    }

    @Test fun notification_grant_opens_settings_before_api_33_or_when_permission_held() {
        assertEquals(
            NotificationGrantAction.OPEN_SETTINGS,
            NotificationGrantPolicy.actionFor(api32, runtimePermissionGranted = true)
        )
        assertEquals(
            NotificationGrantAction.OPEN_SETTINGS,
            NotificationGrantPolicy.actionFor(api33, runtimePermissionGranted = true)
        )
    }

    @Test fun permanent_denial_redirects_to_settings_but_first_denial_does_not() {
        assertTrue(
            NotificationGrantPolicy.shouldOpenSettingsAfterDenial(
                granted = false,
                shouldShowRationale = false
            )
        )
        assertFalse(
            NotificationGrantPolicy.shouldOpenSettingsAfterDenial(
                granted = false,
                shouldShowRationale = true
            )
        )
        assertFalse(
            NotificationGrantPolicy.shouldOpenSettingsAfterDenial(
                granted = true,
                shouldShowRationale = false
            )
        )
    }

    @Test fun hud_start_prompts_for_notifications_only_once_on_api_33() {
        assertTrue(NotificationGrantPolicy.shouldPromptOnStart(api33, false, false))
        assertFalse(NotificationGrantPolicy.shouldPromptOnStart(api33, false, true))
        assertFalse(NotificationGrantPolicy.shouldPromptOnStart(api33, true, false))
        assertFalse(NotificationGrantPolicy.shouldPromptOnStart(api32, true, false))
    }
}
