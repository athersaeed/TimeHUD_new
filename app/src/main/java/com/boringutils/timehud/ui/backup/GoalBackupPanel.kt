package com.boringutils.timehud.ui.backup

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boringutils.timehud.R
import com.boringutils.timehud.ui.theme.TimeHudColors

@Composable
fun GoalBackupPanel(
    isBusy: Boolean,
    @StringRes statusMessageRes: Int?,
    statusIsError: Boolean?,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TimeHudColors.surface)
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.goal_backup_title),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = TimeHudColors.textPrimary
        )
        Text(
            text = stringResource(R.string.goal_backup_description),
            fontSize = 12.sp,
            color = TimeHudColors.textSecondary
        )

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onExport,
                enabled = !isBusy,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TimeHudColors.textEmphasis)
            ) {
                Text(stringResource(R.string.goal_backup_export), fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = onImport,
                enabled = !isBusy,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TimeHudColors.textEmphasis)
            ) {
                Text(stringResource(R.string.goal_backup_import), fontSize = 13.sp)
            }
        }

        statusMessageRes?.let { messageRes ->
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(messageRes),
                fontSize = 12.sp,
                color = when (statusIsError) {
                    true -> TimeHudColors.statusDestructive
                    false -> TimeHudColors.statusPositive
                    null -> TimeHudColors.textEmphasis
                }
            )
        }
    }
}

@Composable
fun GoalImportConfirmationDialog(
    shortTermGoalCount: Int,
    longTermGoalCount: Int,
    onReplace: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.goal_backup_confirm_title)) },
        text = {
            Column {
                Text(stringResource(R.string.goal_backup_short_count, shortTermGoalCount))
                Text(stringResource(R.string.goal_backup_long_count, longTermGoalCount))
                Spacer(modifier = Modifier.height(10.dp))
                Text(stringResource(R.string.goal_backup_replace_warning))
            }
        },
        confirmButton = {
            TextButton(onClick = onReplace) {
                Text(stringResource(R.string.goal_backup_replace))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.goal_backup_cancel))
            }
        }
    )
}
