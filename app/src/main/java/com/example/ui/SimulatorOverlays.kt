package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ScreenLockPortrait
import androidx.compose.material.icons.rounded.ScreenLockRotation
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R

@Composable
fun SimulatorStatusOverlay(
    uiState: SimulatorUiState,
    onRetry: () -> Unit,
    onDismissJsDialog: () -> Unit,
) {
    val safeBarInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)

    Box(modifier = Modifier.fillMaxWidth()) {
        // Subtle top progress bar for subsequent in-app navigations after initial load
        if (uiState.hasCompletedInitialLoad && uiState.loadState is SimulatorLoadState.Loading) {
            val progress = uiState.loadState.progress
            LinearProgressIndicator(
                progress = { (progress / 100f).coerceIn(0.05f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(safeBarInsets)
                    .height(3.dp)
                    .align(Alignment.TopCenter),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
            )
        }

        // Branded initial loading overlay
        AnimatedVisibility(
            visible = !uiState.hasCompletedInitialLoad && uiState.loadState is SimulatorLoadState.Loading,
            enter = fadeIn(animationSpec = tween(150)),
            exit = fadeOut(animationSpec = tween(350)),
        ) {
            val progress = (uiState.loadState as? SimulatorLoadState.Loading)?.progress ?: 0
            SimulatorLoadingOverlay(
                progress = progress,
                safeBarInsets = safeBarInsets,
            )
        }

        // Graceful offline / error screen
        val errorState = uiState.loadState as? SimulatorLoadState.Error
        AnimatedVisibility(
            visible = errorState != null,
            enter = fadeIn(animationSpec = tween(250)),
            exit = fadeOut(animationSpec = tween(200)),
        ) {
            if (errorState != null) {
                SimulatorErrorOverlay(
                    errorState = errorState,
                    safeBarInsets = safeBarInsets,
                    onRetry = onRetry,
                )
            }
        }
    }

    // JavaScript Alert / Confirm / Prompt Dialogs (rendered in their own Android Dialog window)
    uiState.jsDialog?.let { dialog ->
        JsDialogModal(
            dialog = dialog,
            onDismiss = onDismissJsDialog,
        )
    }
}

@Composable
fun GameControlsOverlay(
    uiState: SimulatorUiState,
    onToggleExpanded: () -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onReload: () -> Unit,
    onCycleOrientation: () -> Unit,
    onToggleKeepAwake: () -> Unit,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val safeBarInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)

    Column(
        modifier = modifier
            .windowInsetsPadding(safeBarInsets)
            .padding(top = 10.dp, end = 10.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Surface(
            shape = CircleShape,
            color = Color(0xCC0F2942),
            border = BorderStroke(1.dp, Color(0x55FFB703)),
            shadowElevation = 6.dp,
            modifier = Modifier.alpha(if (uiState.isFullscreen && !uiState.controlsExpanded) 0.6f else 1f),
        ) {
            IconButton(
                onClick = onToggleExpanded,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("controls_toggle_button"),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(
                    imageVector = if (uiState.controlsExpanded) {
                        Icons.Rounded.Close
                    } else {
                        Icons.Rounded.Tune
                    },
                    contentDescription = if (uiState.controlsExpanded) {
                        stringResource(R.string.action_controls_collapse)
                    } else {
                        stringResource(R.string.action_controls_expand)
                    },
                )
            }
        }

        AnimatedVisibility(
            visible = uiState.controlsExpanded,
            enter = fadeIn(tween(150)) + scaleIn(
                initialScale = 0.9f,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            ),
            exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.9f),
        ) {
            Surface(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .testTag("controls_panel"),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xEA0F2942),
                border = BorderStroke(1.dp, Color(0xFF33567A)),
                shadowElevation = 10.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    IconButton(
                        onClick = onBack,
                        enabled = uiState.canGoBack,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("control_back_button"),
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = Color.White,
                            disabledContentColor = Color(0xFF5A738E),
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }

                    IconButton(
                        onClick = onHome,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("control_home_button"),
                        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Home,
                            contentDescription = stringResource(R.string.action_home),
                        )
                    }

                    IconButton(
                        onClick = onReload,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("control_reload_button"),
                        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.action_reload),
                        )
                    }

                    val (orientationIcon, orientationLabel) = when (uiState.orientationMode) {
                        OrientationMode.AUTO ->
                            Icons.Rounded.ScreenRotation to stringResource(R.string.action_orientation_auto)
                        OrientationMode.LANDSCAPE ->
                            Icons.Rounded.ScreenLockRotation to stringResource(R.string.action_orientation_landscape)
                        OrientationMode.PORTRAIT ->
                            Icons.Rounded.ScreenLockPortrait to stringResource(R.string.action_orientation_portrait)
                    }
                    IconButton(
                        onClick = onCycleOrientation,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("control_orientation_button"),
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = if (uiState.orientationMode == OrientationMode.AUTO) {
                                Color.White
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        ),
                    ) {
                        Icon(
                            imageVector = orientationIcon,
                            contentDescription = orientationLabel,
                        )
                    }

                    IconButton(
                        onClick = onToggleKeepAwake,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("control_keep_awake_button"),
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = if (uiState.keepScreenOn) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                Color(0xFFAABBD0)
                            },
                        ),
                    ) {
                        Icon(
                            imageVector = if (uiState.keepScreenOn) {
                                Icons.Rounded.Visibility
                            } else {
                                Icons.Rounded.VisibilityOff
                            },
                            contentDescription = stringResource(R.string.action_keep_screen_on),
                        )
                    }

                    IconButton(
                        onClick = onToggleFullscreen,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("control_fullscreen_button"),
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = if (uiState.isFullscreen) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.White
                            },
                        ),
                    ) {
                        Icon(
                            imageVector = if (uiState.isFullscreen) {
                                Icons.Rounded.FullscreenExit
                            } else {
                                Icons.Rounded.Fullscreen
                            },
                            contentDescription = if (uiState.isFullscreen) {
                                stringResource(R.string.action_exit_fullscreen)
                            } else {
                                stringResource(R.string.action_fullscreen)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PopupHeaderBar(
    title: String,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        color = Color(0xFF0F2942),
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("popup_back_button"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = Color.White,
                )
            }
            Text(
                text = title.ifBlank { stringResource(R.string.popup_title) },
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("close_popup_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.popup_close),
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun SimulatorLoadingOverlay(
    progress: Int,
    safeBarInsets: WindowInsets,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pump_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_scale",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF081421),
                        Color(0xFF0F2942),
                        Color(0xFF081421),
                    ),
                ),
            )
            .windowInsetsPadding(safeBarInsets)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 360.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(
                modifier = Modifier
                    .size(88.dp)
                    .scale(pulseScale),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                tonalElevation = 6.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.LocalGasStation,
                        contentDescription = stringResource(R.string.cd_fuel_pump_badge),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.loading_title),
                style = MaterialTheme.typography.headlineMedium,
                color = Color(0xFFF8FAFC),
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.loading_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFAABBD0),
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(28.dp))

            LinearProgressIndicator(
                progress = { (progress / 100f).coerceIn(0.06f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color(0xFF1A3550),
                strokeCap = StrokeCap.Round,
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = stringResource(R.string.loading_progress_format, progress),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SimulatorErrorOverlay(
    errorState: SimulatorLoadState.Error,
    safeBarInsets: WindowInsets,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF081421), Color(0xFF102235)),
                ),
            )
            .windowInsetsPadding(safeBarInsets)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 420.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF102235),
            border = BorderStroke(1.dp, Color(0xFF33567A)),
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    modifier = Modifier.size(64.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.WifiOff,
                            contentDescription = stringResource(R.string.cd_offline_icon),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = stringResource(R.string.error_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color(0xFFF8FAFC),
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (errorState.isOffline) {
                        stringResource(R.string.error_subtitle_offline)
                    } else {
                        stringResource(R.string.error_subtitle_generic, errorState.description)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFAABBD0),
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("retry_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.action_retry),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun JsDialogModal(
    dialog: JsDialogState,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        is JsDialogState.Alert -> {
            AlertDialog(
                onDismissRequest = {
                    dialog.result.confirm()
                    onDismiss()
                },
                title = { Text(stringResource(R.string.dialog_alert_title)) },
                text = { Text(dialog.message) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            dialog.result.confirm()
                            onDismiss()
                        },
                        modifier = Modifier.testTag("js_dialog_ok_button"),
                    ) {
                        Text(stringResource(R.string.dialog_ok))
                    }
                },
            )
        }
        is JsDialogState.Confirm -> {
            AlertDialog(
                onDismissRequest = {
                    dialog.result.cancel()
                    onDismiss()
                },
                title = { Text(stringResource(R.string.dialog_confirm_title)) },
                text = { Text(dialog.message) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            dialog.result.confirm()
                            onDismiss()
                        },
                        modifier = Modifier.testTag("js_dialog_confirm_button"),
                    ) {
                        Text(stringResource(R.string.dialog_ok))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            dialog.result.cancel()
                            onDismiss()
                        },
                        modifier = Modifier.testTag("js_dialog_cancel_button"),
                    ) {
                        Text(stringResource(R.string.dialog_cancel))
                    }
                },
            )
        }
        is JsDialogState.Prompt -> {
            var inputText by remember(dialog) { mutableStateOf(dialog.defaultValue) }
            AlertDialog(
                onDismissRequest = {
                    dialog.result.cancel()
                    onDismiss()
                },
                title = { Text(stringResource(R.string.dialog_prompt_title)) },
                text = {
                    Column {
                        if (dialog.message.isNotBlank()) {
                            Text(
                                text = dialog.message,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("js_dialog_prompt_input"),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            dialog.result.confirm(inputText)
                            onDismiss()
                        },
                        modifier = Modifier.testTag("js_dialog_prompt_ok_button"),
                    ) {
                        Text(stringResource(R.string.dialog_ok))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            dialog.result.cancel()
                            onDismiss()
                        },
                        modifier = Modifier.testTag("js_dialog_prompt_cancel_button"),
                    ) {
                        Text(stringResource(R.string.dialog_cancel))
                    }
                },
            )
        }
    }
}
