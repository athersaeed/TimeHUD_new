package com.boringutils.timehud

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.pm.PackageManager
import android.os.Build
import android.view.LayoutInflater
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
    fun manifest_registers_boot_receiver_and_overlay_service() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageManager = context.packageManager
        val flags = (PackageManager.GET_RECEIVERS or PackageManager.GET_SERVICES).toLong()
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
        assertEquals(listOf(OverlayService::class.java.name), serviceNames)
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
