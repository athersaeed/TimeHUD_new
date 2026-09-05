package com.boringutils.timehud

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.boringutils.timehud.ui.backup.GoalBackupPanel
import com.boringutils.timehud.ui.backup.GoalImportConfirmationDialog
import com.boringutils.timehud.blocking.AccessibilityServiceStatus
import com.boringutils.timehud.ui.blocking.AppBlockingScreen
import com.boringutils.timehud.ui.brick.BrickModeScreen
import com.boringutils.timehud.ui.navigation.TimeHudDestination
import com.boringutils.timehud.ui.navigation.TimeHudDrawerScaffold
import com.boringutils.timehud.ui.theme.TimeHudColors
import com.boringutils.timehud.ui.theme.TimeHUDTheme
import com.boringutils.timehud.ui.usage.AppUsageScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var requestedDestination by mutableStateOf<TimeHudDestination?>(null)
    private var destinationRequestId by mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeDestinationRequest(intent)

        enableEdgeToEdge()
        setContent {
            TimeHUDTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = TimeHudColors.background
                ) {
                    TimeHUDScreen(
                        onStartService = {
                            startOverlayService(this)
                        },
                        onStopService = {
                            stopOverlayService(this)
                        },
                        requestedDestination = requestedDestination,
                        destinationRequestId = destinationRequestId
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeDestinationRequest(intent)
    }

    private fun consumeDestinationRequest(intent: Intent) {
        val destination = parseTimeHudDestination(
            intent.getStringExtra(EXTRA_TIMEHUD_DESTINATION)
        ) ?: return
        intent.removeExtra(EXTRA_TIMEHUD_DESTINATION)
        requestedDestination = destination
        destinationRequestId += 1L
    }
}

internal const val EXTRA_TIMEHUD_DESTINATION = "com.boringutils.timehud.extra.DESTINATION"

internal fun parseTimeHudDestination(value: String?): TimeHudDestination? = value?.let { name ->
    runCatching { TimeHudDestination.valueOf(name) }.getOrNull()
}

internal fun createTimeHudDestinationIntent(
    context: Context,
    destination: TimeHudDestination
): Intent = Intent(context, MainActivity::class.java).apply {
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_ACTIVITY_SINGLE_TOP or
        Intent.FLAG_ACTIVITY_CLEAR_TOP
    putExtra(EXTRA_TIMEHUD_DESTINATION, destination.name)
}

private fun hasOverlayPermission(context: Context): Boolean =
    Settings.canDrawOverlays(context)

private fun hasUsagePermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    @Suppress("DEPRECATION")
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
    } else {
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

private fun requestOverlayPermission(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}")
    )
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private fun requestUsagePermission(context: Context) {
    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private fun startOverlayService(context: Context) {
    val serviceIntent = Intent(context, OverlayService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(serviceIntent)
    } else {
        context.startService(serviceIntent)
    }
}

private fun stopOverlayService(context: Context) {
    StartupPreferences.markHudStopped(context)
    context.stopService(Intent(context, OverlayService::class.java))
}

@Composable
fun TimeHUDScreen(
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    requestedDestination: TimeHudDestination? = null,
    destinationRequestId: Long = 0L
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val serviceState by OverlayServiceStateStore.uiState.collectAsState()

    var overlayGranted by remember { mutableStateOf(hasOverlayPermission(context)) }
    var usageGranted by remember { mutableStateOf(hasUsagePermission(context)) }
    var accessibilityGranted by remember {
        mutableStateOf(AccessibilityServiceStatus.isEnabled(context))
    }
    var calendarGranted by remember { mutableStateOf(CalendarAgenda.hasReadCalendarPermission(context)) }
    var selectedDestinationName by rememberSaveable {
        mutableStateOf((requestedDestination ?: TimeHudDestination.GOALS).name)
    }
    val selectedDestination = runCatching {
        TimeHudDestination.valueOf(selectedDestinationName)
    }.getOrDefault(TimeHudDestination.GOALS)

    LaunchedEffect(requestedDestination, destinationRequestId) {
        requestedDestination?.let { destination ->
            selectedDestinationName = destination.name
        }
    }

    val initialGoalConfig = remember { GoalSettings.load(context) }
    var shortTermGoals by rememberSaveable { mutableStateOf(initialGoalConfig.shortTermGoals) }
    var longTermGoals by rememberSaveable { mutableStateOf(initialGoalConfig.longTermGoals) }
    var goalsSaved by rememberSaveable { mutableStateOf(false) }
    var calendarImportStatus by rememberSaveable { mutableStateOf<String?>(null) }
    var backupStatusMessageRes by rememberSaveable { mutableStateOf<Int?>(null) }
    var backupStatusIsError by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var backupOperationInProgress by rememberSaveable { mutableStateOf(false) }

    var pendingExportShortTermGoals by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingExportLongTermGoals by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingExportTimestamp by rememberSaveable { mutableStateOf<Long?>(null) }

    var pendingImportShortTermGoals by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingImportLongTermGoals by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingImportReady by rememberSaveable { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    fun clearBackupStatus() {
        backupStatusMessageRes = null
        backupStatusIsError = null
    }

    fun showBackupStatus(messageRes: Int, isError: Boolean?) {
        backupStatusMessageRes = messageRes
        backupStatusIsError = isError
    }

    fun clearPendingImport() {
        pendingImportShortTermGoals = null
        pendingImportLongTermGoals = null
        pendingImportReady = false
    }

    fun applyCalendarImport() {
        clearBackupStatus()
        val items = CalendarAgenda.loadTodayVisibleInstances(context)
        if (items.isEmpty()) {
            calendarImportStatus = "No visible calendar events today"
            return
        }

        shortTermGoals = CalendarAgenda.appendCalendarSection(shortTermGoals, items)
        goalsSaved = false
        val noun = if (items.size == 1) "event" else "events"
        calendarImportStatus = "Imported ${items.size} calendar $noun. Review and save."
    }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        calendarGranted = granted
        calendarImportStatus = context.getString(
            if (granted) {
                R.string.calendar_permission_granted
            } else {
                R.string.calendar_permission_denied
            }
        )
    }

    fun requestCalendarImport() {
        if (CalendarAgenda.hasReadCalendarPermission(context)) {
            calendarGranted = true
            applyCalendarImport()
        } else {
            selectedDestinationName = TimeHudDestination.PERMISSIONS.name
        }
    }

    val exportGoalsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(GoalBackupFormat.MIME_TYPE)
    ) { uri ->
        val shortTermSnapshot = pendingExportShortTermGoals
        val longTermSnapshot = pendingExportLongTermGoals
        val exportTimestamp = pendingExportTimestamp
        pendingExportShortTermGoals = null
        pendingExportLongTermGoals = null
        pendingExportTimestamp = null

        if (uri != null) {
            if (shortTermSnapshot == null || longTermSnapshot == null || exportTimestamp == null) {
                showBackupStatus(R.string.goal_backup_write_failure, isError = true)
            } else {
                backupOperationInProgress = true
                showBackupStatus(R.string.goal_backup_exporting, isError = null)
                coroutineScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        GoalBackupStorage.write(
                            contentResolver = context.contentResolver,
                            uri = uri,
                            backup = GoalBackup.forExport(
                                shortTermGoalText = shortTermSnapshot,
                                longTermGoalText = longTermSnapshot,
                                exportedAtEpochMs = exportTimestamp
                            )
                        )
                    }
                    backupOperationInProgress = false
                    if (result == GoalBackupWriteResult.SUCCESS) {
                        showBackupStatus(R.string.goal_backup_export_success, isError = false)
                    } else {
                        showBackupStatus(R.string.goal_backup_write_failure, isError = true)
                    }
                }
            }
        }
    }

    val importGoalsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            backupOperationInProgress = true
            showBackupStatus(R.string.goal_backup_reading, isError = null)
            coroutineScope.launch {
                val result = withContext(Dispatchers.IO) {
                    GoalBackupStorage.read(context.contentResolver, uri)
                }
                backupOperationInProgress = false
                when (result) {
                    is GoalBackupReadResult.Success -> {
                        pendingImportShortTermGoals = result.backup.shortTermGoalText
                        pendingImportLongTermGoals = result.backup.longTermGoalText
                        pendingImportReady = true
                        clearBackupStatus()
                    }
                    GoalBackupReadResult.Invalid -> {
                        showBackupStatus(R.string.goal_backup_invalid, isError = true)
                    }
                    GoalBackupReadResult.Unreadable -> {
                        showBackupStatus(R.string.goal_backup_read_failure, isError = true)
                    }
                    GoalBackupReadResult.UnsupportedSchema -> {
                        showBackupStatus(R.string.goal_backup_unsupported, isError = true)
                    }
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = hasOverlayPermission(context)
                usageGranted = hasUsagePermission(context)
                accessibilityGranted = AccessibilityServiceStatus.isEnabled(context)
                calendarGranted = CalendarAgenda.hasReadCalendarPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun saveGoalSettings() {
        GoalSettings.save(
            context = context,
            shortTermGoals = shortTermGoals,
            longTermGoals = longTermGoals
        )
        goalsSaved = true
        clearBackupStatus()
    }

    val allGranted = overlayGranted && usageGranted

    TimeHudDrawerScaffold(
        selectedDestination = selectedDestination,
        onDestinationSelected = { selectedDestinationName = it.name }
    ) {
        when (selectedDestination) {
            TimeHudDestination.GOALS -> GoalsPage(
                shortTermGoals = shortTermGoals,
                onShortTermGoalsChange = {
                    shortTermGoals = it
                    goalsSaved = false
                    clearBackupStatus()
                },
                longTermGoals = longTermGoals,
                onLongTermGoalsChange = {
                    longTermGoals = it
                    goalsSaved = false
                    clearBackupStatus()
                },
                onSaveGoals = { saveGoalSettings() },
                goalsSaved = goalsSaved,
                calendarGranted = calendarGranted,
                calendarImportStatus = calendarImportStatus,
                onImportCalendar = { requestCalendarImport() },
                backupOperationInProgress = backupOperationInProgress,
                backupStatusMessageRes = backupStatusMessageRes,
                backupStatusIsError = backupStatusIsError,
                onExportGoals = {
                    clearBackupStatus()
                    pendingExportShortTermGoals = shortTermGoals
                    pendingExportLongTermGoals = longTermGoals
                    pendingExportTimestamp = System.currentTimeMillis()
                    exportGoalsLauncher.launch(context.getString(R.string.goal_backup_filename))
                },
                onImportGoals = {
                    clearBackupStatus()
                    clearPendingImport()
                    importGoalsLauncher.launch(arrayOf(GoalBackupFormat.MIME_TYPE))
                },
                serviceState = serviceState,
                allRequiredPermissionsGranted = allGranted,
                onOpenPermissions = {
                    selectedDestinationName = TimeHudDestination.PERMISSIONS.name
                },
                onStartService = {
                    saveGoalSettings()
                    onStartService()
                },
                onStopService = onStopService
            )

            TimeHudDestination.APP_USAGE -> AppUsageScreen(
                usagePermissionGranted = usageGranted,
                onOpenPermissions = {
                    selectedDestinationName = TimeHudDestination.PERMISSIONS.name
                }
            )

            TimeHudDestination.APP_LIMITS -> AppBlockingScreen(
                usagePermissionGranted = usageGranted,
                accessibilityServiceEnabled = accessibilityGranted,
                onOpenPermissions = {
                    selectedDestinationName = TimeHudDestination.PERMISSIONS.name
                }
            )

            TimeHudDestination.BRICK_MODE -> BrickModeScreen(
                accessibilityServiceEnabled = accessibilityGranted
            )

            TimeHudDestination.PERMISSIONS -> PermissionsPage(
                overlayGranted = overlayGranted,
                usageGranted = usageGranted,
                accessibilityGranted = accessibilityGranted,
                calendarGranted = calendarGranted,
                onRequestOverlay = { requestOverlayPermission(context) },
                onRequestUsage = { requestUsagePermission(context) },
                onRequestAccessibility = {
                    selectedDestinationName = TimeHudDestination.APP_LIMITS.name
                },
                onRequestCalendar = {
                    calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                }
            )
        }
    }

    if (pendingImportReady) {
        val importedBackup = GoalBackup(
            exportedAtEpochMs = 0L,
            shortTermGoalText = pendingImportShortTermGoals.orEmpty(),
            longTermGoalText = pendingImportLongTermGoals.orEmpty()
        )
        GoalImportConfirmationDialog(
            shortTermGoalCount = importedBackup.shortTermGoalCount,
            longTermGoalCount = importedBackup.longTermGoalCount,
            onReplace = {
                GoalSettings.replaceFromBackup(
                    context = context,
                    shortTermGoals = importedBackup.shortTermGoalText,
                    longTermGoals = importedBackup.longTermGoalText
                )
                shortTermGoals = importedBackup.shortTermGoalText
                longTermGoals = importedBackup.longTermGoalText
                goalsSaved = true
                calendarImportStatus = null
                clearPendingImport()
                showBackupStatus(R.string.goal_backup_import_success, isError = false)
            },
            onCancel = { clearPendingImport() }
        )
    }
}

@Composable
private fun GoalsPage(
    shortTermGoals: String,
    onShortTermGoalsChange: (String) -> Unit,
    longTermGoals: String,
    onLongTermGoalsChange: (String) -> Unit,
    onSaveGoals: () -> Unit,
    goalsSaved: Boolean,
    calendarGranted: Boolean,
    calendarImportStatus: String?,
    onImportCalendar: () -> Unit,
    backupOperationInProgress: Boolean,
    backupStatusMessageRes: Int?,
    backupStatusIsError: Boolean?,
    onExportGoals: () -> Unit,
    onImportGoals: () -> Unit,
    serviceState: OverlayServiceUiState,
    allRequiredPermissionsGranted: Boolean,
    onOpenPermissions: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(modifier = Modifier.height(20.dp))

        GoalSettingsPanel(
            shortTermGoals = shortTermGoals,
            onShortTermGoalsChange = onShortTermGoalsChange,
            longTermGoals = longTermGoals,
            onLongTermGoalsChange = onLongTermGoalsChange,
            onSave = onSaveGoals,
            saved = goalsSaved,
            calendarGranted = calendarGranted,
            calendarImportStatus = calendarImportStatus,
            onImportCalendar = onImportCalendar
        )

        Spacer(modifier = Modifier.height(16.dp))

        GoalBackupPanel(
            isBusy = backupOperationInProgress,
            statusMessageRes = backupStatusMessageRes,
            statusIsError = backupStatusIsError,
            onExport = onExportGoals,
            onImport = onImportGoals
        )

        Spacer(modifier = Modifier.height(32.dp))

        val primaryAction = serviceState.primaryAction
        val buttonColor by animateColorAsState(
            targetValue = if (primaryAction == ServicePrimaryAction.STOP) {
                TimeHudColors.surfaceSelected
            } else {
                TimeHudColors.action
            },
            animationSpec = tween(300),
            label = "btnColor"
        )
        val buttonContentColor = if (primaryAction == ServicePrimaryAction.STOP) {
            TimeHudColors.textPrimary
        } else {
            TimeHudColors.onAction
        }

        Button(
            onClick = {
                when (primaryAction) {
                    ServicePrimaryAction.STOP -> onStopService()
                    ServicePrimaryAction.START -> onStartService()
                }
            },
            enabled = serviceState.isRunning || allRequiredPermissionsGranted,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = buttonColor,
                contentColor = buttonContentColor,
                disabledContainerColor = TimeHudColors.disabledSurface
            )
        ) {
            Text(
                text = when (primaryAction) {
                    ServicePrimaryAction.START -> "Start HUD"
                    ServicePrimaryAction.STOP -> "Stop HUD"
                },
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (serviceState.isRunning || allRequiredPermissionsGranted) {
                    buttonContentColor
                } else {
                    TimeHudColors.textDisabled
                }
            )
        }

        if (!allRequiredPermissionsGranted) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.hud_permissions_needed),
                fontSize = 12.sp,
                color = TimeHudColors.textDisabled,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenPermissions) {
                Text(stringResource(R.string.open_permissions))
            }
        }

        Spacer(modifier = Modifier.height(28.dp))
    }
}

@Composable
private fun PermissionsPage(
    overlayGranted: Boolean,
    usageGranted: Boolean,
    accessibilityGranted: Boolean,
    calendarGranted: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestUsage: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onRequestCalendar: () -> Unit
) {
    val allRequiredPermissionsGranted = overlayGranted && usageGranted
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.permissions_heading),
            color = TimeHudColors.textPrimary,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.permissions_description),
            color = TimeHudColors.textSecondary,
            fontSize = 13.sp
        )
        Spacer(modifier = Modifier.height(22.dp))

        PermissionCard(
            title = stringResource(R.string.permission_overlay_title),
            description = stringResource(R.string.permission_overlay_description),
            granted = overlayGranted,
            onRequest = onRequestOverlay
        )
        Spacer(modifier = Modifier.height(14.dp))
        PermissionCard(
            title = stringResource(R.string.permission_usage_title),
            description = stringResource(R.string.permission_usage_description),
            granted = usageGranted,
            onRequest = onRequestUsage
        )
        Spacer(modifier = Modifier.height(14.dp))
        PermissionCard(
            title = stringResource(R.string.permission_accessibility_title),
            description = stringResource(R.string.permission_accessibility_description),
            granted = accessibilityGranted,
            onRequest = onRequestAccessibility
        )
        Spacer(modifier = Modifier.height(14.dp))
        PermissionCard(
            title = stringResource(R.string.permission_calendar_title),
            description = stringResource(R.string.permission_calendar_description),
            granted = calendarGranted,
            onRequest = onRequestCalendar
        )
        Spacer(modifier = Modifier.height(22.dp))

        Text(
            text = stringResource(
                if (allRequiredPermissionsGranted) {
                    R.string.permission_required_ready
                } else {
                    R.string.permission_required_missing
                }
            ),
            color = if (allRequiredPermissionsGranted) {
                TimeHudColors.statusPositive
            } else {
                TimeHudColors.statusWarning
            },
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(28.dp))
    }
}

@Composable
fun GoalSettingsPanel(
    shortTermGoals: String,
    onShortTermGoalsChange: (String) -> Unit,
    longTermGoals: String,
    onLongTermGoalsChange: (String) -> Unit,
    onSave: () -> Unit,
    saved: Boolean,
    calendarGranted: Boolean,
    calendarImportStatus: String?,
    onImportCalendar: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TimeHudColors.surface)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Goals",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TimeHudColors.textPrimary
                )
                Text(
                    text = "Shown on the 5 minute screen",
                    fontSize = 12.sp,
                    color = TimeHudColors.textSecondary
                )
            }
            Text(
                text = if (saved) "Saved" else "Editable",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (saved) TimeHudColors.statusPositive else TimeHudColors.textEmphasis
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        OutlinedTextField(
            value = shortTermGoals,
            onValueChange = onShortTermGoalsChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Short term daily to-do list") },
            minLines = 4,
            maxLines = 8,
            colors = timeHudTextFieldColors()
        )

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedButton(
            onClick = onImportCalendar,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TimeHudColors.textEmphasis)
        ) {
            Text(
                text = if (calendarGranted) {
                    "Import Today's Calendar"
                } else {
                    stringResource(R.string.calendar_permission_setup)
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        calendarImportStatus?.let { status ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = status,
                fontSize = 12.sp,
                color = TimeHudColors.textSecondary
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = longTermGoals,
            onValueChange = onLongTermGoalsChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Long term goals") },
            minLines = 4,
            maxLines = 8,
            colors = timeHudTextFieldColors()
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onSave,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = TimeHudColors.action)
        ) {
            Text("Save Goals", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun timeHudTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TimeHudColors.textPrimary,
    unfocusedTextColor = TimeHudColors.textPrimary,
    focusedContainerColor = TimeHudColors.background,
    unfocusedContainerColor = TimeHudColors.background,
    focusedBorderColor = TimeHudColors.action,
    unfocusedBorderColor = TimeHudColors.border,
    focusedLabelColor = TimeHudColors.textEmphasis,
    unfocusedLabelColor = TimeHudColors.textSecondary,
    cursorColor = TimeHudColors.action,
    focusedPlaceholderColor = TimeHudColors.textDisabled,
    unfocusedPlaceholderColor = TimeHudColors.textDisabled,
    focusedSupportingTextColor = TimeHudColors.textSecondary,
    unfocusedSupportingTextColor = TimeHudColors.textSecondary
)

@Composable
fun PermissionCard(
    title: String,
    description: String,
    granted: Boolean,
    onRequest: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (granted) TimeHudColors.surfaceElevated else TimeHudColors.surface,
        animationSpec = tween(400),
        label = "cardBg"
    )
    val accentColor = if (granted) TimeHudColors.statusPositive else TimeHudColors.textEmphasis

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = TimeHudColors.textPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                fontSize = 12.sp,
                color = TimeHudColors.textSecondary
            )
        }
        if (granted) {
            Text(
                text = stringResource(R.string.permission_granted),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = accentColor
            )
        } else {
            OutlinedButton(
                onClick = onRequest,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = accentColor)
            ) {
                Text(stringResource(R.string.permission_grant), fontSize = 13.sp)
            }
        }
    }
}
