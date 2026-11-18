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

class BrowserInstagramDeviceTest {
    @Test fun browser_setting_round_trips_without_changing_native_rules() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = AppBlockSettings.ruleFor(context, INSTAGRAM_PACKAGE)
        try {
            val enabled = (original ?: AppBlockRule(INSTAGRAM_PACKAGE)).copy(browserInstagramTest = true)
            AppBlockSettings.saveRule(context, enabled)
            assertEquals(enabled, AppBlockSettings.ruleFor(context, INSTAGRAM_PACKAGE))
            AppBlockSettings.saveRule(context, enabled.copy(browserInstagramTest = false))
            assertFalse(AppBlockSettings.ruleFor(context, INSTAGRAM_PACKAGE)?.browserInstagramTest == true)
        } finally {
            // Explicit opt-in is used only by the manual device verification command.
            if (InstrumentationRegistry.getArguments().getString("enableBrowserTest") == "true") {
                AppBlockSettings.saveRule(context,
                    (original ?: AppBlockRule(INSTAGRAM_PACKAGE)).copy(browserInstagramTest = true))
                assertTrue(AppBlockSettings.ruleFor(context, INSTAGRAM_PACKAGE)!!.browserInstagramTest)
                // Flush apply() before the instrumentation process exits.
                assertTrue(context.getSharedPreferences("timehud_app_blocking", 0).edit().commit())
            } else {
                original?.let { AppBlockSettings.saveRule(context, it) }
                    ?: AppBlockSettings.removeRule(context, INSTAGRAM_PACKAGE)
            }
        }
    }

    @Test fun inspector_reads_fixed_controls_but_not_messages_or_hidden_controls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val screen = FrameLayout(activity)
                val address = TextView(activity).apply {
                    text = "instagram.com/direct/t/123/"
                    accessibilityDelegate = object : View.AccessibilityDelegate() {
                        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.viewIdResourceName = "com.android.chrome:id/url_bar"
                        }
                    }
                }
                screen.addView(address, FrameLayout.LayoutParams(800, 100))
                val web = FrameLayout(activity).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    accessibilityDelegate = object : View.AccessibilityDelegate() {
                        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.className = "android.webkit.WebView"
                        }
                    }
                }
                screen.addView(web, FrameLayout.LayoutParams(900, 1500).apply { topMargin = 120 })
                listOf("Conversation information", "Voice Clip", "Add Photo or Video").forEachIndexed { i, label ->
                    web.addView(Button(activity).apply { text = label },
                        FrameLayout.LayoutParams(800, 120).apply { topMargin = i * 140 })
                }
                web.addView(EditText(activity).apply { setText("PRIVATE_DRAFT_FIXTURE") },
                    FrameLayout.LayoutParams(800, 120).apply { topMargin = 500 })
                web.addView(TextView(activity).apply { text = "PRIVATE_MESSAGE_FIXTURE" },
                    FrameLayout.LayoutParams(800, 120).apply { topMargin = 650 })
                web.addView(Button(activity).apply { text = "Close"; visibility = View.INVISIBLE },
                    FrameLayout.LayoutParams(800, 120).apply { topMargin = 800 })
                activity.setContentView(screen)
            }
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.waitForIdle(100, 5000)
            val observation = BrowserInstagramInspector.inspect(
                checkNotNull(instrumentation.uiAutomation.rootInActiveWindow), "com.android.chrome")
            assertEquals(AppSurface.MESSAGE_THREAD, BrowserInstagramClassifier.classify(observation.signals))
            assertTrue(observation.signals.hasComposer)
            assertNotNull(observation.contentBounds)
            assertFalse(observation.signals.controls.contains("close"))
            assertEquals(setOf("conversation information", "voice clip", "add photo or video"),
                observation.signals.controls)
        }
    }
}
