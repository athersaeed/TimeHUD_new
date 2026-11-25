package com.boringutils.timehud

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boringutils.timehud.blocking.BrickModeSettings
import com.boringutils.timehud.blocking.BrickModeSchedule
import com.boringutils.timehud.blocking.BrickModeCatalogLoader
import com.boringutils.timehud.blocking.UsageRestrictionMode
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OverlayModePickerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = object : ContextWrapper(instrumentation.targetContext) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("overlay_picker_test_$name", mode)
    }
    private var controller: ActiveOverlayContentController? = null
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var overlay: View
    private var settingsOpened = false

    @Before
    fun clearSettings() {
        context.getSharedPreferences("timehud_brick_mode", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun cleanup() {
        instrumentation.runOnMainSync { controller?.dispose() }
        scenario?.close()
        clearSettings()
    }

    private fun openOverlay(appControlEnabled: Boolean = true) {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { activity ->
            overlay = LayoutInflater.from(activity).inflate(R.layout.overlay_active, null)
            activity.setContentView(overlay)
            controller = ActiveOverlayContentController(
                context = context, handler = Handler(Looper.getMainLooper()), rootView = overlay,
                timeText = "1h13m", requiresCloseDelay = true,
                onClose = {}, onOpenBrickMode = { settingsOpened = true },
                isAppControlEnabled = { appControlEnabled }
            )
        }
        instrumentation.waitForIdleSync()
    }

    private fun choose(id: Int) {
        instrumentation.runOnMainSync { overlay.findViewById<Button>(id).performClick() }
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync {
                ready = overlay.findViewById<Button>(R.id.btn_confirm_overlay_mode).isEnabled
            }
            if (ready) return
            Thread.sleep(20L)
        }
        fail("Mode confirmation did not finish loading")
    }

    @Test
    fun cancelling_preserves_the_existing_timer_and_app_lists() {
        BrickModeSettings.setPackageAllowed(context, UsageRestrictionMode.SLEEP, "saved.audio", true)
        BrickModeSettings.startTimed(context, UsageRestrictionMode.BRICK, 60)
        val before = BrickModeSettings.load(context)
        openOverlay()
        choose(R.id.btn_overlay_mode_sleep)
        assertEquals(before, BrickModeSettings.load(context))
        instrumentation.runOnMainSync {
            overlay.findViewById<Button>(R.id.btn_cancel_overlay_mode).performClick()
            assertEquals(View.GONE, overlay.findViewById<View>(R.id.layout_mode_confirmation).visibility)
        }
        assertEquals(before, BrickModeSettings.load(context))
    }

    @Test
    fun confirmation_switches_mode_using_the_saved_list_without_changing_schedules() {
        BrickModeSettings.setPackageAllowed(context, UsageRestrictionMode.SLEEP, "saved.audio", true)
        BrickModeSettings.startTimed(context, UsageRestrictionMode.BRICK, 60)
        BrickModeSettings.saveSchedule(context, BrickModeSchedule(
            id = "saved-schedule", enabled = false, mode = UsageRestrictionMode.RESTRICTED,
            daysOfWeek = setOf(java.util.Calendar.MONDAY), startMinuteOfDay = 60,
            durationMinutes = 60
        ))
        val schedules = BrickModeSettings.loadSchedules(context)
        assertEquals(1, schedules.size)
        openOverlay()
        choose(R.id.btn_overlay_mode_sleep)
        instrumentation.runOnMainSync {
            overlay.findViewById<Button>(R.id.btn_confirm_overlay_mode).performClick()
            assertTrue(overlay.findViewById<Button>(R.id.btn_overlay_mode_sleep).isSelected)
        }
        val saved = BrickModeSettings.load(context)
        assertTrue(saved.enabled)
        assertEquals(UsageRestrictionMode.SLEEP, saved.mode)
        assertNull(saved.endsAtEpochMs)
        assertEquals(setOf("saved.audio"), saved.sleepAllowedPackages)
        assertEquals(schedules, BrickModeSettings.loadSchedules(context))
    }

    @Test
    fun confirmation_counts_installed_chosen_apps_and_excludes_protected_essentials() {
        val catalog = BrickModeCatalogLoader.load(context)
        val chosen = catalog.apps.filterNot { it.alwaysAvailable }.take(2)
        assertTrue(chosen.isNotEmpty())
        chosen.forEach {
            BrickModeSettings.setPackageAllowed(context, UsageRestrictionMode.SLEEP, it.packageName, true)
        }
        catalog.apps.firstOrNull { it.alwaysAvailable }?.let {
            BrickModeSettings.setPackageAllowed(context, UsageRestrictionMode.SLEEP, it.packageName, true)
        }
        openOverlay()
        choose(R.id.btn_overlay_mode_sleep)
        instrumentation.runOnMainSync {
            assertEquals(
                context.resources.getQuantityString(
                    R.plurals.overlay_mode_confirm_message, chosen.size,
                    context.getString(R.string.sleep_mode_name), chosen.size
                ), overlay.findViewById<TextView>(R.id.tv_overlay_mode_message).text.toString()
            )
        }
    }

    @Test
    fun missing_app_control_opens_settings_without_starting_a_mode() {
        openOverlay(appControlEnabled = false)
        choose(R.id.btn_overlay_mode_sleep)
        instrumentation.runOnMainSync {
            val confirm = overlay.findViewById<Button>(R.id.btn_confirm_overlay_mode)
            assertEquals(context.getString(R.string.brick_mode_enable_access), confirm.text.toString())
            confirm.performClick()
        }
        assertTrue(settingsOpened)
        assertFalse(BrickModeSettings.load(context).enabled)
    }

    @Test
    fun modes_can_be_chosen_during_close_delay_and_show_the_zero_app_confirmation() {
        openOverlay()
        instrumentation.runOnMainSync {
            assertFalse(overlay.findViewById<Button>(R.id.btn_close_active).isEnabled)
        }
        saveScreenshot("overlay-mode-row.png")
        choose(R.id.btn_overlay_mode_sleep)
        instrumentation.runOnMainSync {
            val message = overlay.findViewById<TextView>(R.id.tv_overlay_mode_message).text.toString()
            assertEquals(
                context.resources.getQuantityString(
                    R.plurals.overlay_mode_confirm_message, 0,
                    context.getString(R.string.sleep_mode_name), 0
                ), message
            )
        }
        saveScreenshot("overlay-mode-confirmation.png")
        instrumentation.runOnMainSync {
            val modal = overlay.findViewById<android.widget.FrameLayout>(R.id.layout_mode_confirmation)
            (modal.getChildAt(0) as android.widget.ScrollView).fullScroll(View.FOCUS_DOWN)
        }
        saveScreenshot("overlay-mode-confirmation-actions.png")
        instrumentation.runOnMainSync {
            val bounds = android.graphics.Rect()
            assertTrue(overlay.findViewById<Button>(R.id.btn_cancel_overlay_mode).getGlobalVisibleRect(bounds))
            assertTrue(bounds.height() > 0)
        }
    }

    private fun saveScreenshot(name: String) {
        instrumentation.waitForIdleSync()
        val frames = java.util.concurrent.CountDownLatch(1)
        instrumentation.runOnMainSync {
            overlay.postOnAnimation { overlay.postOnAnimation { frames.countDown() } }
        }
        assertTrue(frames.await(3, java.util.concurrent.TimeUnit.SECONDS))
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null), name).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
