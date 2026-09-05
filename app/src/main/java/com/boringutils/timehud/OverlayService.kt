package com.boringutils.timehud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.boringutils.timehud.ui.navigation.TimeHudDestination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class ActiveOverlayTrigger(
    val requiresCloseDelay: Boolean,
    val returnsHomeOnClose: Boolean
) {
    BUBBLE_TAP(requiresCloseDelay = false, returnsHomeOnClose = false),
    FIVE_MINUTE_BUCKET(requiresCloseDelay = true, returnsHomeOnClose = false),
    APP_BLOCK(requiresCloseDelay = true, returnsHomeOnClose = true)
}

class OverlayService : Service() {

    companion object {
        private const val TAG = "TimeHUD"
        private const val CHANNEL_ID = "timehud_overlay"
        private const val NOTIFICATION_ID = 1
        private const val TICK_INTERVAL_MS = 10_000L
        private const val FIVE_MINUTES_MS = 5 * 60 * 1_000L
        private const val BUBBLE_SIZE_DP = 64
        private const val BUBBLE_EDGE_MARGIN_DP = 8
        private const val BUBBLE_DEFAULT_TOP_DP = 80
        private const val BUBBLE_PREFERENCES = "timehud_bubble"
        private const val BUBBLE_X_KEY = "bubble_x"
        private const val BUBBLE_Y_KEY = "bubble_y"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var handler: Handler
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var passiveView: View? = null
    private var activeView: View? = null
    private var activeContentController: ActiveOverlayContentController? = null
    private var isActiveState = false
    private var isBlockingOverlayVisible = false
    private var lastTriggeredBucket: Long = -1L
    private var latestTimeText: String = "…"
    private var overlayFailed = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        handler = Handler(Looper.getMainLooper())

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        OverlayServiceStateStore.markRunning()

        serviceScope.launch {
            BlockingOverlayStateStore.isVisible.collect(::handleBlockingOverlayVisibility)
        }
        showPassiveOverlay()
        serviceScope.launch {
            while (isActive && !overlayFailed) {
                val totalMs = withContext(Dispatchers.IO) {
                    try {
                        ScreenTimeDisplay.queryMs(this@OverlayService)
                    } catch (_: RuntimeException) {
                        null
                    }
                }
                // Keep the last successful reading on provider failure.
                if (!overlayFailed) totalMs?.let(::updateScreenTime)
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (overlayFailed) return START_NOT_STICKY
        StartupPreferences.markHudRunning(this)
        OverlayServiceStateStore.markRunning()
        refreshNotification(getString(R.string.notif_text))
        Log.d(TAG, "Overlay service started")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        handler.removeCallbacksAndMessages(null)
        activeContentController?.dispose()
        activeContentController = null
        removeOverlay(passiveView)
        removeOverlay(activeView)
        OverlayServiceStateStore.markStopped()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_description)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String = getString(R.string.notif_text)) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(createMainActivityPendingIntent())
            .setOngoing(true)
            .build()

    private fun refreshNotification(contentText: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun createMainActivityPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun makeBubbleLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }

    private fun showPassiveOverlay() {
        if (passiveView != null || isBlockingOverlayVisible || overlayFailed) return
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_passive, null)
        val params = makeBubbleLayoutParams()
        val savedPosition = loadBubblePosition()
        val position = BubblePositioning.clamp(
            x = savedPosition?.x ?: defaultBubbleX(),
            y = savedPosition?.y ?: dpToPx(BUBBLE_DEFAULT_TOP_DP),
            screenWidth = resources.displayMetrics.widthPixels,
            screenHeight = resources.displayMetrics.heightPixels,
            bubbleWidth = dpToPx(BUBBLE_SIZE_DP),
            bubbleHeight = dpToPx(BUBBLE_SIZE_DP)
        )
        params.x = position.x
        params.y = position.y

        passiveView = view
        updateBubbleText(view, getFormattedScreenTime())
        attachBubbleTouchListener(view, params)
        attachOverlay(view, params)
    }

    private fun attachBubbleTouchListener(view: View, params: WindowManager.LayoutParams) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var isDragging = false

        view.setOnClickListener {
            showActiveOverlay(
                timeText = getFormattedScreenTime(),
                trigger = ActiveOverlayTrigger.BUBBLE_TAP
            )
        }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    isDragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - downRawX
                    val deltaY = event.rawY - downRawY
                    if (!isDragging &&
                        deltaX * deltaX + deltaY * deltaY >=
                        (touchSlop * touchSlop).toFloat()
                    ) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val position = BubblePositioning.clamp(
                            x = startX + deltaX.toInt(),
                            y = startY + deltaY.toInt(),
                            screenWidth = resources.displayMetrics.widthPixels,
                            screenHeight = resources.displayMetrics.heightPixels,
                            bubbleWidth = view.width.coerceAtLeast(dpToPx(BUBBLE_SIZE_DP)),
                            bubbleHeight = view.height.coerceAtLeast(dpToPx(BUBBLE_SIZE_DP))
                        )
                        params.x = position.x
                        params.y = position.y
                        if (passiveView === view) {
                            try {
                                windowManager.updateViewLayout(view, params)
                            } catch (_: RuntimeException) {
                                stopAfterOverlayFailure()
                            }
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        saveBubblePosition(params.x, params.y)
                    } else {
                        view.performClick()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        saveBubblePosition(params.x, params.y)
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun defaultBubbleX(): Int =
        resources.displayMetrics.widthPixels -
            dpToPx(BUBBLE_SIZE_DP + BUBBLE_EDGE_MARGIN_DP)

    private fun loadBubblePosition(): BubblePosition? {
        val preferences = getSharedPreferences(BUBBLE_PREFERENCES, MODE_PRIVATE)
        if (!preferences.contains(BUBBLE_X_KEY) || !preferences.contains(BUBBLE_Y_KEY)) {
            return null
        }
        return BubblePosition(
            x = preferences.getInt(BUBBLE_X_KEY, 0),
            y = preferences.getInt(BUBBLE_Y_KEY, 0)
        )
    }

    private fun saveBubblePosition(x: Int, y: Int) {
        getSharedPreferences(BUBBLE_PREFERENCES, MODE_PRIVATE).edit {
            putInt(BUBBLE_X_KEY, x)
            putInt(BUBBLE_Y_KEY, y)
        }
    }

    private fun showActiveOverlay(timeText: String, trigger: ActiveOverlayTrigger) {
        if (isActiveState || isBlockingOverlayVisible || overlayFailed) return
        isActiveState = true

        removeOverlay(passiveView)
        passiveView = null

        val inflater = LayoutInflater.from(this)
        activeView = inflater.inflate(R.layout.overlay_active, null)
        activeView?.let { view ->
            activeContentController = ActiveOverlayContentController(
                context = this,
                handler = handler,
                rootView = view,
                timeText = timeText,
                requiresCloseDelay = trigger.requiresCloseDelay,
                onClose = ::dismissActiveOverlay,
                onOpenBrickMode = ::openBrickMode
            )
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        activeView?.let { attachOverlay(it, params) }
    }

    private fun dismissActiveOverlay() {
        activeContentController?.dispose()
        activeContentController = null
        removeOverlay(activeView)
        activeView = null
        isActiveState = false
        showPassiveOverlay()
        updatePassiveText(getFormattedScreenTime())
    }

    private fun openBrickMode() {
        dismissActiveOverlay()
        startActivity(createTimeHudDestinationIntent(this, TimeHudDestination.BRICK_MODE))
    }

    private fun handleBlockingOverlayVisibility(visible: Boolean) {
        isBlockingOverlayVisible = visible
        if (visible) {
            removeOverlay(passiveView)
            passiveView = null
        } else if (!isActiveState) {
            showPassiveOverlay()
            updatePassiveText(getFormattedScreenTime())
        }
    }

    private fun removeOverlay(view: View?) {
        try {
            view?.let { windowManager.removeView(it) }
        } catch (_: Exception) {
            // View may already be removed.
        }
    }

    private fun updateScreenTime(totalMs: Long) {
        latestTimeText = ScreenTimeDisplay.format(totalMs)
        if (!isActiveState) updatePassiveText(latestTimeText)
        val currentBucket = totalMs / FIVE_MINUTES_MS
        if (currentBucket > 0 && currentBucket != lastTriggeredBucket) {
            lastTriggeredBucket = currentBucket
            showActiveOverlay(latestTimeText, ActiveOverlayTrigger.FIVE_MINUTE_BUCKET)
        }
    }

    private fun attachOverlay(view: View, params: WindowManager.LayoutParams) {
        try {
            windowManager.addView(view, params)
        } catch (_: RuntimeException) {
            stopAfterOverlayFailure()
        }
    }

    private fun stopAfterOverlayFailure() {
        overlayFailed = true
        activeContentController?.dispose()
        activeContentController = null
        removeOverlay(passiveView)
        removeOverlay(activeView)
        passiveView = null
        activeView = null
        isActiveState = false
        StartupPreferences.markHudStopped(this)
        OverlayServiceStateStore.markStopped()
        Log.w(TAG, "HUD stopped because its overlay window is unavailable")
        stopSelf()
    }

    private fun updatePassiveText(text: String) {
        passiveView?.let { updateBubbleText(it, text) }
    }

    private fun updateBubbleText(view: View, text: String) {
        view.findViewById<TextView>(R.id.tv_time)?.text = text
        view.contentDescription = getString(R.string.overlay_bubble_content_description, text)
    }

    private fun getFormattedScreenTime(): String = latestTimeText

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }
}
