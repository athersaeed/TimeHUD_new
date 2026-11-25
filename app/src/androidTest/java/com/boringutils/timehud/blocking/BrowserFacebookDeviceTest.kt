package com.boringutils.timehud.blocking

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.boringutils.timehud.MainActivity
import org.junit.Assert.*
import org.junit.Test

class BrowserFacebookDeviceTest {
    @Test fun browser_toggle_reuses_native_sections_and_removal_clears_it() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = AppBlockSettings.ruleFor(context, FACEBOOK_PACKAGE)
        try {
            val rule = AppBlockRule(FACEBOOK_PACKAGE, blockedSurfaces = setOf(AppSurface.HOME_FEED, AppSurface.STORIES), browserFacebook = true)
            AppBlockSettings.saveRule(context, rule)
            assertEquals(rule, AppBlockSettings.ruleFor(context, FACEBOOK_PACKAGE))
            AppBlockSettings.saveRule(context, rule.copy(blockedSurfaces = setOf(AppSurface.REELS)))
            val loaded = checkNotNull(AppBlockSettings.ruleFor(context, FACEBOOK_PACKAGE))
            assertTrue(loaded.browserFacebook)
            assertEquals(setOf(AppSurface.REELS), loaded.blockedSurfaces)
            AppBlockSettings.saveRule(context, loaded.copy(browserFacebook = false))
            assertFalse(AppBlockSettings.ruleFor(context, FACEBOOK_PACKAGE)!!.browserFacebook)
            assertEquals(setOf(AppSurface.REELS), AppBlockSettings.ruleFor(context, FACEBOOK_PACKAGE)!!.blockedSurfaces)
            AppBlockSettings.removeRule(context, FACEBOOK_PACKAGE)
            assertNull(AppBlockSettings.ruleFor(context, FACEBOOK_PACKAGE))
            assertFalse(context.getSharedPreferences("timehud_app_blocking", 0).contains("facebook_browser_enabled"))
        } finally {
            original?.let { AppBlockSettings.saveRule(context, it) }
                ?: AppBlockSettings.removeRule(context, FACEBOOK_PACKAGE)
            assertTrue(context.getSharedPreferences("timehud_app_blocking", 0).edit().commit())
        }
    }

    @Test fun previous_browser_section_settings_migrate_to_one_toggle_without_changing_native_choices() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("timehud_app_blocking", 0)
        val original = AppBlockSettings.ruleFor(context, FACEBOOK_PACKAGE)
        try {
            AppBlockSettings.saveRule(context, AppBlockRule(FACEBOOK_PACKAGE, blockedSurfaces = setOf(AppSurface.STORIES)))
            assertTrue(prefs.edit().remove("facebook_browser_enabled")
                .putStringSet("facebook_browser_surfaces", setOf("REELS", "STORIES")).commit())
            val migrated = checkNotNull(AppBlockSettings.ruleFor(context, FACEBOOK_PACKAGE))
            assertTrue(migrated.browserFacebook)
            assertEquals(setOf(AppSurface.STORIES), migrated.blockedSurfaces)
            AppBlockSettings.saveRule(context, migrated.copy(browserFacebook = false))
            assertFalse(prefs.contains("facebook_browser_surfaces"))
            assertFalse(AppBlockSettings.ruleFor(context, FACEBOOK_PACKAGE)!!.browserFacebook)
        } finally {
            original?.let { AppBlockSettings.saveRule(context, it) }
                ?: AppBlockSettings.removeRule(context, FACEBOOK_PACKAGE)
            assertTrue(prefs.edit().commit())
        }
    }

    @Test fun inspector_uses_selected_navigation_and_leaves_visible_navigation_above_shield() {
        fixture("m.facebook.com", false) { observation ->
            assertEquals(AppSurface.HOME_FEED, BrowserFacebookClassifier.classify(observation.signals))
            assertEquals(setOf(AppSurface.HOME_FEED), observation.signals.selectedNavigation)
            assertNotNull(observation.contentBounds)
            assertTrue(observation.contentBounds!!.top >= 320)
            assertFalse(observation.signals.controls.contains("close story"))
            assertFalse(observation.signals.toString().contains("PRIVATE"))
            assertFalse(observation.signals.toString().contains("facebook.com"))
        }
    }

    @Test fun unrelated_site_and_focused_address_remain_available() {
        fixture("example.com", false) {
            assertEquals(AppSurface.OTHER, BrowserFacebookClassifier.classify(it.signals))
        }
        fixture("m.facebook.com/reel/123", true) {
            assertTrue(it.signals.editingAddress)
            assertEquals(AppSurface.OTHER, BrowserFacebookClassifier.classify(it.signals))
        }
    }

    private fun fixture(url: String, editing: Boolean, check: (BrowserFacebookObservation) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val screen = FrameLayout(activity)
                val address = EditText(activity).apply {
                    setText(url)
                    isFocusableInTouchMode = editing
                    isFocusable = editing
                    accessibilityDelegate = object : View.AccessibilityDelegate() {
                        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.viewIdResourceName = "com.android.chrome:id/url_bar"
                        }
                    }
                }
                screen.addView(address, FrameLayout.LayoutParams(900, 100))
                val web = FrameLayout(activity).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    accessibilityDelegate = object : View.AccessibilityDelegate() {
                        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.className = "android.webkit.WebView"
                        }
                    }
                }
                screen.addView(web, FrameLayout.LayoutParams(950, 1500).apply { topMargin = 120 })
                web.addView(Button(activity).apply { contentDescription = "Facebook logo" },
                    FrameLayout.LayoutParams(500, 80))
                listOf("feed, 1,800 new, 1 of 6", "reels, 100 new, 4 of 6").forEachIndexed { i, label ->
                    web.addView(View(activity).apply {
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                        isClickable = true
                        isSelected = i == 0
                        contentDescription = label
                    }, FrameLayout.LayoutParams(400, 100).apply { topMargin = 100; leftMargin = i * 450 })
                }
                web.addView(EditText(activity).apply {
                    setText("PRIVATE_DRAFT_FIXTURE"); isFocusable = false
                }, FrameLayout.LayoutParams(800, 100).apply { topMargin = 400 })
                web.addView(TextView(activity).apply { text = "PRIVATE_POST_FIXTURE" },
                    FrameLayout.LayoutParams(800, 100).apply { topMargin = 550 })
                web.addView(Button(activity).apply { contentDescription = "Close story"; visibility = View.INVISIBLE },
                    FrameLayout.LayoutParams(800, 100).apply { topMargin = 700 })
                activity.setContentView(screen)
                if (editing) address.requestFocus()
            }
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.waitForIdle(100, 5000)
            check(BrowserFacebookInspector.inspect(checkNotNull(instrumentation.uiAutomation.rootInActiveWindow),
                "com.android.chrome"))
        }
    }
}
