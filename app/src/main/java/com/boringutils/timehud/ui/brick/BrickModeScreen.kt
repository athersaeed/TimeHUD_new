package com.boringutils.timehud.ui.brick

import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.boringutils.timehud.R
import com.boringutils.timehud.blocking.BrickModeApp
import com.boringutils.timehud.blocking.BrickModeCatalogLoader
import com.boringutils.timehud.blocking.BrickModeSchedule
import com.boringutils.timehud.blocking.BrickModeSchedulePolicy
import com.boringutils.timehud.blocking.BrickModeSettings
import com.boringutils.timehud.blocking.BrickModeTimer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.UUID

internal data class BrickModeAppUi(
    val packageName: String,
    val appName: String,
    val alwaysAvailable: Boolean,
    val allowed: Boolean
)

internal data class BrickModeUiState(
    val isLoading: Boolean = true,
    val enabled: Boolean = false,
    val endsAtEpochMs: Long? = null,
    val schedules: List<BrickModeSchedule> = emptyList(),
    val apps: List<BrickModeAppUi> = emptyList()
)

internal fun parseBrickModeDurationMinutes(value: String): Int? = value
    .trim()
    .toIntOrNull()
    ?.takeIf { it in 1..BrickModeTimer.MAX_DURATION_MINUTES }

internal fun formatBrickModeRemaining(remainingMs: Long): String {
    val totalSeconds = (remainingMs.coerceAtLeast(0L) + 999L) / 1_000L
    val days = totalSeconds / 86_400L
    val hours = totalSeconds % 86_400L / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return when {
        days > 0L -> "${days}d ${hours}h ${minutes}m ${seconds}s"
        hours > 0L -> "${hours}h ${minutes}m ${seconds}s"
        minutes > 0L -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

internal fun parseBrickModeScheduleStart(value: String): Int? {
    val parts = value.trim().split(':')
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
    val minute = parts[1].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
    if (parts[1].length != 2) return null
    return hour * 60 + minute
}

internal fun parseBrickModeScheduleDuration(value: String): Int? = value
    .trim()
    .toIntOrNull()
    ?.takeIf { it in 1..24 * 60 }

internal fun formatBrickModeScheduleDuration(durationMinutes: Int): String {
    val hours = durationMinutes / 60
    val minutes = durationMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}

internal fun filterBrickModeApps(
    apps: List<BrickModeAppUi>,
    query: String
): List<BrickModeAppUi> {
    val trimmedQuery = query.trim()
    if (trimmedQuery.isEmpty()) return apps
    return apps.filter { app ->
        app.appName.contains(trimmedQuery, ignoreCase = true) ||
            app.packageName.contains(trimmedQuery, ignoreCase = true)
    }
}

internal class BrickModeViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(BrickModeUiState())
    val uiState = _uiState.asStateFlow()
    private var refreshJob: Job? = null

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            _uiState.value = withContext(Dispatchers.IO) { loadState() }
        }
    }

    fun setEnabled(enabled: Boolean) {
        BrickModeSettings.setEnabled(getApplication(), enabled)
        refresh()
    }

    fun startTimed(durationMinutes: Int) {
        if (BrickModeSettings.startTimed(getApplication(), durationMinutes)) refresh()
    }

    fun setPackageAllowed(packageName: String, allowed: Boolean) {
        BrickModeSettings.setPackageAllowed(getApplication(), packageName, allowed)
        refresh()
    }

    fun addSchedule(daysOfWeek: Set<Int>, startMinuteOfDay: Int, durationMinutes: Int) {
        val schedule = BrickModeSchedule(
            id = UUID.randomUUID().toString(),
            daysOfWeek = daysOfWeek,
            startMinuteOfDay = startMinuteOfDay,
            durationMinutes = durationMinutes
        )
        if (BrickModeSettings.saveSchedule(getApplication(), schedule)) refresh()
    }

    fun setScheduleEnabled(scheduleId: String, enabled: Boolean) {
        BrickModeSettings.setScheduleEnabled(getApplication(), scheduleId, enabled)
        refresh()
    }

    fun removeSchedule(scheduleId: String) {
        BrickModeSettings.removeSchedule(getApplication(), scheduleId)
        refresh()
    }

    private fun loadState(): BrickModeUiState {
        val context = getApplication<Application>()
        val config = BrickModeSettings.load(context)
        val schedules = BrickModeSettings.loadSchedules(context)
        val catalog = BrickModeCatalogLoader.load(context)
        return BrickModeUiState(
            isLoading = false,
            enabled = config.enabled,
            endsAtEpochMs = config.endsAtEpochMs,
            schedules = schedules,
            apps = catalog.apps.map { app -> app.toUi(config.allowedPackages) }
        )
    }

    private fun BrickModeApp.toUi(allowedPackages: Set<String>) = BrickModeAppUi(
        packageName = packageName,
        appName = appName,
        alwaysAvailable = alwaysAvailable,
        allowed = alwaysAvailable || packageName in allowedPackages
    )
}

@Composable
internal fun BrickModeScreen(
    accessibilityServiceEnabled: Boolean,
    viewModel: BrickModeViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showAccessibilityDisclosure by remember { mutableStateOf(false) }
    var showAddScheduleDialog by remember { mutableStateOf(false) }
    var timerDurationInput by rememberSaveable { mutableStateOf("60") }
    var timerDurationHasError by rememberSaveable { mutableStateOf(false) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val filteredApps = remember(state.apps, searchQuery) {
        filterBrickModeApps(state.apps, searchQuery)
    }
    val essentialApps = filteredApps.filter(BrickModeAppUi::alwaysAvailable)
    val selectableApps = filteredApps.filterNot(BrickModeAppUi::alwaysAvailable)
    val selectedCount = state.apps.count { it.allowed && !it.alwaysAvailable }
    val scheduledActive = BrickModeSchedulePolicy.isAnyActive(state.schedules, nowMs)
    val effectiveEnabled = state.enabled || scheduledActive
    val remainingTimeText = state.endsAtEpochMs
        ?.takeIf { state.enabled }
        ?.let { endsAtEpochMs -> formatBrickModeRemaining(endsAtEpochMs - nowMs) }

    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(state.enabled, state.endsAtEpochMs, state.schedules) {
        val endsAtEpochMs = state.endsAtEpochMs?.takeIf { state.enabled }
        val hasEnabledSchedule = state.schedules.any(BrickModeSchedule::enabled)
        if (endsAtEpochMs == null && !hasEnabledSchedule) return@LaunchedEffect
        while (true) {
            nowMs = System.currentTimeMillis()
            if (endsAtEpochMs != null && nowMs >= endsAtEpochMs) {
                viewModel.refresh()
                break
            }
            delay(1_000L)
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.brick_mode_heading),
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.brick_mode_description),
                color = Color(0xFF9999B5),
                fontSize = 13.sp
            )
        }

        item {
            BrickModeStatusCard(
                active = effectiveEnabled,
                manualEnabled = state.enabled,
                scheduledActive = scheduledActive,
                selectedCount = selectedCount,
                remainingTimeText = remainingTimeText,
                onEnabledChange = { enabled ->
                    if (enabled && !accessibilityServiceEnabled) {
                        showAccessibilityDisclosure = true
                    } else {
                        viewModel.setEnabled(enabled)
                    }
                }
            )
        }

        item {
            BrickModeTimerCard(
                durationMinutes = timerDurationInput,
                durationHasError = timerDurationHasError,
                enabled = state.enabled,
                remainingTimeText = remainingTimeText,
                onDurationChange = { value ->
                    if (value.length <= 5 && value.all(Char::isDigit)) {
                        timerDurationInput = value
                        timerDurationHasError = false
                    }
                },
                onStartTimer = {
                    val durationMinutes = parseBrickModeDurationMinutes(timerDurationInput)
                    if (durationMinutes == null) {
                        timerDurationHasError = true
                    } else if (!accessibilityServiceEnabled) {
                        showAccessibilityDisclosure = true
                    } else {
                        timerDurationHasError = false
                        viewModel.startTimed(durationMinutes)
                    }
                }
            )
        }

        item {
            BrickModeSchedulesCard(
                schedules = state.schedules,
                nowMs = nowMs,
                onAddSchedule = { showAddScheduleDialog = true },
                onScheduleEnabledChange = viewModel::setScheduleEnabled,
                onRemoveSchedule = viewModel::removeSchedule
            )
        }

        item {
            BrickModeMessageCard(
                title = stringResource(R.string.brick_mode_background_title),
                message = stringResource(R.string.brick_mode_background_message)
            )
        }

        if (
            !accessibilityServiceEnabled &&
            (effectiveEnabled || state.schedules.any(BrickModeSchedule::enabled))
        ) {
            item {
                BrickModeMessageCard(
                    title = stringResource(R.string.brick_mode_access_required_title),
                    message = stringResource(R.string.brick_mode_access_required_message),
                    actionLabel = stringResource(R.string.brick_mode_enable_access),
                    onAction = { showAccessibilityDisclosure = true }
                )
            }
        }

        if (state.isLoading && state.apps.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(color = Color(0xFF668DFF))
                }
            }
        } else if (state.apps.isEmpty()) {
            item {
                BrickModeMessageCard(
                    title = stringResource(R.string.brick_mode_empty_title),
                    message = stringResource(R.string.brick_mode_empty_message)
                )
            }
        } else {
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.brick_mode_search_label)) },
                    placeholder = { Text(stringResource(R.string.brick_mode_search_placeholder)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color(0xFF668DFF),
                        focusedBorderColor = Color(0xFF668DFF),
                        unfocusedBorderColor = Color(0xFF3A3A50),
                        focusedLabelColor = Color(0xFFAEC2FF),
                        unfocusedLabelColor = Color(0xFF9999B5),
                        focusedPlaceholderColor = Color(0xFF66667A),
                        unfocusedPlaceholderColor = Color(0xFF66667A)
                    )
                )
            }

            if (filteredApps.isEmpty()) {
                item {
                    BrickModeMessageCard(
                        title = stringResource(R.string.brick_mode_search_empty_title),
                        message = stringResource(R.string.brick_mode_search_empty_message)
                    )
                }
            } else {
                if (essentialApps.isNotEmpty()) {
                    item { BrickModeSectionHeader(R.string.brick_mode_essential_apps) }
                    items(essentialApps, key = { "essential:${it.packageName}" }) { app ->
                        BrickModeAppRow(app = app, onAllowedChange = {})
                    }
                }
                if (selectableApps.isNotEmpty()) {
                    item { BrickModeSectionHeader(R.string.brick_mode_choose_apps) }
                    items(selectableApps, key = { "selectable:${it.packageName}" }) { app ->
                        BrickModeAppRow(
                            app = app,
                            onAllowedChange = { allowed ->
                                viewModel.setPackageAllowed(app.packageName, allowed)
                            }
                        )
                    }
                }
            }
        }
    }

    if (showAccessibilityDisclosure) {
        AlertDialog(
            onDismissRequest = { showAccessibilityDisclosure = false },
            title = { Text(stringResource(R.string.app_blocking_disclosure_title)) },
            text = { Text(stringResource(R.string.app_blocking_disclosure_message)) },
            confirmButton = {
                Button(onClick = {
                    showAccessibilityDisclosure = false
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) {
                    Text(stringResource(R.string.app_blocking_disclosure_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAccessibilityDisclosure = false }) {
                    Text(stringResource(R.string.goal_backup_cancel))
                }
            }
        )
    }


    if (showAddScheduleDialog) {
        AddBrickModeScheduleDialog(
            onDismiss = { showAddScheduleDialog = false },
            onSave = { daysOfWeek, startMinuteOfDay, durationMinutes ->
                viewModel.addSchedule(daysOfWeek, startMinuteOfDay, durationMinutes)
                showAddScheduleDialog = false
            }
        )
    }
}

@Composable
private fun BrickModeStatusCard(
    active: Boolean,
    manualEnabled: Boolean,
    scheduledActive: Boolean,
    selectedCount: Int,
    remainingTimeText: String?,
    onEnabledChange: (Boolean) -> Unit
) {
    val switchDescription = stringResource(R.string.brick_mode_switch_description)
    val selectedCountText = pluralStringResource(
        R.plurals.brick_mode_selected_count,
        selectedCount,
        selectedCount
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (active) Color(0xFF18284A) else Color(0xFF171725))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = when {
                    scheduledActive && !manualEnabled -> {
                        stringResource(R.string.brick_mode_active_scheduled)
                    }
                    active -> stringResource(R.string.brick_mode_active)
                    else -> stringResource(R.string.brick_mode_inactive)
                },
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = selectedCountText,
                color = Color(0xFF9999B5),
                fontSize = 12.sp
            )
            if (remainingTimeText != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.brick_mode_timer_remaining, remainingTimeText),
                    color = Color(0xFF75D69C),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        Switch(
            checked = manualEnabled,
            onCheckedChange = onEnabledChange,
            modifier = Modifier.semantics { contentDescription = switchDescription }
        )
    }
}

@Composable
private fun BrickModeTimerCard(
    durationMinutes: String,
    durationHasError: Boolean,
    enabled: Boolean,
    remainingTimeText: String?,
    onDurationChange: (String) -> Unit,
    onStartTimer: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF171725))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.brick_mode_timer_title),
            color = Color.White,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = when {
                remainingTimeText != null -> stringResource(
                    R.string.brick_mode_timer_active,
                    remainingTimeText
                )
                enabled -> stringResource(R.string.brick_mode_timer_until_off)
                else -> stringResource(R.string.brick_mode_timer_description)
            },
            color = if (remainingTimeText != null) Color(0xFF75D69C) else Color(0xFF9999B5),
            fontSize = 13.sp
        )
        OutlinedTextField(
            value = durationMinutes,
            onValueChange = onDurationChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.brick_mode_timer_minutes_label)) },
            supportingText = {
                Text(
                    stringResource(
                        if (durationHasError) {
                            R.string.brick_mode_timer_invalid
                        } else {
                            R.string.brick_mode_timer_range
                        }
                    )
                )
            },
            isError = durationHasError,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Color(0xFF668DFF),
                focusedBorderColor = Color(0xFF668DFF),
                unfocusedBorderColor = Color(0xFF3A3A50),
                focusedLabelColor = Color(0xFFAEC2FF),
                unfocusedLabelColor = Color(0xFF9999B5)
            )
        )
        Button(
            onClick = onStartTimer,
            modifier = Modifier.align(Alignment.End)
        ) {
            Text(
                stringResource(
                    if (enabled) R.string.brick_mode_timer_set else R.string.brick_mode_timer_start
                )
            )
        }
    }
}

@Composable
private fun BrickModeSchedulesCard(
    schedules: List<BrickModeSchedule>,
    nowMs: Long,
    onAddSchedule: () -> Unit,
    onScheduleEnabledChange: (String, Boolean) -> Unit,
    onRemoveSchedule: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF171725))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.brick_mode_schedules_title),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.brick_mode_schedules_description),
                    color = Color(0xFF9999B5),
                    fontSize = 12.sp
                )
            }
            Button(onClick = onAddSchedule) {
                Text(stringResource(R.string.brick_mode_schedule_add))
            }
        }

        if (schedules.isEmpty()) {
            Text(
                text = stringResource(R.string.brick_mode_schedules_empty),
                color = Color(0xFF777790),
                fontSize = 13.sp
            )
        } else {
            schedules.forEach { schedule ->
                BrickModeScheduleRow(
                    schedule = schedule,
                    active = BrickModeSchedulePolicy.isActive(schedule, nowMs),
                    onEnabledChange = { enabled ->
                        onScheduleEnabledChange(schedule.id, enabled)
                    },
                    onRemove = { onRemoveSchedule(schedule.id) }
                )
            }
        }
    }
}

@Composable
private fun BrickModeScheduleRow(
    schedule: BrickModeSchedule,
    active: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    val daysText = brickModeScheduleDaysText(schedule.daysOfWeek)
    val startText = remember(schedule.startMinuteOfDay) {
        formatBrickModeScheduleStart(context, schedule.startMinuteOfDay)
    }
    val durationText = formatBrickModeScheduleDuration(schedule.durationMinutes)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) Color(0xFF18284A) else Color(0xFF111120))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        R.string.brick_mode_schedule_summary,
                        daysText,
                        startText,
                        durationText
                    ),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                if (active) {
                    Text(
                        text = stringResource(R.string.brick_mode_schedule_active_now),
                        color = Color(0xFF75D69C),
                        fontSize = 11.sp
                    )
                }
            }
            Switch(
                checked = schedule.enabled,
                onCheckedChange = onEnabledChange,
                modifier = Modifier.semantics {
                    contentDescription = context.getString(
                        R.string.brick_mode_schedule_switch_description,
                        daysText,
                        startText
                    )
                }
            )
        }
        TextButton(
            onClick = onRemove,
            modifier = Modifier.align(Alignment.End)
        ) {
            Text(stringResource(R.string.brick_mode_schedule_remove))
        }
    }
}

@Composable
private fun AddBrickModeScheduleDialog(
    onDismiss: () -> Unit,
    onSave: (Set<Int>, Int, Int) -> Unit
) {
    val context = LocalContext.current
    var selectedDays by remember {
        mutableStateOf(
            setOf(
                Calendar.MONDAY,
                Calendar.TUESDAY,
                Calendar.WEDNESDAY,
                Calendar.THURSDAY,
                Calendar.FRIDAY
            )
        )
    }
    var startTime by remember { mutableStateOf("09:00") }
    var durationMinutes by remember { mutableStateOf("60") }
    var hasError by remember { mutableStateOf(false) }
    val orderedDays = listOf(
        Calendar.MONDAY,
        Calendar.TUESDAY,
        Calendar.WEDNESDAY,
        Calendar.THURSDAY,
        Calendar.FRIDAY,
        Calendar.SATURDAY,
        Calendar.SUNDAY
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.brick_mode_schedule_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.brick_mode_schedule_days_label),
                    fontWeight = FontWeight.SemiBold
                )
                orderedDays.chunked(4).forEach { rowDays ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rowDays.forEach { day ->
                            FilterChip(
                                selected = day in selectedDays,
                                onClick = {
                                    selectedDays = if (day in selectedDays) {
                                        selectedDays - day
                                    } else {
                                        selectedDays + day
                                    }
                                    hasError = false
                                },
                                label = { Text(brickModeDayLabel(context, day)) }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = startTime,
                    onValueChange = { value ->
                        if (value.length <= 5 && value.all { it.isDigit() || it == ':' }) {
                            startTime = value
                            hasError = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.brick_mode_schedule_start_label)) },
                    supportingText = {
                        Text(stringResource(R.string.brick_mode_schedule_start_hint))
                    },
                    isError = hasError && parseBrickModeScheduleStart(startTime) == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                )
                OutlinedTextField(
                    value = durationMinutes,
                    onValueChange = { value ->
                        if (value.length <= 4 && value.all(Char::isDigit)) {
                            durationMinutes = value
                            hasError = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(stringResource(R.string.brick_mode_schedule_duration_label))
                    },
                    supportingText = {
                        Text(stringResource(R.string.brick_mode_schedule_duration_hint))
                    },
                    isError = hasError && parseBrickModeScheduleDuration(durationMinutes) == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                if (hasError) {
                    Text(
                        text = stringResource(R.string.brick_mode_schedule_invalid),
                        color = Color(0xFFFF8A80),
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val startMinuteOfDay = parseBrickModeScheduleStart(startTime)
                val duration = parseBrickModeScheduleDuration(durationMinutes)
                if (selectedDays.isEmpty() || startMinuteOfDay == null || duration == null) {
                    hasError = true
                } else {
                    onSave(selectedDays, startMinuteOfDay, duration)
                }
            }) {
                Text(stringResource(R.string.brick_mode_schedule_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.goal_backup_cancel))
            }
        }
    )
}

@Composable
private fun brickModeScheduleDaysText(daysOfWeek: Set<Int>): String =
    if (daysOfWeek.size == 7) {
        stringResource(R.string.brick_mode_schedule_every_day)
    } else {
        val context = LocalContext.current
        listOf(
            Calendar.MONDAY,
            Calendar.TUESDAY,
            Calendar.WEDNESDAY,
            Calendar.THURSDAY,
            Calendar.FRIDAY,
            Calendar.SATURDAY,
            Calendar.SUNDAY
        ).filter(daysOfWeek::contains).joinToString(separator = ", ") { day ->
            brickModeDayLabel(context, day)
        }
    }

private fun brickModeDayLabel(context: Context, dayOfWeek: Int): String = context.getString(
    when (dayOfWeek) {
        Calendar.MONDAY -> R.string.brick_mode_day_monday
        Calendar.TUESDAY -> R.string.brick_mode_day_tuesday
        Calendar.WEDNESDAY -> R.string.brick_mode_day_wednesday
        Calendar.THURSDAY -> R.string.brick_mode_day_thursday
        Calendar.FRIDAY -> R.string.brick_mode_day_friday
        Calendar.SATURDAY -> R.string.brick_mode_day_saturday
        else -> R.string.brick_mode_day_sunday
    }
)

private fun formatBrickModeScheduleStart(context: Context, startMinuteOfDay: Int): String {
    val calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, startMinuteOfDay / 60)
        set(Calendar.MINUTE, startMinuteOfDay % 60)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return DateFormat.getTimeFormat(context).format(calendar.time)
}

@Composable
private fun BrickModeSectionHeader(titleRes: Int) {
    Text(
        text = stringResource(titleRes),
        color = Color.White,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun BrickModeAppRow(
    app: BrickModeAppUi,
    onAllowedChange: (Boolean) -> Unit
) {
    val switchDescription = stringResource(R.string.brick_mode_app_switch_description, app.appName)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF171725))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.appName,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (app.alwaysAvailable) {
                    stringResource(R.string.brick_mode_always_available)
                } else {
                    app.packageName
                },
                color = if (app.alwaysAvailable) Color(0xFF75D69C) else Color(0xFF777790),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(
            checked = app.allowed,
            onCheckedChange = if (app.alwaysAvailable) null else onAllowedChange,
            enabled = !app.alwaysAvailable,
            modifier = Modifier.semantics { contentDescription = switchDescription }
        )
    }
}

@Composable
private fun BrickModeMessageCard(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF171725))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
        Text(message, color = Color(0xFF9999B5), fontSize = 13.sp)
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
