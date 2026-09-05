package com.boringutils.timehud.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boringutils.timehud.R
import com.boringutils.timehud.ui.theme.TimeHudColors
import kotlinx.coroutines.launch

enum class TimeHudDestination(@param:StringRes val titleRes: Int) {
    GOALS(R.string.nav_goals),
    APP_USAGE(R.string.nav_app_usage),
    BRICK_MODE(R.string.nav_brick_mode),
    APP_LIMITS(R.string.nav_app_limits),
    PERMISSIONS(R.string.nav_permissions)
}

@Composable
fun TimeHudDrawerScaffold(
    selectedDestination: TimeHudDestination,
    onDestinationSelected: (TimeHudDestination) -> Unit,
    content: @Composable () -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    fun selectDestination(destination: TimeHudDestination) {
        onDestinationSelected(destination)
        coroutineScope.launch { drawerState.close() }
    }

    BackHandler(enabled = drawerState.isOpen || selectedDestination != TimeHudDestination.GOALS) {
        if (drawerState.isOpen) {
            coroutineScope.launch { drawerState.close() }
        } else {
            onDestinationSelected(TimeHudDestination.GOALS)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.widthIn(max = 320.dp),
                drawerContainerColor = TimeHudColors.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(horizontal = 14.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 14.dp, vertical = 24.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.app_name),
                            color = TimeHudColors.textPrimary,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = stringResource(R.string.nav_subtitle),
                            color = TimeHudColors.textSecondary,
                            fontSize = 12.sp
                        )
                    }

                    DrawerItem(
                        destination = TimeHudDestination.GOALS,
                        selectedDestination = selectedDestination,
                        onClick = { selectDestination(TimeHudDestination.GOALS) }
                    )
                    DrawerItem(
                        destination = TimeHudDestination.APP_USAGE,
                        selectedDestination = selectedDestination,
                        onClick = { selectDestination(TimeHudDestination.APP_USAGE) }
                    )
                    DrawerItem(
                        destination = TimeHudDestination.BRICK_MODE,
                        selectedDestination = selectedDestination,
                        onClick = { selectDestination(TimeHudDestination.BRICK_MODE) }
                    )
                    DrawerItem(
                        destination = TimeHudDestination.APP_LIMITS,
                        selectedDestination = selectedDestination,
                        onClick = { selectDestination(TimeHudDestination.APP_LIMITS) }
                    )

                    Spacer(modifier = Modifier.weight(1f))
                    HorizontalDivider(color = TimeHudColors.borderSubtle)
                    Spacer(modifier = Modifier.height(8.dp))
                    DrawerItem(
                        destination = TimeHudDestination.PERMISSIONS,
                        selectedDestination = selectedDestination,
                        onClick = { selectDestination(TimeHudDestination.PERMISSIONS) }
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(TimeHudColors.background)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(64.dp)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HamburgerButton(onClick = { coroutineScope.launch { drawerState.open() } })
                Text(
                    text = stringResource(selectedDestination.titleRes),
                    color = TimeHudColors.textPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Box(modifier = Modifier.fillMaxSize()) {
                content()
            }
        }
    }
}

@Composable
private fun DrawerItem(
    destination: TimeHudDestination,
    selectedDestination: TimeHudDestination,
    onClick: () -> Unit
) {
    NavigationDrawerItem(
        label = { Text(stringResource(destination.titleRes)) },
        selected = destination == selectedDestination,
        onClick = onClick,
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = TimeHudColors.surfaceSelected,
            unselectedContainerColor = Color.Transparent,
            selectedTextColor = TimeHudColors.textPrimary,
            unselectedTextColor = TimeHudColors.textSecondary
        ),
        modifier = Modifier.padding(vertical = 3.dp)
    )
}

@Composable
private fun HamburgerButton(onClick: () -> Unit) {
    val description = stringResource(R.string.open_navigation_menu)
    IconButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = description }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .width(22.dp)
                        .height(2.dp)
                        .background(TimeHudColors.textPrimary)
                )
            }
        }
    }
}
