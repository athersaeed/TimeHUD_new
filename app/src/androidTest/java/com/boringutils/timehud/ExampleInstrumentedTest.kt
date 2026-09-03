package com.boringutils.timehud

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.pm.PackageManager
import android.os.Build
import android.view.LayoutInflater
import com.boringutils.timehud.blocking.TimeHudAccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.boringutils.timehud", appContext.packageName)
    }

    @Test
    fun manifest_registers_boot_receiver_and_services() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageManager = context.packageManager
        val flags = (
            PackageManager.GET_RECEIVERS or
                PackageManager.GET_SERVICES or
                PackageManager.GET_META_DATA
            ).toLong()
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(flags)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(context.packageName, flags.toInt())
        }

        val receiverNames = packageInfo.receivers?.map { it.name }.orEmpty()
        assertTrue(receiverNames.contains(BootReceiver::class.java.name))

        val overlayServiceInfo = packageInfo.services
            ?.firstOrNull { it.name == OverlayService::class.java.name }
        assertNotNull(overlayServiceInfo)
        assertFalse(overlayServiceInfo!!.exported)

        val serviceNames = packageInfo.services?.map { it.name }.orEmpty()
        assertTrue(serviceNames.contains(OverlayService::class.java.name))
        assertTrue(serviceNames.contains(TimeHudAccessibilityService::class.java.name))

        val accessibilityServiceInfo = packageInfo.services
            ?.firstOrNull { it.name == TimeHudAccessibilityService::class.java.name }
        assertNotNull(accessibilityServiceInfo)
        assertFalse(accessibilityServiceInfo!!.exported)
        assertEquals(Manifest.permission.BIND_ACCESSIBILITY_SERVICE, accessibilityServiceInfo.permission)
        assertEquals(
            R.xml.timehud_accessibility_service,
            accessibilityServiceInfo.metaData.getInt("android.accessibilityservice")
        )
    }

    @Test
    fun passive_overlay_is_an_interactive_bubble() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bubble = LayoutInflater.from(context).inflate(R.layout.overlay_passive, null)

        assertTrue(bubble.isClickable)
        assertTrue(bubble.isFocusable)
        assertNotNull(bubble.background)
        assertNotNull(bubble.findViewById<android.widget.TextView>(R.id.tv_time))
    }
}
