package com.boringutils.timehud.ui.usage

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class AppUsageError {
    PERMISSION_REQUIRED,
    UNAVAILABLE
}

internal data class AppUsageUiState(
    val isLoading: Boolean = false,
    val entries: List<AppUsageEntry> = emptyList(),
    val periodStartMs: Long? = null,
    val error: AppUsageError? = null
)

internal class AppUsageViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(AppUsageUiState())
    val uiState = _uiState.asStateFlow()
    private var refreshJob: Job? = null

    fun refresh(permissionGranted: Boolean) {
        refreshJob?.cancel()
        if (!permissionGranted) {
            _uiState.value = AppUsageUiState(error = AppUsageError.PERMISSION_REQUIRED)
            return
        }

        refreshJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val result = withContext(Dispatchers.IO) {
                AppUsageRepository.load(getApplication())
            }
            _uiState.value = when (result) {
                is AppUsageLoadResult.Success -> AppUsageUiState(
                    entries = result.entries,
                    periodStartMs = result.periodStartMs
                )
                AppUsageLoadResult.AccessDenied -> AppUsageUiState(
                    error = AppUsageError.PERMISSION_REQUIRED
                )
                AppUsageLoadResult.Unavailable -> AppUsageUiState(
                    error = AppUsageError.UNAVAILABLE
                )
            }
        }
    }
}

@Composable
internal fun AppUsageScreen(
    usagePermissionGranted: Boolean,
    onOpenPermissions: () -> Unit,
    viewModel: AppUsageViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

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
            AppUsageHeader(
                isRefreshing = state.isLoading && state.entries.isNotEmpty(),
                onRefresh = { viewModel.refresh(usagePermissionGranted) }
            )
        }

        when {
            state.isLoading && state.entries.isEmpty() -> item { LoadingUsageCard() }
            state.error == AppUsageError.PERMISSION_REQUIRED -> item {
                UsageMessageCard(
                    title = stringResource(R.string.app_usage_permission_title),
                    message = stringResource(R.string.app_usage_permission_message),
                    actionLabel = stringResource(R.string.open_permissions),
                    onAction = onOpenPermissions
                )
            }
            state.error == AppUsageError.UNAVAILABLE -> item {
                UsageMessageCard(
                    title = stringResource(R.string.app_usage_unavailable_title),
                    message = stringResource(R.string.app_usage_unavailable_message),
                    actionLabel = stringResource(R.string.retry),
                    onAction = { viewModel.refresh(usagePermissionGranted) }
                )
            }
            state.entries.isEmpty() -> item {
                UsageMessageCard(
                    title = stringResource(R.string.app_usage_empty_title),
                    message = stringResource(R.string.app_usage_empty_message)
                )
            }
            else -> {
                item { AppUsageSummary(entries = state.entries) }
                item {
                    Text(
                        text = stringResource(R.string.app_usage_top_apps),
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                item { AppUsageChart(entries = state.entries.take(MAX_CHART_APPS)) }
                item {
                    Text(
                        text = stringResource(R.string.app_usage_all_apps),
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                items(
                    items = state.entries,
                    key = { entry -> entry.packageName }
                ) { entry ->
                    AppUsageRow(
                        entry = entry,
                        longestDurationMs = state.entries.first().durationMs
                    )
                }
            }
        }
    }
}

@Composable
private fun AppUsageHeader(isRefreshing: Boolean, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.app_usage_heading),
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.app_usage_period),
                color = Color(0xFF8888AA),
                fontSize = 13.sp
            )
        }
        OutlinedButton(onClick = onRefresh, enabled = !isRefreshing) {
            Text(stringResource(R.string.refresh))
        }
    }
}

@Composable
private fun LoadingUsageCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF151526)),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = Color(0xFF668DFF))
    }
}

@Composable
private fun UsageMessageCard(
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
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4488FF))
            ) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun AppUsageSummary(entries: List<AppUsageEntry>) {
    val totalDuration = entries.sumOf { it.durationMs }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF151526))
            .padding(18.dp)
    ) {
        Text(
            text = formatAppUsageDuration(totalDuration),
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.app_usage_total, entries.size),
            color = Color(0xFF8888AA),
            fontSize = 13.sp
        )
    }
}

@Composable
private fun AppUsageChart(entries: List<AppUsageEntry>) {
    val longestDuration = entries.firstOrNull()?.durationMs ?: 1L
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF151526))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        entries.forEach { entry ->
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.appName,
                        modifier = Modifier.weight(1f),
                        color = Color.White,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = formatAppUsageDuration(entry.durationMs),
                        color = Color(0xFFAEC2FF),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                UsageBar(
                    fraction = (entry.durationMs.toFloat() / longestDuration).coerceIn(0f, 1f),
                    description = stringResource(
                        R.string.app_usage_bar_description,
                        entry.appName,
                        formatAppUsageDuration(entry.durationMs)
                    )
                )
            }
        }
    }
}

@Composable
private fun AppUsageRow(entry: AppUsageEntry, longestDurationMs: Long) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF151526))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF28335C)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = entry.appName.firstOrNull()?.uppercase() ?: "?",
                    color = Color(0xFFAEC2FF),
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.size(12.dp))
            Text(
                text = entry.appName,
                modifier = Modifier.weight(1f),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatAppUsageDuration(entry.durationMs),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        UsageBar(
            fraction = (entry.durationMs.toFloat() / longestDurationMs.coerceAtLeast(1L))
                .coerceIn(0f, 1f),
            description = stringResource(
                R.string.app_usage_bar_description,
                entry.appName,
                formatAppUsageDuration(entry.durationMs)
            )
        )
    }
}

@Composable
private fun UsageBar(fraction: Float, description: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF29293D))
            .semantics { contentDescription = description }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceAtLeast(MIN_VISIBLE_BAR_FRACTION))
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF668DFF))
        )
    }
}

private const val MAX_CHART_APPS = 5
private const val MIN_VISIBLE_BAR_FRACTION = 0.015f
