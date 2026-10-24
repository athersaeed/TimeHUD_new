package com.boringutils.timehud.blocking

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boringutils.timehud.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppWindowInspectorTest {
    @Test
    fun x_explore_description_inside_selected_tab_is_recognized() {
        val signals = inspectTab(selected = true, navigationDescription = "Explore")
        assertEquals(AppSurface.EXPLORE, AppSurfaceClassifier.classify(X_PACKAGE, signals))
        assertEquals(setOf("explore"), signals.selectedLabels)
    }

    @Test
    fun unselected_explore_tab_does_not_block_home() {
        val signals = inspectTab(selected = false, navigationDescription = "Explore")
        assertEquals(AppSurface.OTHER, AppSurfaceClassifier.classify(X_PACKAGE, signals))
        assertFalse(signals.hasSelectedLabel("explore"))
    }

    @Test
    fun selected_post_text_and_other_packages_do_not_inherit_explore_selection() {
        val postSignals = inspectTab(selected = true, navigationDescription = null)
        assertEquals(AppSurface.OTHER, AppSurfaceClassifier.classify(X_PACKAGE, postSignals))
        val otherSignals = inspectTab(
            selected = true,
            navigationDescription = "Explore",
            packageName = INSTAGRAM_PACKAGE
        )
        assertFalse(otherSignals.hasSelectedLabel("explore"))
    }

    private fun inspectTab(
        selected: Boolean,
        navigationDescription: String?,
        packageName: String = X_PACKAGE
    ): AppUiSignals {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        return ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val screen = FrameLayout(activity)
                val tab = FrameLayout(activity).apply {
                    isFocusable = true
                    isSelected = selected
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                }
                val child = TextView(activity).apply {
                    text = "Explore"
                    contentDescription = navigationDescription
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                }
                tab.addView(child, FrameLayout.LayoutParams(200, 100))
                screen.addView(tab, FrameLayout.LayoutParams(300, 150))
                activity.setContentView(screen)
            }
            instrumentation.waitForIdleSync()
            val automation = instrumentation.uiAutomation
            automation.waitForIdle(100L, 5_000L)
            val root = checkNotNull(automation.rootInActiveWindow)
            AppWindowInspector.inspect(root, null, packageName)
        }
    }
}
