package com.boringutils.timehud.ui.blocking

import android.app.Application
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
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
import com.boringutils.timehud.blocking.AppBlockRule
import com.boringutils.timehud.blocking.AppBlockSettings
import com.boringutils.timehud.blocking.AppSurface
import com.boringutils.timehud.blocking.INSTAGRAM_PACKAGE
import com.boringutils.timehud.blocking.supportedSurfacesFor
import com.boringutils.timehud.ui.usage.AppUsageLoadResult
import com.boringutils.timehud.ui.usage.AppUsageRepository
import com.boringutils.timehud.ui.usage.formatAppUsageDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class BlockableAppUi(
    val packageName: String,
    val appName: String,
    val usageMs: Long,
    val rule: AppBlockRule?
)

internal data class AppBlockingUiState(
    val isLoading: Boolean = false,
    val apps: List<BlockableAppUi> = emptyList(),
    val usagePermissionRequired: Boolean = false
)

internal fun filterBlockableApps(
    apps: List<BlockableAppUi>,
    query: String
): List<BlockableAppUi> {
    val trimmedQuery = query.trim()
    if (trimmedQuery.isEmpty()) return apps

    return apps.filter { app ->
        app.appName.contains(trimmedQuery, ignoreCase = true) ||
            app.packageName.contains(trimmedQuery, ignoreCase = true)
    }
}

internal class AppBlockingViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(AppBlockingUiState())
    val uiState = _uiState.asStateFlow()
    private var refreshJob: Job? = null

    fun refresh(usagePermissionGranted: Boolean) {
        refreshJob?.cancel()
        if (!usagePermissionGranted) {
            _uiState.value = AppBlockingUiState(usagePermissionRequired = true)
            return
        }
        refreshJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            _uiState.value = withContext(Dispatchers.IO) {
                loadState()
            }
        }
    }

    fun saveRule(rule: AppBlockRule, usagePermissionGranted: Boolean) {
        AppBlockSettings.saveRule(getApplication(), rule)
        refresh(usagePermissionGranted)
    }

    fun removeRule(packageName: String, usagePermissionGranted: Boolean) {
        AppBlockSettings.removeRule(getApplication(), packageName)
        refresh(usagePermissionGranted)
    }

    private fun loadState(): AppBlockingUiState {
        val context = getApplication<Application>()
        val rules = AppBlockSettings.loadRules(context).associateBy { it.packageName }
        val entries = when (val result = AppUsageRepository.load(context)) {
            is AppUsageLoadResult.Success -> result.entries
            AppUsageLoadResult.AccessDenied -> return AppBlockingUiState(
                usagePermissionRequired = true
            )
            AppUsageLoadResult.Unavailable -> emptyList()
        }
        val usageByPackage = entries.associateBy { it.packageName }
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchableApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(
                launcherIntent,
                android.content.pm.PackageManager.ResolveInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(launcherIntent, 0)
        }
        val appsByPackage = launchableApps.asSequence()
            .mapNotNull { resolveInfo ->
                val packageName = resolveInfo.activityInfo?.packageName
                    ?.takeIf { it != context.packageName }
                    ?: return@mapNotNull null
                val usageEntry = usageByPackage[packageName]
                packageName to BlockableAppUi(
                    packageName = packageName,
                    appName = resolveInfo.loadLabel(context.packageManager).toString()
                        .takeIf { it.isNotBlank() }
                        ?: packageName,
                    usageMs = usageEntry?.durationMs ?: 0L,
                    rule = rules[packageName]
                )
            }
            .toMap()
            .toMutableMap()
        rules.forEach { (packageName, rule) ->
            appsByPackage.putIfAbsent(
                packageName,
                BlockableAppUi(
                    packageName = packageName,
                    appName = resolveAppName(context, packageName),
                    usageMs = 0L,
                    rule = rule
                )
            )
        }
        return AppBlockingUiState(
            apps = appsByPackage.values.sortedWith(
                compareByDescending<BlockableAppUi> { it.rule != null }
                    .thenByDescending { it.usageMs }
                    .thenBy { it.appName.lowercase() }
            )
        )
    }

    private fun resolveAppName(application: Application, packageName: String): String = runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.packageManager.getApplicationInfo(
                packageName,
                android.content.pm.PackageManager.ApplicationInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            application.packageManager.getApplicationInfo(packageName, 0)
        }
        application.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)
}

@Composable
internal fun AppBlockingScreen(
    usagePermissionGranted: Boolean,
    accessibilityServiceEnabled: Boolean,
    onOpenPermissions: () -> Unit,
    viewModel: AppBlockingViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var editingApp by remember { mutableStateOf<BlockableAppUi?>(null) }
    var showAccessibilityDisclosure by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val filteredApps = remember(state.apps, searchQuery) {
        filterBlockableApps(state.apps, searchQuery)
    }

    LaunchedEffect(usagePermissionGranted) {
        viewModel.refresh(usagePermissionGranted)
    }
    DisposableEffect(lifecycleOwner, usagePermissionGranted) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh(usagePermissionGranted)
            }
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
                text = stringResource(R.string.app_blocking_heading),
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.app_blocking_description),
                color = Color(0xFF9999B5),
                fontSize = 13.sp
            )
        }

        item {
            BlockingAccessCard(
                enabled = accessibilityServiceEnabled,
                onEnable = { showAccessibilityDisclosure = true }
            )
        }

        when {
            state.usagePermissionRequired -> item {
                BlockingMessageCard(
                    title = stringResource(R.string.app_blocking_usage_required_title),
                    message = stringResource(R.string.app_blocking_usage_required_message),
                    actionLabel = stringResource(R.string.open_permissions),
                    onAction = onOpenPermissions
                )
            }
            state.isLoading && state.apps.isEmpty() -> item {
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
            state.apps.isEmpty() -> item {
                BlockingMessageCard(
                    title = stringResource(R.string.app_blocking_empty_title),
                    message = stringResource(R.string.app_blocking_empty_message)
                )
            }
            else -> {
                item {
                    Text(
                        text = stringResource(R.string.app_blocking_apps_title),
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.app_blocking_search_label)) },
                        placeholder = {
                            Text(stringResource(R.string.app_blocking_search_placeholder))
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = { focusManager.clearFocus() }
                        ),
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
                        BlockingMessageCard(
                            title = stringResource(R.string.app_blocking_search_empty_title),
                            message = stringResource(R.string.app_blocking_search_empty_message)
                        )
                    }
                } else {
                    items(filteredApps, key = { it.packageName }) { app ->
                        BlockableAppRow(app = app, onEdit = { editingApp = app })
                    }
                }
            }
        }
    }

    editingApp?.let { app ->
        AppRuleDialog(
            app = app,
            onDismiss = { editingApp = null },
            onSave = { rule ->
                viewModel.saveRule(rule, usagePermissionGranted)
                editingApp = null
            },
            onRemove = {
                viewModel.removeRule(app.packageName, usagePermissionGranted)
                editingApp = null
            }
        )
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
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK
                        )
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
}

@Composable
private fun BlockingAccessCard(enabled: Boolean, onEnable: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF151526))
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.app_blocking_access_title),
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.app_blocking_access_description),
                    color = Color(0xFF9999B5),
                    fontSize = 13.sp
                )
            }
            Text(
                text = stringResource(
                    if (enabled) R.string.permission_granted else R.string.permission_grant
                ),
                color = if (enabled) Color(0xFF69F0AE) else Color(0xFFFFB0A8),
                fontWeight = FontWeight.Bold
            )
        }
        if (!enabled) {
            Spacer(modifier = Modifier.height(14.dp))
            Button(
                onClick = onEnable,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4488FF))
            ) {
                Text(stringResource(R.string.app_blocking_enable_access))
            }
        }
    }
}

@Composable
private fun BlockableAppRow(app: BlockableAppUi, onEdit: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF151526))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appName,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(
                        R.string.app_blocking_usage_today,
                        formatAppUsageDuration(app.usageMs)
                    ),
                    color = Color(0xFF9999B5),
                    fontSize = 12.sp
                )
            }
            OutlinedButton(onClick = onEdit) {
                Text(
                    stringResource(
                        if (app.rule == null) {
                            R.string.app_blocking_set
                        } else {
                            R.string.app_blocking_edit
                        }
                    )
                )
            }
        }
        app.rule?.let { rule ->
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = ruleSummary(rule),
                color = Color(0xFFAEC2FF),
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun AppRuleDialog(
    app: BlockableAppUi,
    onDismiss: () -> Unit,
    onSave: (AppBlockRule) -> Unit,
    onRemove: () -> Unit
) {
    val existingRule = app.rule
    var limitText by remember(app.packageName) {
        mutableStateOf(existingRule?.dailyLimitMinutes?.toString().orEmpty())
    }
    var blockedSurfaces by remember(app.packageName) {
        mutableStateOf(existingRule?.blockedSurfaces.orEmpty())
    }
    var allowMessages by remember(app.packageName) {
        mutableStateOf(existingRule?.allowMessages ?: true)
    }
    var showValidationError by remember(app.packageName) { mutableStateOf(false) }
    val parsedLimit = limitText.toIntOrNull()
    val validLimit = limitText.isBlank() || parsedLimit in 1..1440
    val surfaceOptions = supportedSurfacesFor(app.packageName)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_blocking_rule_title, app.appName)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.app_blocking_limit_description))
                OutlinedTextField(
                    value = limitText,
                    onValueChange = { value ->
                        limitText = value.filter(Char::isDigit).take(4)
                        showValidationError = false
                    },
                    label = { Text(stringResource(R.string.app_blocking_limit_label)) },
                    singleLine = true,
                    isError = showValidationError && !validLimit,
                    supportingText = if (showValidationError && !validLimit) {
                        { Text(stringResource(R.string.app_blocking_limit_error)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                surfaceOptions.forEach { surface ->
                    RuleSwitch(
                        label = surfaceOptionLabel(surface),
                        checked = surface in blockedSurfaces,
                        onCheckedChange = { checked ->
                            blockedSurfaces = if (checked) {
                                blockedSurfaces + surface
                            } else {
                                blockedSurfaces - surface
                            }
                        }
                    )
                }
                if (app.packageName == INSTAGRAM_PACKAGE) {
                    RuleSwitch(
                        label = stringResource(R.string.app_blocking_allow_messages),
                        checked = allowMessages,
                        onCheckedChange = { allowMessages = it }
                    )
                }
                if (surfaceOptions.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.app_blocking_surface_note),
                        color = Color(0xFF66667A),
                        fontSize = 12.sp
                    )
                }

                if (existingRule != null) {
                    TextButton(onClick = onRemove) {
                        Text(stringResource(R.string.app_blocking_remove_rule))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (!validLimit) {
                    showValidationError = true
                } else {
                    onSave(
                        AppBlockRule(
                            packageName = app.packageName,
                            dailyLimitMinutes = parsedLimit,
                            blockedSurfaces = blockedSurfaces,
                            allowMessages = app.packageName == INSTAGRAM_PACKAGE && allowMessages
                        )
                    )
                }
            }) {
                Text(stringResource(R.string.app_blocking_save_rule))
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
private fun RuleSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun BlockingMessageCard(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF151526))
            .padding(20.dp)
    ) {
        Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(6.dp))
        Text(message, color = Color(0xFF9999B5), fontSize = 13.sp)
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun ruleSummary(rule: AppBlockRule): String = buildList {
    rule.dailyLimitMinutes?.let { add(stringResource(R.string.app_blocking_summary_limit, it)) }
    supportedSurfacesFor(rule.packageName)
        .filter(rule.blockedSurfaces::contains)
        .forEach { add(surfaceSummaryLabel(it)) }
    if (rule.isConfigured && rule.packageName == INSTAGRAM_PACKAGE && rule.allowMessages) {
        add(stringResource(R.string.app_blocking_summary_messages))
    }
}.joinToString(" • ")

@Composable
private fun surfaceOptionLabel(surface: AppSurface): String = stringResource(
    when (surface) {
        AppSurface.SHORTS -> R.string.app_blocking_block_shorts
        AppSurface.VIDEO_SEARCH -> R.string.app_blocking_block_video_search
        AppSurface.PICTURE_IN_PICTURE -> R.string.app_blocking_block_picture_in_picture
        AppSurface.COMMENTS -> R.string.app_blocking_block_comments
        AppSurface.REELS -> R.string.app_blocking_block_reels
        AppSurface.STORIES -> R.string.app_blocking_block_stories
        AppSurface.EXPLORE -> R.string.app_blocking_block_explore
        AppSurface.X_VIDEOS -> R.string.app_blocking_block_x_videos
        AppSurface.MARKETPLACE -> R.string.app_blocking_block_marketplace
        AppSurface.SPOTLIGHT -> R.string.app_blocking_block_spotlight
        AppSurface.MESSAGE_INBOX,
        AppSurface.MESSAGE_THREAD,
        AppSurface.OTHER,
        AppSurface.UNKNOWN -> error("$surface is not a configurable surface")
    }
)

@Composable
private fun surfaceSummaryLabel(surface: AppSurface): String = stringResource(
    when (surface) {
        AppSurface.SHORTS -> R.string.app_blocking_summary_shorts
        AppSurface.VIDEO_SEARCH -> R.string.app_blocking_summary_video_search
        AppSurface.PICTURE_IN_PICTURE -> R.string.app_blocking_summary_picture_in_picture
        AppSurface.COMMENTS -> R.string.app_blocking_summary_comments
        AppSurface.REELS -> R.string.app_blocking_summary_reels
        AppSurface.STORIES -> R.string.app_blocking_summary_stories
        AppSurface.EXPLORE -> R.string.app_blocking_summary_explore
        AppSurface.X_VIDEOS -> R.string.app_blocking_summary_x_videos
        AppSurface.MARKETPLACE -> R.string.app_blocking_summary_marketplace
        AppSurface.SPOTLIGHT -> R.string.app_blocking_summary_spotlight
        AppSurface.MESSAGE_INBOX,
        AppSurface.MESSAGE_THREAD,
        AppSurface.OTHER,
        AppSurface.UNKNOWN -> error("$surface is not a configurable surface")
    }
)
