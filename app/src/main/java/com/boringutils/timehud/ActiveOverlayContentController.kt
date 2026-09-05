package com.boringutils.timehud

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import java.util.Calendar

internal class ActiveOverlayContentController(
    private val context: Context,
    private val handler: Handler,
    private val rootView: View,
    timeText: String,
    requiresCloseDelay: Boolean,
    private val onClose: () -> Unit,
    private val onOpenBrickMode: () -> Unit
) {
    private var activeGoalConfig = GoalSettings.load(context)
    private var activeGoalMode = GoalMode.SHORT_TERM

    private val enableCloseRunnable = Runnable {
        rootView.findViewById<Button>(R.id.btn_close_active)?.let {
            configureCloseButton(it, enabled = true)
        }
    }

    init {
        rootView.findViewById<TextView>(R.id.tv_time)?.text = timeText
        updateAgenda(CalendarAgenda.loadTodayVisibleInstances(context))
        updateGoals()
        rootView.findViewById<Switch>(R.id.switch_goal_mode)
            ?.setOnCheckedChangeListener { _, checked ->
                activeGoalMode = if (checked) GoalMode.LONG_TERM else GoalMode.SHORT_TERM
                updateGoals()
            }
        rootView.findViewById<Button>(R.id.btn_close_active)?.apply {
            configureCloseButton(this, enabled = !requiresCloseDelay)
            setOnClickListener { onClose() }
        }
        rootView.findViewById<Button>(R.id.btn_open_brick_mode)
            ?.setOnClickListener { onOpenBrickMode() }
        if (requiresCloseDelay) {
            handler.postDelayed(enableCloseRunnable, ACTIVE_CLOSE_DELAY_MS)
        }
    }

    fun dispose() {
        handler.removeCallbacks(enableCloseRunnable)
        rootView.animate().cancel()
    }

    private fun configureCloseButton(button: Button, enabled: Boolean) {
        button.isEnabled = enabled
        button.text = context.getString(
            if (enabled) R.string.active_close else R.string.active_close_delayed
        )
        button.setTextColor(
            context.getColor(
                if (enabled) R.color.graphite_background else R.color.graphite_text_disabled
            )
        )
        button.backgroundTintList = ColorStateList.valueOf(
            context.getColor(
                if (enabled) R.color.graphite_text_primary else R.color.graphite_disabled_surface
            )
        )
    }

    private fun updateGoals() {
        val items = if (activeGoalMode == GoalMode.LONG_TERM) {
            activeGoalConfig.longTermItems
        } else {
            activeGoalConfig.shortTermItems
        }
        rootView.findViewById<TextView>(R.id.tv_goal_mode)?.text = if (
            activeGoalMode == GoalMode.LONG_TERM
        ) {
            context.getString(R.string.active_long_term_goals)
        } else {
            context.getString(R.string.active_short_term_goals)
        }
        val taskContainer = rootView.findViewById<LinearLayout>(R.id.layout_goal_tasks) ?: return
        taskContainer.removeAllViews()

        if (items.isEmpty()) {
            taskContainer.addView(createEmptyGoalsView())
            return
        }

        items.forEach { item ->
            taskContainer.addView(createGoalRow(item))
        }
    }

    private fun createGoalRow(goalText: String): View {
        val completed = GoalCompletionStore.isCompleted(context, activeGoalMode, goalText)
        val rowKey = GoalCompletionKeys.taskKey(activeGoalMode, goalText)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            background = makeRoundedBackground(
                context.getColor(
                    if (completed) {
                        R.color.graphite_surface_elevated
                    } else {
                        R.color.graphite_surface
                    }
                ),
                8
            )
            setPadding(dp(8), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }

        val checkbox = CheckBox(context).apply {
            tag = "check:$rowKey"
            isChecked = completed
            isClickable = false
            isFocusable = false
            buttonTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(
                    context.getColor(R.color.graphite_text_primary),
                    context.getColor(R.color.graphite_text_secondary)
                )
            )
        }
        row.addView(
            checkbox,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val taskText = TextView(context).apply {
            text = goalText
            textSize = 17f
            setTextColor(
                context.getColor(
                    if (completed) {
                        R.color.graphite_text_secondary
                    } else {
                        R.color.graphite_text_primary
                    }
                )
            )
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
            if (GoalCompletionStore.isCompleted(context, activeGoalMode, goalText)) {
                GoalCompletionStore.clearCompleted(context, activeGoalMode, goalText)
                updateGoals()
            } else {
                showCompletionModal(ActiveGoalTask(activeGoalMode, goalText))
            }
        }
        return row
    }

    private fun createEmptyGoalsView(): View = TextView(context).apply {
        text = context.getString(R.string.active_no_goals)
        textSize = 18f
        setTextColor(context.getColor(R.color.graphite_text_primary))
        setPadding(dp(6), dp(6), dp(6), dp(6))
    }

    private fun showCompletionModal(task: ActiveGoalTask) {
        val modal = rootView.findViewById<FrameLayout>(R.id.layout_completion_modal) ?: return
        modal.getChildAt(0)?.setOnClickListener { }
        modal.visibility = View.VISIBLE
        modal.alpha = 0f
        modal.animate().alpha(1f).setDuration(120L).start()
        modal.setOnClickListener { hideCompletionModal() }

        modal.findViewById<TextView>(R.id.tv_completion_task)?.text = task.text
        modal.findViewById<Button>(R.id.btn_done_today)?.setOnClickListener {
            hideCompletionModal()
            GoalCompletionStore.markCompleted(context, task.mode, task.text)
            updateGoals()
            runCompletionCelebration(GoalCompletionKeys.taskKey(task.mode, task.text))
        }
        modal.findViewById<Button>(R.id.btn_remove_goal)?.setOnClickListener {
            hideCompletionModal()
            GoalCompletionStore.clearCompleted(context, task.mode, task.text)
            activeGoalConfig = GoalSettings.removeGoal(context, task.mode, task.text)
            updateGoals()
        }
        modal.findViewById<Button>(R.id.btn_cancel_completion)?.setOnClickListener {
            hideCompletionModal()
        }
    }

    private fun hideCompletionModal() {
        rootView.findViewById<FrameLayout>(R.id.layout_completion_modal)?.apply {
            animate().cancel()
            visibility = View.GONE
            alpha = 1f
        }
    }

    private fun runCompletionCelebration(rowKey: String) {
        pulseCompletedCheck(rowKey)
        val celebration = rootView.findViewById<FrameLayout>(R.id.layout_celebration) ?: return
        val dots = rootView.findViewById<FrameLayout>(R.id.layout_celebration_dots) ?: return
        val banner = rootView.findViewById<TextView>(R.id.tv_celebration_banner) ?: return

        celebration.visibility = View.VISIBLE
        celebration.alpha = 1f
        dots.removeAllViews()
        startDotBurst(dots)

        banner.animate().cancel()
        banner.visibility = View.VISIBLE
        banner.alpha = 0f
        banner.translationY = -dp(12).toFloat()
        banner.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(180L)
            .withEndAction {
                banner.animate()
                    .alpha(0f)
                    .translationY(-dp(8).toFloat())
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

    private fun pulseCompletedCheck(rowKey: String) {
        val checkView = rootView.findViewWithTag<View>("check:$rowKey") ?: return
        checkView.animate().cancel()
        checkView.scaleX = 1f
        checkView.scaleY = 1f
        checkView.animate()
            .scaleX(1.28f)
            .scaleY(1.28f)
            .setDuration(140L)
            .withEndAction {
                checkView.animate().scaleX(1f).scaleY(1f).setDuration(180L).start()
            }
            .start()
    }

    private fun startDotBurst(dots: FrameLayout) {
        val goalPanel = rootView.findViewById<View>(R.id.layout_goal_tasks) ?: return
        dots.post {
            val rootLocation = IntArray(2)
            val goalLocation = IntArray(2)
            rootView.getLocationOnScreen(rootLocation)
            goalPanel.getLocationOnScreen(goalLocation)

            val centerX = goalLocation[0] - rootLocation[0] + goalPanel.width / 2
            val centerY = goalLocation[1] - rootLocation[1] +
                goalPanel.height.coerceAtMost(dp(220)) / 2
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
                context.getColor(R.color.graphite_text_primary),
                context.getColor(R.color.graphite_text_emphasis),
                context.getColor(R.color.graphite_text_secondary),
                context.getColor(R.color.graphite_text_disabled)
            )

            offsets.forEachIndexed { index, offset ->
                val dotSize = dp(if (index % 2 == 0) 8 else 6)
                val dot = View(context).apply {
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
                    .translationX(dp(offset.first).toFloat())
                    .translationY(dp(offset.second).toFloat())
                    .setDuration(850L)
                    .withStartAction { dot.alpha = 1f }
                    .withEndAction { (dot.parent as? ViewGroup)?.removeView(dot) }
                    .start()
            }
        }
    }

    private fun updateAgenda(items: List<TodayCalendarItem>) {
        val agendaLayout = rootView.findViewById<View>(R.id.layout_calendar_agenda)
        val agendaText = rootView.findViewById<TextView>(R.id.tv_calendar_agenda)
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
            agendaLines += context.getString(R.string.active_more_agenda_items, remainingCount)
        }
        agendaText?.text = agendaLines.joinToString(separator = "\n")
    }

    private fun makeRoundedBackground(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun makeOvalBackground(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private data class ActiveGoalTask(val mode: GoalMode, val text: String)

    private companion object {
        const val ACTIVE_CLOSE_DELAY_MS = 5_000L
    }
}

internal object ScreenTimeDisplay {
    fun current(context: Context): String = format(queryMs(context))

    fun format(ms: Long): String {
        val totalMinutes = ms / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return "${hours}h${minutes}m"
    }

    fun queryMs(
        context: Context,
        nowMs: Long = System.currentTimeMillis()
    ): Long {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE)
            as? UsageStatsManager ?: return 0L
        val powerManager = context.getSystemService(Context.POWER_SERVICE)
            as? android.os.PowerManager ?: return 0L

        val calendar = Calendar.getInstance().apply { timeInMillis = nowMs }
        if (calendar.get(Calendar.HOUR_OF_DAY) < 3) {
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }
        calendar.set(Calendar.HOUR_OF_DAY, 3)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        val startOfPeriod = calendar.timeInMillis
        val events = usageStatsManager.queryEvents(startOfPeriod, nowMs)
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
            } else if (event.eventType == 16 && lastInteractiveTime > 0) {
                totalScreenTime += event.timeStamp - lastInteractiveTime
                lastInteractiveTime = 0L
            }
        }

        if (lastInteractiveTime > 0) {
            totalScreenTime += nowMs - lastInteractiveTime
        } else if (firstEvent && powerManager.isInteractive) {
            totalScreenTime += nowMs - startOfPeriod
        }
        return totalScreenTime
    }
}
