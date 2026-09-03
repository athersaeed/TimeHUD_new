package com.boringutils.timehud.blocking

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import com.boringutils.timehud.ActiveOverlayContentController
import com.boringutils.timehud.ActiveOverlayTrigger
import com.boringutils.timehud.R
import com.boringutils.timehud.ScreenTimeDisplay
import com.boringutils.timehud.ui.usage.AppUsageLoadResult
import com.boringutils.timehud.ui.usage.AppUsageRepository
import com.boringutils.timehud.ui.usage.currentUsagePeriodStart
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class TimeHudAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val usageExecutor = Executors.newSingleThreadExecutor()
    private val usageSeedInProgress = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)
    private lateinit var overlayController: BlockingOverlayController

    private var focusedPackage: String? = null
    private var focusStartedElapsedMs = 0L
    private var focusStartedWallMs = 0L

    private val evaluateRunnable = Runnable { evaluateWindows() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlayController = BlockingOverlayController(
            service = this,
            onClose = ::returnHome
        )
        scheduleEvaluation(delayMs = 0L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        scheduleEvaluation()
    }

    override fun onInterrupt() {
        flushFocusedUsage()
        focusedPackage = null
        focusStartedElapsedMs = 0L
        focusStartedWallMs = 0L
        if (::overlayController.isInitialized) overlayController.clear()
    }

    override fun onDestroy() {
        destroyed.set(true)
        mainHandler.removeCallbacksAndMessages(null)
        flushFocusedUsage()
        if (::overlayController.isInitialized) overlayController.clear()
        usageExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun scheduleEvaluation(delayMs: Long = WINDOW_DEBOUNCE_MS) {
        if (destroyed.get()) return
        mainHandler.removeCallbacks(evaluateRunnable)
        mainHandler.postDelayed(evaluateRunnable, delayMs)
    }

    private fun evaluateWindows() {
        val rules = AppBlockSettings.loadRules(this).associateBy { it.packageName }
        if (rules.isEmpty()) {
            updateFocusedPackage(null)
            overlayController.clear()
            return
        }

        val windows = runCatching { windows.orEmpty() }.getOrDefault(emptyList())
        val geometries = windows.mapNotNull(::windowGeometry)
        val appWindows = windows.mapNotNull { window ->
            val geometry = windowGeometry(window) ?: return@mapNotNull null
            if (geometry.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@mapNotNull null
            val root = runCatching { window.root }.getOrNull() ?: return@mapNotNull null
            val packageName = root.packageName?.toString()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val rule = rules[packageName] ?: return@mapNotNull null
            val signals = AppWindowInspector.inspect(
                root = root,
                windowTitle = runCatching { window.title?.toString() }.getOrNull()
            ).copy(isCompactWindow = geometry.isCompact(resources.displayMetrics))
            ObservedAppWindow(
                geometry = geometry,
                packageName = packageName,
                surface = AppSurfaceClassifier.classify(packageName, signals),
                rule = rule
            )
        }

        val focusedPackageOnScreen = findFocusedPackage(windows)
        updateFocusedPackage(
            focusedPackageOnScreen?.takeIf { packageName ->
                rules[packageName]?.dailyLimitMinutes != null
            }
        )
        flushFocusedUsage()
        seedUninitializedUsage(rules.values)

        val nowElapsedMs = SystemClock.elapsedRealtime()
        val nowWallMs = System.currentTimeMillis()
        val targets = appWindows.mapNotNull { observed ->
            val usageMs = currentFocusedUsageMs(
                packageName = observed.packageName,
                nowElapsedMs = nowElapsedMs,
                nowWallMs = nowWallMs
            )
            when (
                val decision = AppBlockDecisionEngine.decide(
                    rule = observed.rule,
                    focusedUsageMs = usageMs,
                    surface = observed.surface
                )
            ) {
                BlockDecision.Allow -> null
                is BlockDecision.Block -> BlockTarget(
                    windowId = observed.geometry.id,
                    bounds = observed.geometry.bounds,
                    layer = observed.geometry.layer,
                    reason = decision.reason
                )
            }
        }

        overlayController.render(targets, geometries)
        scheduleNextLimitBoundary(appWindows, nowElapsedMs, nowWallMs)
    }

    private fun updateFocusedPackage(newPackage: String?) {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val nowWallMs = System.currentTimeMillis()
        if (focusedPackage == newPackage) return

        flushFocusedUsage(nowElapsedMs, nowWallMs)
        focusedPackage = newPackage
        focusStartedElapsedMs = nowElapsedMs
        focusStartedWallMs = nowWallMs
    }

    private fun flushFocusedUsage(
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
        nowWallMs: Long = System.currentTimeMillis()
    ) {
        val packageName = focusedPackage ?: return
        if (focusStartedElapsedMs <= 0L) return
        val elapsedDuration = (nowElapsedMs - focusStartedElapsedMs).coerceAtLeast(0L)
        val currentPeriodStart = currentUsagePeriodStart(nowWallMs)
        val durationInCurrentPeriod = if (focusStartedWallMs < currentPeriodStart) {
            (nowWallMs - currentPeriodStart).coerceAtLeast(0L)
        } else {
            elapsedDuration
        }
        FocusedAppUsageStore.addFocusedUsage(
            context = this,
            packageName = packageName,
            durationMs = min(elapsedDuration, durationInCurrentPeriod),
            nowMs = nowWallMs
        )
        focusStartedElapsedMs = nowElapsedMs
        focusStartedWallMs = nowWallMs
    }

    private fun currentFocusedUsageMs(
        packageName: String,
        nowElapsedMs: Long,
        nowWallMs: Long
    ): Long {
        val storedUsage = FocusedAppUsageStore.usageMs(this, packageName, nowWallMs)
        if (focusedPackage != packageName || focusStartedElapsedMs <= 0L) return storedUsage
        val currentPeriodStart = currentUsagePeriodStart(nowWallMs)
        val liveDuration = if (focusStartedWallMs < currentPeriodStart) {
            (nowWallMs - currentPeriodStart).coerceAtLeast(0L)
        } else {
            (nowElapsedMs - focusStartedElapsedMs).coerceAtLeast(0L)
        }
        return storedUsage + liveDuration
    }

    private fun seedUninitializedUsage(rules: Collection<AppBlockRule>) {
        val nowMs = System.currentTimeMillis()
        val packagesToSeed = rules.asSequence()
            .filter { it.dailyLimitMinutes != null }
            .map { it.packageName }
            .filterNot { FocusedAppUsageStore.isSeeded(this, it, nowMs) }
            .toSet()
        if (packagesToSeed.isEmpty() || !usageSeedInProgress.compareAndSet(false, true)) return

        usageExecutor.execute {
            val usageByPackage = when (val result = AppUsageRepository.load(this)) {
                is AppUsageLoadResult.Success -> result.entries.associate {
                    it.packageName to it.durationMs
                }
                AppUsageLoadResult.AccessDenied,
                AppUsageLoadResult.Unavailable -> emptyMap()
            }
            packagesToSeed.forEach { packageName ->
                FocusedAppUsageStore.seedIfNeeded(
                    context = this,
                    packageName = packageName,
                    usageMs = usageByPackage[packageName] ?: 0L,
                    nowMs = System.currentTimeMillis()
                )
            }
            usageSeedInProgress.set(false)
            if (!destroyed.get()) {
                mainHandler.post { scheduleEvaluation(delayMs = 0L) }
            }
        }
    }

    private fun scheduleNextLimitBoundary(
        appWindows: List<ObservedAppWindow>,
        nowElapsedMs: Long,
        nowWallMs: Long
    ) {
        val focused = appWindows.firstOrNull { it.packageName == focusedPackage } ?: return
        val limitMs = focused.rule.dailyLimitMinutes?.toLong()?.times(60_000L) ?: return
        val remainingMs = limitMs - currentFocusedUsageMs(
            focused.packageName,
            nowElapsedMs,
            nowWallMs
        )
        if (remainingMs > 0L) {
            scheduleEvaluation(delayMs = remainingMs.coerceAtMost(MAX_LIMIT_TIMER_DELAY_MS))
        }
    }

    private fun returnHome() {
        if (performGlobalAction(GLOBAL_ACTION_HOME)) {
            overlayController.clear()
            updateFocusedPackage(null)
            scheduleEvaluation(delayMs = HOME_NAVIGATION_DELAY_MS)
        }
    }

    private fun findFocusedPackage(windows: List<AccessibilityWindowInfo>): String? {
        val applicationWindows = windows.asSequence()
            .mapNotNull { window ->
                val geometry = windowGeometry(window) ?: return@mapNotNull null
                if (geometry.type != AccessibilityWindowInfo.TYPE_APPLICATION) {
                    return@mapNotNull null
                }
                val packageName = runCatching { window.root?.packageName?.toString() }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                geometry to packageName
            }
            .toList()
        return applicationWindows.firstOrNull { it.first.isFocused }?.second
            ?: applicationWindows.firstOrNull { it.first.isActive }?.second
    }

    private fun windowGeometry(window: AccessibilityWindowInfo): WindowGeometry? {
        if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return null
        val rect = Rect()
        window.getBoundsInScreen(rect)
        val bounds = ScreenRect(rect.left, rect.top, rect.right, rect.bottom)
        if (bounds.area <= 0L) return null
        return WindowGeometry(
            id = window.id,
            bounds = bounds,
            layer = window.layer,
            type = window.type,
            isFocused = window.isFocused,
            isActive = window.isActive
        )
    }

    private companion object {
        const val WINDOW_DEBOUNCE_MS = 120L
        const val HOME_NAVIGATION_DELAY_MS = 350L
        const val MAX_LIMIT_TIMER_DELAY_MS = 30_000L
    }
}

private data class WindowGeometry(
    val id: Int,
    val bounds: ScreenRect,
    val layer: Int,
    val type: Int,
    val isFocused: Boolean,
    val isActive: Boolean
) {
    fun isCompact(displayMetrics: android.util.DisplayMetrics): Boolean =
        bounds.width < displayMetrics.widthPixels * COMPACT_WINDOW_RATIO &&
            bounds.height < displayMetrics.heightPixels * COMPACT_WINDOW_RATIO

    private companion object {
        const val COMPACT_WINDOW_RATIO = 0.85f
    }
}

private data class ObservedAppWindow(
    val geometry: WindowGeometry,
    val packageName: String,
    val surface: AppSurface,
    val rule: AppBlockRule
)

private data class BlockTarget(
    val windowId: Int,
    val bounds: ScreenRect,
    val layer: Int,
    val reason: BlockReason
)

private object AppWindowInspector {
    private const val MAX_NODES = 600

    fun inspect(root: AccessibilityNodeInfo, windowTitle: String?): AppUiSignals {
        val labels = mutableSetOf<String>()
        val selectedLabels = mutableSetOf<String>()
        val focusedLabels = mutableSetOf<String>()
        val editableLabels = mutableSetOf<String>()
        val viewIds = mutableSetOf<String>()
        traverse(root) { node ->
            val nodeLabels = buildList<CharSequence> {
                node.contentDescription?.let(::add)
                node.text?.let(::add)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    node.hintText?.let(::add)
                }
            }
                .map { it.toString().normalizeSignal() }
                .filter { it.isNotEmpty() }
            labels += nodeLabels
            if (node.isSelected) selectedLabels += nodeLabels
            if (node.isFocused) focusedLabels += nodeLabels
            if (node.isEditable) editableLabels += nodeLabels
            node.viewIdResourceName?.normalizeSignal()?.takeIf { it.isNotEmpty() }?.let(viewIds::add)
        }
        return AppUiSignals(
            labels = labels,
            selectedLabels = selectedLabels,
            focusedLabels = focusedLabels,
            editableLabels = editableLabels,
            viewIds = viewIds,
            windowTitle = windowTitle.orEmpty().normalizeSignal()
        )
    }

    private fun traverse(root: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited += 1
            visit(node)
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }
    }

    private fun String.normalizeSignal(): String = trim().lowercase()
}

private class BlockingOverlayController(
    private val service: AccessibilityService,
    private val onClose: () -> Unit
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val attachedViews = mutableListOf<View>()
    private val contentControllers = mutableListOf<ActiveOverlayContentController>()
    private var lastSignature: String? = null

    fun render(targets: List<BlockTarget>, windows: List<WindowGeometry>) {
        val pieces = targets.flatMap { target ->
            val occluders = windows.asSequence()
                .filter { it.layer > target.layer }
                .map { it.bounds }
                .toList()
            val visibleRects = VisibleRegionCalculator.calculate(target.bounds, occluders)
                .filter { it.width >= MIN_OVERLAY_EDGE_PX && it.height >= MIN_OVERLAY_EDGE_PX }
                .sortedByDescending { it.area }
            visibleRects.mapIndexed { index, rect -> OverlayPiece(target, rect, showControls = index == 0) }
        }
        val signature = pieces.joinToString("|") { piece ->
            "${piece.target.windowId}:${piece.target.reason}:${piece.rect}:${piece.showControls}"
        }
        if (signature == lastSignature) return

        clear()
        lastSignature = signature
        val allPiecesAttached = pieces.map(::attachPiece).all { it }
        if (!allPiecesAttached) {
            lastSignature = null
        }
    }

    fun clear() {
        contentControllers.forEach(ActiveOverlayContentController::dispose)
        contentControllers.clear()
        attachedViews.forEach { view ->
            runCatching { windowManager.removeViewImmediate(view) }
        }
        attachedViews.clear()
        lastSignature = null
    }

    private fun attachPiece(piece: OverlayPiece): Boolean {
        val view = createPieceView(piece)
        val params = WindowManager.LayoutParams(
            piece.rect.width,
            piece.rect.height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = piece.rect.left
            y = piece.rect.top
        }
        return runCatching {
            windowManager.addView(view, params)
            attachedViews += view
        }.isSuccess
    }

    @SuppressLint("InflateParams")
    private fun createPieceView(piece: OverlayPiece): View {
        if (piece.showControls && piece.rect.width >= MIN_CARD_WIDTH_PX &&
            piece.rect.height >= MIN_CARD_HEIGHT_PX
        ) {
            return LayoutInflater.from(service).inflate(R.layout.overlay_active, null).apply {
                contentDescription = service.getString(R.string.blocking_overlay_content_description)
                contentControllers += ActiveOverlayContentController(
                    context = service,
                    handler = Handler(Looper.getMainLooper()),
                    rootView = this,
                    timeText = ScreenTimeDisplay.current(service),
                    requiresCloseDelay = ActiveOverlayTrigger.APP_BLOCK.requiresCloseDelay,
                    onClose = onClose
                )
            }
        }

        return FrameLayout(service).apply {
            setBackgroundColor(Color.argb(238, 13, 13, 26))
            contentDescription = service.getString(R.string.blocking_overlay_content_description)
            isClickable = true
            setOnTouchListener { view, event ->
                if (event.action == MotionEvent.ACTION_UP) view.performClick()
                true
            }
        }
    }

    private data class OverlayPiece(
        val target: BlockTarget,
        val rect: ScreenRect,
        val showControls: Boolean
    )

    private companion object {
        const val MIN_OVERLAY_EDGE_PX = 2
        const val MIN_CARD_WIDTH_PX = 280
        const val MIN_CARD_HEIGHT_PX = 220
    }
}
