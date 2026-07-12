package com.boringutils.timehud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.util.Calendar

class OverlayService : Service() {

    companion object {
        private const val TAG = "TimeHUD"
        private const val CHANNEL_ID = "timehud_overlay"
        private const val NOTIFICATION_ID = 1
        private const val TICK_INTERVAL_MS = 10_000L
        private const val ACTIVE_CLOSE_DELAY_MS = 5_000L
        private const val FIVE_MINUTES_MS = 5 * 60 * 1_000L
    }

    private lateinit var windowManager: WindowManager
    private lateinit var handler: Handler

    private var passiveView: View? = null
    private var activeView: View? = null
    private var isActiveState = false
    private var lastTriggeredBucket: Long = -1L
    private var activeGoalConfig: GoalConfiguration? = null
    private var activeGoalMode = GoalMode.SHORT_TERM

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        handler = Handler(Looper.getMainLooper())

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        OverlayServiceStateStore.markRunning()

        showPassiveOverlay()
        handler.post(tickRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        StartupPreferences.markHudRunning(this)
        OverlayServiceStateStore.markRunning()
        refreshNotification(getString(R.string.notif_text))
        Log.d(TAG, "Overlay service started")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
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

    private fun makeLayoutParams(gravity: Int): WindowManager.LayoutParams {
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            x = 0
            y = 0
        }
    }

    private fun showPassiveOverlay() {
        if (passiveView != null) return
        val inflater = LayoutInflater.from(this)
        passiveView = inflater.inflate(R.layout.overlay_passive, null)
        passiveView?.findViewById<TextView>(R.id.tv_time)?.text = getFormattedScreenTime()

        val params = makeLayoutParams(Gravity.TOP or Gravity.END)
        params.x = dpToPx(8)
        windowManager.addView(passiveView, params)
    }

    private fun showActiveOverlay(timeText: String) {
        if (isActiveState) return
        isActiveState = true

        removeOverlay(passiveView)
        passiveView = null

        val inflater = LayoutInflater.from(this)
        activeView = inflater.inflate(R.layout.overlay_active, null)
        activeView?.findViewById<TextView>(R.id.tv_time)?.text = timeText

        activeGoalConfig = GoalSettings.load(this)
        activeGoalMode = GoalMode.SHORT_TERM
        activeView?.let { view ->
            updateActiveAgenda(view, CalendarAgenda.loadTodayVisibleInstances(this))
            updateActiveGoals(view)
            view.findViewById<Switch>(R.id.switch_goal_mode)?.setOnCheckedChangeListener { _, checked ->
                activeGoalMode = if (checked) GoalMode.LONG_TERM else GoalMode.SHORT_TERM
                updateActiveGoals(view)
            }
            view.findViewById<Button>(R.id.btn_close_active)?.apply {
                isEnabled = false
                text = "Close in 5s"
                setTextColor(0xFFAAAAAA.toInt())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    backgroundTintList = ColorStateList.valueOf(0xFF44444F.toInt())
                }
                setOnClickListener {
                    dismissActiveOverlay()
                }
            }
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
        windowManager.addView(activeView, params)

        handler.postDelayed(enableActiveCloseRunnable, ACTIVE_CLOSE_DELAY_MS)
    }

    private val enableActiveCloseRunnable = Runnable {
        activeView?.findViewById<Button>(R.id.btn_close_active)?.apply {
            isEnabled = true
            text = "Close"
            setTextColor(0xFFFFFFFF.toInt())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                backgroundTintList = ColorStateList.valueOf(0xFF4488FF.toInt())
            }
        }
    }

    private fun dismissActiveOverlay() {
        handler.removeCallbacks(enableActiveCloseRunnable)
        removeOverlay(activeView)
        activeView = null
        isActiveState = false
        activeGoalConfig = null
        activeGoalMode = GoalMode.SHORT_TERM
        showPassiveOverlay()
        updatePassiveText(getFormattedScreenTime())
    }

    private fun updateActiveGoals(view: View) {
        val goalConfig = activeGoalConfig ?: GoalSettings.load(this).also {
            activeGoalConfig = it
        }
        val items = if (activeGoalMode == GoalMode.LONG_TERM) {
            goalConfig.longTermItems
        } else {
            goalConfig.shortTermItems
        }
        view.findViewById<TextView>(R.id.tv_goal_mode)?.text = if (activeGoalMode == GoalMode.LONG_TERM) {
            "Long term goals"
        } else {
            "Short term goals"
        }
        val taskContainer = view.findViewById<LinearLayout>(R.id.layout_goal_tasks) ?: return
        taskContainer.removeAllViews()

        if (items.isEmpty()) {
            taskContainer.addView(createEmptyGoalsView())
            return
        }

        items.forEach { item ->
            taskContainer.addView(createGoalRow(view, item))
        }
    }

    private fun createGoalRow(rootView: View, goalText: String): View {
        val completed = GoalCompletionStore.isCompleted(this, activeGoalMode, goalText)
        val rowKey = GoalCompletionKeys.taskKey(activeGoalMode, goalText)
        val row = LinearLayout(this).apply {
            tag = rowKey
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            background = makeRoundedBackground(
                if (completed) Color.rgb(26, 46, 34) else Color.rgb(17, 17, 32),
                8
            )
            setPadding(dpToPx(8), dpToPx(8), dpToPx(12), dpToPx(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(8)
            }
        }

        val checkbox = CheckBox(this).apply {
            tag = "check:$rowKey"
            isChecked = completed
            isClickable = false
            isFocusable = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(Color.rgb(105, 240, 174), Color.rgb(154, 160, 184))
                )
            }
        }
        row.addView(
            checkbox,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val taskText = TextView(this).apply {
            text = goalText
            textSize = 17f
            setTextColor(if (completed) Color.rgb(154, 190, 166) else Color.rgb(236, 238, 255))
            if (completed) {
                paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                alpha = 0.78f
            }
        }
        row.addView(
            taskText,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        row.setOnClickListener {
            if (GoalCompletionStore.isCompleted(this, activeGoalMode, goalText)) {
                GoalCompletionStore.clearCompleted(this, activeGoalMode, goalText)
                updateActiveGoals(rootView)
            } else {
                showCompletionModal(rootView, ActiveGoalTask(activeGoalMode, goalText))
            }
        }

        return row
    }

    private fun createEmptyGoalsView(): View =
        TextView(this).apply {
            text = "- No goals saved yet"
            textSize = 18f
            setTextColor(Color.rgb(236, 238, 255))
            setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6))
        }

    private fun showCompletionModal(rootView: View, task: ActiveGoalTask) {
        val modal = rootView.findViewById<FrameLayout>(R.id.layout_completion_modal) ?: return
        val panel = modal.getChildAt(0)
        panel?.setOnClickListener { }
        modal.visibility = View.VISIBLE
        modal.alpha = 0f
        modal.animate().alpha(1f).setDuration(120L).start()
        modal.setOnClickListener {
            hideCompletionModal(rootView)
        }

        modal.findViewById<TextView>(R.id.tv_completion_task)?.text = task.text
        modal.findViewById<Button>(R.id.btn_done_today)?.setOnClickListener {
            hideCompletionModal(rootView)
            GoalCompletionStore.markCompleted(this, task.mode, task.text)
            updateActiveGoals(rootView)
            runCompletionCelebration(rootView, GoalCompletionKeys.taskKey(task.mode, task.text))
        }
        modal.findViewById<Button>(R.id.btn_remove_goal)?.setOnClickListener {
            hideCompletionModal(rootView)
            GoalCompletionStore.clearCompleted(this, task.mode, task.text)
            activeGoalConfig = GoalSettings.removeGoal(this, task.mode, task.text)
            updateActiveGoals(rootView)
        }
        modal.findViewById<Button>(R.id.btn_cancel_completion)?.setOnClickListener {
            hideCompletionModal(rootView)
        }
    }

    private fun hideCompletionModal(rootView: View) {
        rootView.findViewById<FrameLayout>(R.id.layout_completion_modal)?.apply {
            animate().cancel()
            visibility = View.GONE
            alpha = 1f
        }
    }

    private fun runCompletionCelebration(rootView: View, rowKey: String) {
        pulseCompletedCheck(rootView, rowKey)

        val celebration = rootView.findViewById<FrameLayout>(R.id.layout_celebration) ?: return
        val dots = rootView.findViewById<FrameLayout>(R.id.layout_celebration_dots) ?: return
        val banner = rootView.findViewById<TextView>(R.id.tv_celebration_banner) ?: return

        celebration.visibility = View.VISIBLE
        celebration.alpha = 1f
        dots.removeAllViews()
        startDotBurst(rootView, dots)

        banner.animate().cancel()
        banner.visibility = View.VISIBLE
        banner.alpha = 0f
        banner.translationY = -dpToPx(12).toFloat()
        banner.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(180L)
            .withEndAction {
                banner.animate()
                    .alpha(0f)
                    .translationY(-dpToPx(8).toFloat())
                    .setStartDelay(850L)
                    .setDuration(260L)
                    .withEndAction {
                        banner.visibility = View.GONE
                        celebration.visibility = View.GONE
                        dots.removeAllViews()
                    }
                    .start()
            }
            .start()
    }

    private fun pulseCompletedCheck(rootView: View, rowKey: String) {
        val checkView = rootView.findViewWithTag<View>("check:$rowKey") ?: return
        checkView.animate().cancel()
        checkView.scaleX = 1f
        checkView.scaleY = 1f
        checkView.animate()
            .scaleX(1.28f)
            .scaleY(1.28f)
            .setDuration(140L)
            .withEndAction {
                checkView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180L)
                    .start()
            }
            .start()
    }

    private fun startDotBurst(rootView: View, dots: FrameLayout) {
        val goalPanel = rootView.findViewById<View>(R.id.layout_goal_tasks) ?: return
        dots.post {
            val rootLocation = IntArray(2)
            val goalLocation = IntArray(2)
            rootView.getLocationOnScreen(rootLocation)
            goalPanel.getLocationOnScreen(goalLocation)

            val centerX = goalLocation[0] - rootLocation[0] + goalPanel.width / 2
            val centerY = goalLocation[1] - rootLocation[1] + goalPanel.height.coerceAtMost(dpToPx(220)) / 2
            val offsets = listOf(
                -120 to -58,
                -74 to -88,
                -28 to -48,
                24 to -92,
                78 to -54,
                128 to -84,
                -96 to 4,
                96 to 8
            )
            val colors = intArrayOf(
                Color.rgb(105, 240, 174),
                Color.rgb(154, 183, 255),
                Color.rgb(255, 179, 102),
                Color.rgb(255, 107, 107)
            )

            offsets.forEachIndexed { index, offset ->
                val dotSize = dpToPx(if (index % 2 == 0) 8 else 6)
                val dot = View(this).apply {
                    alpha = 0f
                    scaleX = 0.5f
                    scaleY = 0.5f
                    background = makeOvalBackground(colors[index % colors.size])
                }
                dots.addView(
                    dot,
                    FrameLayout.LayoutParams(dotSize, dotSize).apply {
                        leftMargin = centerX - dotSize / 2
                        topMargin = centerY - dotSize / 2
                    }
                )
                dot.animate()
                    .alpha(0f)
                    .scaleX(1.25f)
                    .scaleY(1.25f)
                    .translationX(dpToPx(offset.first).toFloat())
                    .translationY(dpToPx(offset.second).toFloat())
                    .setDuration(850L)
                    .withStartAction {
                        dot.alpha = 1f
                    }
                    .withEndAction {
                        (dot.parent as? ViewGroup)?.removeView(dot)
                    }
                    .start()
            }
        }
    }

    private fun updateActiveAgenda(view: View, items: List<TodayCalendarItem>) {
        val agendaLayout = view.findViewById<View>(R.id.layout_calendar_agenda)
        val agendaText = view.findViewById<TextView>(R.id.tv_calendar_agenda)
        if (items.isEmpty()) {
            agendaLayout?.visibility = View.GONE
            agendaText?.text = ""
            return
        }

        agendaLayout?.visibility = View.VISIBLE
        val visibleItems = items.take(5)
        val remainingCount = items.size - visibleItems.size
        val agendaLines = visibleItems.map { "- ${CalendarAgenda.formatForGoals(it)}" }
            .toMutableList()
        if (remainingCount > 0) {
            agendaLines += "- $remainingCount more today"
        }
        agendaText?.text = agendaLines.joinToString(separator = "\n")
    }

    private fun removeOverlay(view: View?) {
        try {
            view?.let { windowManager.removeView(it) }
        } catch (_: Exception) {
            // View may already be removed.
        }
    }

    private val tickRunnable: Runnable = object : Runnable {
        override fun run() {
            val totalMs = queryScreenTimeMs()
            val text = formatTime(totalMs)

            if (!isActiveState) {
                updatePassiveText(text)
            }

            val currentBucket = totalMs / FIVE_MINUTES_MS
            if (currentBucket > 0 && currentBucket != lastTriggeredBucket) {
                lastTriggeredBucket = currentBucket
                showActiveOverlay(text)
            }

            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    private fun updatePassiveText(text: String) {
        passiveView?.findViewById<TextView>(R.id.tv_time)?.text = text
    }

    private fun queryScreenTimeMs(): Long {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return 0L
        val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            ?: return 0L

        val cal = Calendar.getInstance()
        if (cal.get(Calendar.HOUR_OF_DAY) < 3) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        cal.set(Calendar.HOUR_OF_DAY, 3)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        val startOfPeriod = cal.timeInMillis
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(startOfPeriod, now)
        var totalScreenTime = 0L
        var lastInteractiveTime = 0L
        var firstEvent = true

        val event = android.app.usage.UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType != 15 && event.eventType != 16) continue

            if (firstEvent) {
                if (event.eventType == 16) {
                    totalScreenTime += event.timeStamp - startOfPeriod
                }
                firstEvent = false
            }

            if (event.eventType == 15) {
                lastInteractiveTime = event.timeStamp
            } else if (event.eventType == 16) {
                if (lastInteractiveTime > 0) {
                    totalScreenTime += event.timeStamp - lastInteractiveTime
                    lastInteractiveTime = 0L
                }
            }
        }

        if (lastInteractiveTime > 0) {
            totalScreenTime += now - lastInteractiveTime
        } else if (firstEvent && pm.isInteractive) {
            totalScreenTime += now - startOfPeriod
        }

        return totalScreenTime
    }

    private fun formatTime(ms: Long): String {
        val totalMinutes = ms / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return "${hours}h${minutes}m"
    }

    private fun getFormattedScreenTime(): String = formatTime(queryScreenTimeMs())

    private fun makeRoundedBackground(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dpToPx(radiusDp).toFloat()
        }

    private fun makeOvalBackground(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }

    private data class ActiveGoalTask(
        val mode: GoalMode,
        val text: String
    )
}
