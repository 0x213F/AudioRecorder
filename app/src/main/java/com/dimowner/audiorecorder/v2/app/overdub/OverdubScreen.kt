/*
 * Copyright 2026 Dmytro Ponomarenko
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dimowner.audiorecorder.v2.app.overdub

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dimowner.audiorecorder.R
import com.dimowner.audiorecorder.util.TimeUtils
import com.dimowner.audiorecorder.v2.app.components.KeepScreenOn
import com.dimowner.audiorecorder.v2.app.home.CircleButton
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Self-contained overdub screen. Drives [OverdubViewModel] through
 * ARMED → CAPTURING → MIXING → REVIEW and reports the saved record id back to the caller.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OverdubScreen(
    onPopBackStack: () -> Unit,
    onSaved: (Long) -> Unit,
    uiState: OverdubState,
    event: SharedFlow<OverdubEvent>,
    onAction: (OverdubAction) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val msgPermissionDenied = stringResource(R.string.msg_permission_microphone_denied)
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onAction(OverdubAction.OnPermissionGranted)
        } else {
            onAction(OverdubAction.OnPermissionDenied)
            scope.launch { snackbarHostState.showSnackbar(message = msgPermissionDenied) }
        }
    }

    LaunchedEffect(Unit) {
        event.collect { e ->
            when (e) {
                is OverdubEvent.RequestRecordPermission ->
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                is OverdubEvent.Saved -> onSaved(e.recordId)
                is OverdubEvent.Exit -> onPopBackStack()
                is OverdubEvent.ShowError ->
                    scope.launch { snackbarHostState.showSnackbar(message = context.getString(e.messageRes)) }
            }
        }
    }

    // Route hardware back-press through the ViewModel so the discard dialog can intervene.
    BackHandler { onAction(OverdubAction.OnExitClick) }

    if (uiState.stage == OverdubStage.CAPTURING) {
        KeepScreenOn(enabled = true)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.overdub)) },
                navigationIcon = {
                    IconButton(onClick = { onAction(OverdubAction.OnExitClick) }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.btn_cancel),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.overdub_of_record, uiState.baseRecordName),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(8.dp))
            MonitoringChip(uiState.monitoring)
            Spacer(Modifier.size(32.dp))

            when (uiState.stage) {
                OverdubStage.LOADING -> CircularProgressIndicator()

                OverdubStage.ARMED -> {
                    Text(
                        text = stringResource(R.string.overdub_arm_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.size(24.dp))
                    CircleButton(
                        modifier = Modifier.size(96.dp),
                        text = stringResource(R.string.overdub_start),
                        onClick = { onAction(OverdubAction.OnRecordClick) },
                    )
                }

                OverdubStage.CAPTURING -> {
                    TimeReadout(uiState.positionMills, uiState.baseDurationMills)
                    Spacer(Modifier.size(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = { onAction(OverdubAction.OnRedoClick) }) {
                            Text(stringResource(R.string.overdub_redo))
                        }
                        CircleButton(
                            modifier = Modifier.size(96.dp),
                            text = stringResource(R.string.overdub_stop_and_mix),
                            onClick = { onAction(OverdubAction.OnStopClick) },
                        )
                    }
                }

                OverdubStage.MIXING, OverdubStage.SAVING -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.size(16.dp))
                    Text(stringResource(R.string.overdub_mixing))
                }

                OverdubStage.REVIEW -> {
                    TimeReadout(uiState.positionMills, uiState.baseDurationMills)
                    Spacer(Modifier.size(16.dp))
                    IconButton(
                        onClick = { onAction(OverdubAction.OnToggleReviewPlay) },
                        modifier = Modifier.size(64.dp),
                    ) {
                        Icon(
                            painter = painterResource(
                                id = if (uiState.isReviewPlaying) R.drawable.ic_pause else R.drawable.ic_play
                            ),
                            contentDescription = stringResource(R.string.playback),
                            modifier = Modifier.size(48.dp),
                        )
                    }
                    Spacer(Modifier.size(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = { onAction(OverdubAction.OnRetryClick) }) {
                            Text(stringResource(R.string.overdub_retry))
                        }
                        CircleButton(
                            modifier = Modifier.size(80.dp),
                            text = stringResource(R.string.overdub_save),
                            onClick = { onAction(OverdubAction.OnSaveClick) },
                        )
                        IconButton(onClick = { onAction(OverdubAction.OnDiscardClick) }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_delete_forever),
                                contentDescription = stringResource(R.string.delete),
                            )
                        }
                    }
                }
            }
        }
    }

    if (uiState.showHeadphoneDialog) {
        HeadphoneDialog(
            onConfirm = { dontAsk -> onAction(OverdubAction.ConfirmHeadphoneDialog(dontAsk)) },
            onDismiss = { onAction(OverdubAction.DismissHeadphoneDialog) },
        )
    }
    if (uiState.showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { onAction(OverdubAction.DismissDiscardDialog) },
            title = { Text(stringResource(R.string.overdub_discard_title)) },
            text = { Text(stringResource(R.string.overdub_discard_body)) },
            confirmButton = {
                TextButton(onClick = { onAction(OverdubAction.ConfirmDiscard) }) {
                    Text(stringResource(R.string.overdub_discard_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(OverdubAction.DismissDiscardDialog) }) {
                    Text(stringResource(R.string.overdub_discard_keep))
                }
            },
        )
    }
}

@Composable
private fun MonitoringChip(route: MonitoringRoute) {
    val text = when (route) {
        MonitoringRoute.HEADPHONES -> stringResource(R.string.overdub_monitor_headphones)
        MonitoringRoute.BLUETOOTH -> stringResource(R.string.overdub_monitor_bluetooth)
        MonitoringRoute.SPEAKER -> stringResource(R.string.overdub_monitor_speaker)
    }
    val color = if (route == MonitoringRoute.SPEAKER) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    Text(text = text, color = color, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun TimeReadout(positionMills: Long, durationMills: Long) {
    Text(
        text = TimeUtils.formatTimeIntervalHourMinSec2(positionMills),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.size(8.dp))
    val progress = if (durationMills > 0) {
        (positionMills.toFloat() / durationMills.toFloat()).coerceIn(0f, 1f)
    } else 0f
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun HeadphoneDialog(
    onConfirm: (dontAskAgain: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var dontAsk by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.overdub_headphone_title)) },
        text = {
            Column {
                Text(stringResource(R.string.overdub_headphone_body))
                Spacer(Modifier.size(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = dontAsk, onCheckedChange = { dontAsk = it })
                    Text(stringResource(R.string.overdub_headphone_dont_ask))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(dontAsk) }) {
                Text(stringResource(R.string.overdub_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        },
    )
}
