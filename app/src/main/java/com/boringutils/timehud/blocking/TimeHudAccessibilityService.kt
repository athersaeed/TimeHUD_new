package com.boringutils.timehud.blocking

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.res.ColorStateList
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
import android.widget.Button
import android.widget.FrameLayout
import com.boringutils.timehud.AppLimitGraceControls
import com.boringutils.timehud.ActiveOverlayContentController
import com.boringutils.timehud.ActiveOverlayTrigger
import com.boringutils.timehud.AccessibilityForegroundAppStateStore
import com.boringutils.timehud.BlockingOverlayStateStore
import com.boringutils.timehud.R
import com.boringutils.timehud.createTimeHudDestinationIntent
import com.boringutils.timehud.ui.navigation.TimeHudDestination
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
    private val brickCatalogLoadInProgress = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)
    private lateinit var overlayController: BlockingOverlayController

    @Volatile
    private var brickModeCatalog = BrickModeCatalog()

    @Volatile
    private var brickCatalogLoadedAtElapsedMs = 0L

    private var focusedPackage: String? = null
    private var focusStartedElapsedMs = 0L
    private var focusStartedWallMs = 0L

    private val evaluateRunnable = Runnable { evaluateWindows() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityForegroundAppStateStore.markConnected()
        overlayController = BlockingOverlayController(
            service = this,
            onClose = ::returnHome,
            onOpenBrickMode = ::openBrickMode,
            onStartNormalGrace = ::startNormalGrace,
            onStartEmergencyGrace = ::startEmergencyGrace,
            onEndGrace = ::endGrace,
            onGraceExpired = { scheduleEvaluation(delayMs = 0L) }
        )
        refreshBrickModeCatalog(force = true)
        scheduleEvaluation(delayMs = 0L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            refreshBrickModeCatalog()
        }
        scheduleEvaluation()
    }

    override fun onInterrupt() {
        AccessibilityForegroundAppStateStore.updatePackage(null)
        flushFocusedUsage()
        focusedPackage = null
        focusStartedElapsedMs = 0L
        focusStartedWallMs = 0L
        if (::overlayController.isInitialized) overlayController.clear()
    }

    override fun onDestroy() {
        AccessibilityForegroundAppStateStore.markDisconnected()
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

    private fun refreshBrickModeCatalog(force: Boolean = false) {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val catalogIsFresh = brickCatalogLoadedAtElapsedMs > 0L &&
            nowElapsedMs - brickCatalogLoadedAtElapsedMs < BRICK_CATALOG_REFRESH_MS
        if ((!force && catalogIsFresh) || !brickCatalogLoadInProgress.compareAndSet(false, true)) {
            return
        }
        usageExecutor.execute {
            val loadedCatalog = runCatching { BrickModeCatalogLoader.load(this) }
                .getOrDefault(BrickModeCatalog())
            brickModeCatalog = loadedCatalog
            brickCatalogLoadedAtElapsedMs = SystemClock.elapsedRealtime()
            brickCatalogLoadInProgress.set(false)
            if (!destroyed.get()) {
                mainHandler.post { scheduleEvaluation(delayMs = 0L) }
            }
        }
    }

    private fun evaluateWindows() {
        val windows = runCatching { windows.orEmpty() }.getOrDefault(emptyList())
        AccessibilityForegroundAppStateStore.updatePackage(findFocusedPackage(windows))
        val nowWallMs = System.currentTimeMillis()
        val graceState = AppLimitGraceStore.load(this, nowWallMs)
        val graceStatus = AppLimitGracePolicy.status(graceState, nowWallMs)
        val rules = AppBlockSettings.loadRules(this).associateBy { it.packageName }
        val brickModeConfig = BrickModeSettings.load(this, nowWallMs)
        val brickModeSchedules = BrickModeSettings.loadSchedules(this)
        val scheduledRestrictionMode = BrickModeSchedulePolicy.activeMode(
            brickModeSchedules,
            nowWallMs
        )
        val currentBrickModeCatalog = brickModeCatalog
        if (
            rules.isEmpty() &&
            !brickModeConfig.enabled &&
            brickModeSchedules.none(BrickModeSchedule::enabled)
        ) {
            updateFocusedPackage(null)
            overlayController.clear()
            return
        }

        val geometries = windows.mapNotNull(::windowGeometry)
        val appWindows = windows.mapNotNull { window ->
            val geometry = windowGeometry(window) ?: return@mapNotNull null
            if (geometry.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@mapNotNull null
            val root = runCatching { window.root }.getOrNull() ?: return@mapNotNull null
            val packageName = root.packageName?.toString()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val rule = rules[packageName]
            val brickModeDecision = BrickModeDecisionEngine.decide(
                config = brickModeConfig,
                packageName = packageName,
                catalog = currentBrickModeCatalog,
                scheduledMode = scheduledRestrictionMode,
                nowMs = nowWallMs
            )
            if (brickModeDecision == BlockDecision.Allow && rule == null) {
                return@mapNotNull null
            }
            val surface = if (rule != null && brickModeDecision == BlockDecision.Allow) {
                val signals = AppWindowInspector.inspect(
                    root = root,
                    windowTitle = runCatching { window.title?.toString() }.getOrNull()
                ).copy(isCompactWindow = geometry.isCompact(resources.displayMetrics))
                AppSurfaceClassifier.classify(packageName, signals)
            } else {
                AppSurface.UNKNOWN
            }
            ObservedAppWindow(
                geometry = geometry,
                packageName = packageName,
                surface = surface,
                rule = rule,
                brickModeDecision = brickModeDecision
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
        val targets = appWindows.mapNotNull { observed ->
            val focusedUsageMs = if (
                observed.brickModeDecision == BlockDecision.Allow && observed.rule != null
            ) {
                currentFocusedUsageMs(
                    packageName = observed.packageName,
                    nowElapsedMs = nowElapsedMs,
                    nowWallMs = nowWallMs
                )
            } else {
                0L
            }
            val appControlDecision = AppControlDecisionEngine.decide(
                brickModeDecision = observed.brickModeDecision,
                appBlockRule = observed.rule,
                focusedUsageMs = focusedUsageMs,
                surface = observed.surface
            )
            val decision = AppLimitGracePolicy.applyTo(appControlDecision, graceStatus)
            when (decision) {
                BlockDecision.Allow -> null
                is BlockDecision.Block -> BlockTarget(
                    windowId = observed.geometry.id,
                    bounds = observed.geometry.bounds,
                    layer = observed.geometry.layer,
                    reason = decision.reason
                )
            }
        }

        overlayController.render(targets, geometries, graceStatus)
        scheduleNextBoundary(
            appWindows = appWindows,
            brickModeConfig = brickModeConfig,
            brickModeSchedules = brickModeSchedules,
            graceStatus = graceStatus,
            nowElapsedMs = nowElapsedMs,
            nowWallMs = nowWallMs
        )
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

    private fun scheduleNextBoundary(
        appWindows: List<ObservedAppWindow>,
        brickModeConfig: BrickModeConfig,
        brickModeSchedules: List<BrickModeSchedule>,
        graceStatus: AppLimitGraceStatus,
        nowElapsedMs: Long,
        nowWallMs: Long
    ) {
        val appLimitRemainingMs = appWindows
            .firstOrNull { it.packageName == focusedPackage }
            ?.let { focused ->
                val limitMs = focused.rule?.dailyLimitMinutes?.toLong()?.times(60_000L)
                    ?: return@let null
                limitMs - currentFocusedUsageMs(
                    focused.packageName,
                    nowElapsedMs,
                    nowWallMs
                )
            }
            ?.takeIf { it > 0L }
        val brickModeRemainingMs = brickModeConfig.remainingMs(nowWallMs)
            ?.takeIf { it > 0L }
        val scheduledBoundaryRemainingMs = BrickModeSchedulePolicy.nextBoundaryEpochMs(
            schedules = brickModeSchedules,
            nowMs = nowWallMs
        )?.let { boundaryMs -> boundaryMs - nowWallMs }
            ?.takeIf { it > 0L }
        val graceBoundaryRemainingMs = when (graceStatus) {
            is AppLimitGraceStatus.Active -> graceStatus.endMs - nowWallMs
            is AppLimitGraceStatus.Cooldown -> graceStatus.endMs - nowWallMs
            AppLimitGraceStatus.NormalAvailable -> null
        }?.takeIf { it > 0L }
        val nextBoundaryMs = listOfNotNull(
            appLimitRemainingMs?.coerceAtMost(MAX_LIMIT_TIMER_DELAY_MS),
            brickModeRemainingMs,
            scheduledBoundaryRemainingMs,
            graceBoundaryRemainingMs
        ).minOrNull() ?: return
        scheduleEvaluation(delayMs = nextBoundaryMs)
    }

    private fun startNormalGrace(durationMinutes: Int) {
        runCatching {
            AppLimitGraceStore.startNormal(this, durationMinutes, System.currentTimeMillis())
        }.onSuccess {
            scheduleEvaluation(delayMs = 0L)
        }
    }

    private fun startEmergencyGrace() {
        runCatching {
            AppLimitGraceStore.startEmergency(this, System.currentTimeMillis())
        }.onSuccess {
            scheduleEvaluation(delayMs = 0L)
        }
    }

    private fun endGrace() {
        AppLimitGraceStore.endActiveGrace(this, System.currentTimeMillis())
        scheduleEvaluation(delayMs = 0L)
    }

    private fun returnHome() {
        if (performGlobalAction(GLOBAL_ACTION_HOME)) {
            overlayController.clear()
            updateFocusedPackage(null)
            scheduleEvaluation(delayMs = HOME_NAVIGATION_DELAY_MS)
        }
    }

    private fun openBrickMode() {
        overlayController.clear()
        updateFocusedPackage(null)
        startActivity(createTimeHudDestinationIntent(this, TimeHudDestination.BRICK_MODE))
        scheduleEvaluation(delayMs = HOME_NAVIGATION_DELAY_MS)
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
            isActive = window.isActive,
            isVolumePanel = isVolumePanel(window)
        )
    }

    private fun isVolumePanel(window: AccessibilityWindowInfo): Boolean {
        if (window.type != AccessibilityWindowInfo.TYPE_SYSTEM) return false
        val root = runCatching { window.root }.getOrNull() ?: return false
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        nodes.add(root)
        var visited = 0
        while (nodes.isNotEmpty() && visited < MAX_VOLUME_WINDOW_NODES) {
            val node = nodes.removeFirst()
            visited++
            val viewId = node.viewIdResourceName.orEmpty()
            if (BlockingOccluderPolicy.isVolumeControlId(viewId)) {
                return true
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(nodes::addLast)
            }
        }
        return false
    }

    private companion object {
        const val WINDOW_DEBOUNCE_MS = 120L
        const val HOME_NAVIGATION_DELAY_MS = 350L
        const val MAX_LIMIT_TIMER_DELAY_MS = 30_000L
        const val BRICK_CATALOG_REFRESH_MS = 60_000L
        const val MAX_VOLUME_WINDOW_NODES = 80
    }
}

private data class WindowGeometry(
    val id: Int,
    val bounds: ScreenRect,
    val layer: Int,
    val type: Int,
    val isFocused: Boolean,
    val isActive: Boolean,
    val isVolumePanel: Boolean
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
    val rule: AppBlockRule?,
    val brickModeDecision: BlockDecision
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
    private val onClose: () -> Unit,
    private val onOpenBrickMode: () -> Unit,
    private val onStartNormalGrace: (Int) -> Unit,
    private val onStartEmergencyGrace: () -> Unit,
    private val onEndGrace: () -> Unit,
    private val onGraceExpired: () -> Unit
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val blockingViews = mutableListOf<View>()
    private val contentControllers = mutableListOf<ActiveOverlayContentController>()
    private var graceCountdownButton: Button? = null
    private var graceCountdownEndMs = 0L
    private var lastSignature: String? = null

    private val updateGraceCountdownRunnable = object : Runnable {
        override fun run() {
            val button = graceCountdownButton ?: return
            val remainingMs = graceCountdownEndMs - System.currentTimeMillis()
            if (remainingMs <= 0L) {
                clearGraceCountdown()
                onGraceExpired()
                return
            }
            button.text = service.getString(
                R.string.app_limit_grace_countdown,
                formatCountdown(remainingMs)
            )
            mainHandler.postDelayed(this, GRACE_COUNTDOWN_TICK_MS)
        }
    }

    fun render(
        targets: List<BlockTarget>,
        windows: List<WindowGeometry>,
        graceStatus: AppLimitGraceStatus
    ) {
        val pieces = targets.flatMap { target ->
            val occluders = windows.asSequence()
                .filter { it.layer > target.layer }
                .filter { window ->
                    BlockingOccluderPolicy.shouldPreserve(
                        targetBounds = target.bounds,
                        occluderBounds = window.bounds,
                        isVolumePanel = window.isVolumePanel,
                    )
                }
                .map { it.bounds }
                .toList()
            val visibleRects = VisibleRegionCalculator.calculate(target.bounds, occluders)
                .filter { it.width >= MIN_OVERLAY_EDGE_PX && it.height >= MIN_OVERLAY_EDGE_PX }
            val orderedRects = BlockingControlsPlacement.order(
                target = target.bounds,
                visibleRects = visibleRects,
                minWidth = MIN_CARD_WIDTH_PX,
                minHeight = MIN_CARD_HEIGHT_PX
            )
            orderedRects.mapIndexed { index, rect -> OverlayPiece(target, rect, showControls = index == 0) }
        }
        val signature = pieces.joinToString("|") { piece ->
            "${piece.target.windowId}:${piece.target.reason}:${piece.rect}:${piece.showControls}"
        } + ":${graceStatus.uiSignature()}"
        if (signature != lastSignature) {
            clearBlockingViews()
            lastSignature = signature
            val allPiecesAttached = pieces.map { attachPiece(it, graceStatus) }.all { it }
            if (!allPiecesAttached) {
                clearBlockingViews()
                lastSignature = null
            }
            BlockingOverlayStateStore.setVisible(pieces.isNotEmpty() && allPiecesAttached)
        }

        when (graceStatus) {
            is AppLimitGraceStatus.Active -> showGraceCountdown(graceStatus.endMs)
            AppLimitGraceStatus.NormalAvailable,
            is AppLimitGraceStatus.Cooldown -> clearGraceCountdown()
        }
    }

    fun clear() {
        clearBlockingViews()
        clearGraceCountdown()
        BlockingOverlayStateStore.setVisible(false)
    }

    private fun clearBlockingViews() {
        contentControllers.forEach(ActiveOverlayContentController::dispose)
        contentControllers.clear()
        blockingViews.forEach { view ->
            runCatching { windowManager.removeViewImmediate(view) }
        }
        blockingViews.clear()
        lastSignature = null
    }

    private fun attachPiece(
        piece: OverlayPiece,
        graceStatus: AppLimitGraceStatus
    ): Boolean {
        val view = createPieceView(piece, graceStatus)
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
            blockingViews += view
        }.isSuccess
    }

    @SuppressLint("InflateParams")
    private fun createPieceView(
        piece: OverlayPiece,
        graceStatus: AppLimitGraceStatus
    ): View {
        if (piece.showControls && piece.rect.width >= MIN_CARD_WIDTH_PX &&
            piece.rect.height >= MIN_CARD_HEIGHT_PX
        ) {
            return LayoutInflater.from(service).inflate(R.layout.overlay_active, null).apply {
                contentDescription = service.getString(R.string.blocking_overlay_content_description)
                contentControllers += ActiveOverlayContentController(
                    context = service,
                    handler = Handler(Looper.getMainLooper()),
                    rootView = this,
                    timeText = null,
                    requiresCloseDelay = ActiveOverlayTrigger.APP_BLOCK.requiresCloseDelay,
                    onClose = onClose,
                    onOpenBrickMode = onOpenBrickMode,
                    graceControls = graceStatus.controlsFor(piece.target.reason)
                )
            }
        }

        return FrameLayout(service).apply {
            setBackgroundColor(service.getColor(R.color.graphite_overlay_background))
            contentDescription = service.getString(R.string.blocking_overlay_content_description)
            isClickable = true
            setOnTouchListener { view, event ->
                if (event.action == MotionEvent.ACTION_UP) view.performClick()
                true
            }
        }
    }

    private fun AppLimitGraceStatus.controlsFor(reason: BlockReason): AppLimitGraceControls? {
        if (reason == BlockReason.BRICK_MODE) return null
        return when (this) {
            AppLimitGraceStatus.NormalAvailable -> AppLimitGraceControls(
                optionsMinutes = APP_LIMIT_GRACE_MINUTES,
                onStartNormal = onStartNormalGrace
            )
            is AppLimitGraceStatus.Cooldown -> AppLimitGraceControls(
                cooldownRemainingMs = endMs - System.currentTimeMillis(),
                emergencyAvailable = emergencyAvailable,
                onStartEmergency = onStartEmergencyGrace
            )
            is AppLimitGraceStatus.Active -> null
        }
    }

    private fun AppLimitGraceStatus.uiSignature(): String = when (this) {
        AppLimitGraceStatus.NormalAvailable -> "normal"
        is AppLimitGraceStatus.Active -> "active:$kind:$endMs"
        is AppLimitGraceStatus.Cooldown -> "cooldown:$endMs:$emergencyAvailable"
    }

    private fun showGraceCountdown(endMs: Long) {
        if (graceCountdownButton != null && graceCountdownEndMs == endMs) return
        clearGraceCountdown()
        graceCountdownEndMs = endMs
        val button = Button(service).apply {
            isAllCaps = false
            textSize = 14f
            setTextColor(service.getColor(R.color.graphite_text_primary))
            backgroundTintList = ColorStateList.valueOf(
                service.getColor(R.color.graphite_surface_elevated)
            )
            contentDescription = service.getString(R.string.app_limit_grace_countdown_description)
            setOnClickListener { onEndGrace() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            dp(GRACE_COUNTDOWN_HEIGHT_DP),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(GRACE_COUNTDOWN_TOP_MARGIN_DP)
        }
        val attached = runCatching { windowManager.addView(button, params) }.isSuccess
        if (!attached) {
            graceCountdownEndMs = 0L
            return
        }
        graceCountdownButton = button
        updateGraceCountdownRunnable.run()
    }

    private fun clearGraceCountdown() {
        mainHandler.removeCallbacks(updateGraceCountdownRunnable)
        graceCountdownButton?.let { view ->
            runCatching { windowManager.removeViewImmediate(view) }
        }
        graceCountdownButton = null
        graceCountdownEndMs = 0L
    }

    private fun formatCountdown(remainingMs: Long): String {
        val totalSeconds = ((remainingMs + 999L) / 1_000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "%d:%02d".format(minutes, seconds)
    }

    private fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density).toInt()

    private data class OverlayPiece(
        val target: BlockTarget,
        val rect: ScreenRect,
        val showControls: Boolean
    )

    private companion object {
        const val MIN_OVERLAY_EDGE_PX = 2
        const val MIN_CARD_WIDTH_PX = 280
        const val MIN_CARD_HEIGHT_PX = 220
        const val GRACE_COUNTDOWN_TICK_MS = 1_000L
        const val GRACE_COUNTDOWN_HEIGHT_DP = 48
        const val GRACE_COUNTDOWN_TOP_MARGIN_DP = 18
    }
}
