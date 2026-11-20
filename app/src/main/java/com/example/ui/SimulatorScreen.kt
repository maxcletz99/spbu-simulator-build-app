package com.example.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R

@Composable
fun SimulatorScreen(
    viewModel: SimulatorViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val rootView = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var popupWebView by remember { mutableStateOf<WebView?>(null) }
    var customFullscreenView by remember { mutableStateOf<View?>(null) }
    var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var pendingFileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var lastBackPressMs by remember { mutableLongStateOf(0L) }

    val singleFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        pendingFileCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        pendingFileCallback = null
    }

    val multiFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        pendingFileCallback?.onReceiveValue(if (uris.isNotEmpty()) uris.toTypedArray() else null)
        pendingFileCallback = null
    }

    // Apply orientation lock changes
    LaunchedEffect(uiState.orientationMode, activity) {
        activity?.requestedOrientation = uiState.orientationMode.requestedOrientation
    }

    // Apply immersive fullscreen mode cleanly via WindowInsetsControllerCompat
    LaunchedEffect(uiState.isFullscreen, customFullscreenView, activity, rootView) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, rootView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (uiState.isFullscreen || customFullscreenView != null) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Sync WebView lifecycle with Activity lifecycle
    DisposableEffect(lifecycleOwner, webViewRef) {
        val observer = LifecycleEventObserver { _, event ->
            val wv = webViewRef ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_RESUME -> wv.onResume()
                Lifecycle.Event.ON_PAUSE -> {
                    wv.onPause()
                    CookieManager.getInstance().flush()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Cleanup WebView only when screen permanently leaves composition
    DisposableEffect(Unit) {
        onDispose {
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = null
            popupWebView?.destroy()
            popupWebView = null
            webViewRef?.destroy()
            webViewRef = null
        }
    }

    val closePopup: () -> Unit = {
        popupWebView?.destroy()
        popupWebView = null
    }

    // Intercept system back button / gesture
    BackHandler(enabled = true) {
        when {
            customFullscreenView != null -> {
                customViewCallback?.onCustomViewHidden()
                customFullscreenView = null
                customViewCallback = null
            }
            popupWebView != null -> {
                val p = popupWebView
                if (p?.canGoBack() == true) {
                    p.goBack()
                } else {
                    closePopup()
                }
            }
            uiState.controlsExpanded -> {
                viewModel.setControlsExpanded(false)
            }
            webViewRef?.canGoBack() == true -> {
                webViewRef?.goBack()
            }
            else -> {
                val now = SystemClock.elapsedRealtime()
                if (now - lastBackPressMs < 2000L) {
                    activity?.finish()
                } else {
                    lastBackPressMs = now
                    Toast.makeText(
                        context,
                        context.getString(R.string.press_back_again_to_exit),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val contentPaddingModifier = if (uiState.isFullscreen || customFullscreenView != null) {
            Modifier.fillMaxSize()
        } else {
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        }

        // Primary embedded WebView playing https://app.spbusimulator.online/ directly
        AndroidView(
            modifier = contentPaddingModifier.testTag("simulator_webview"),
            factory = { ctx ->
                WebView(ctx).also { webView ->
                    configureSimulatorWebView(
                        webView = webView,
                        onProgressChanged = viewModel::onProgressChanged,
                        onTitleChanged = viewModel::onPageTitleChanged,
                        onPageStarted = viewModel::onPageStarted,
                        onPageFinished = viewModel::onPageFinished,
                        onNavigationStateChanged = viewModel::onNavigationStateChanged,
                        onPageError = viewModel::onPageError,
                        onShowJsDialog = viewModel::showJsDialog,
                        onFileChooserRequest = { callback, acceptTypes, allowMultiple ->
                            pendingFileCallback?.onReceiveValue(null)
                            pendingFileCallback = callback
                            val mimeType = acceptTypes.firstOrNull()?.takeIf { it.contains("/") } ?: "*/*"
                            if (allowMultiple) {
                                multiFileLauncher.launch(mimeType)
                            } else {
                                singleFileLauncher.launch(mimeType)
                            }
                            true
                        },
                        onShowCustomView = { view, callback ->
                            customViewCallback?.onCustomViewHidden()
                            customFullscreenView = view
                            customViewCallback = callback
                        },
                        onHideCustomView = {
                            customFullscreenView = null
                            customViewCallback = null
                        },
                        onCreateChildWebView = { resultMsg ->
                            popupWebView?.destroy()
                            val child = createPopupWebView(
                                context = ctx,
                                onCloseRequested = {
                                    popupWebView?.destroy()
                                    popupWebView = null
                                },
                            )
                            popupWebView = child
                            val transport = resultMsg.obj as? WebView.WebViewTransport
                            transport?.webView = child
                            resultMsg.sendToTarget()
                        },
                        onRenderProcessGoneCallback = {
                            viewModel.prepareRetry()
                            webView.reload()
                        },
                    )
                    webViewRef = webView
                    webView.loadUrl(uiState.targetUrl)
                }
            },
            update = { webView ->
                webView.keepScreenOn = uiState.keepScreenOn
            },
        )

        // Subtle top progress bar for subsequent in-app navigations after initial load
        if (uiState.hasCompletedInitialLoad && uiState.loadState is SimulatorLoadState.Loading) {
            val progress = (uiState.loadState as SimulatorLoadState.Loading).progress
            LinearProgressIndicator(
                progress = { (progress / 100f).coerceIn(0.05f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
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
            SimulatorLoadingOverlay(progress = progress)
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
                    onRetry = {
                        viewModel.prepareRetry()
                        val wv = webViewRef
                        if (wv != null) {
                            if (wv.url.isNullOrBlank()) {
                                wv.loadUrl(uiState.targetUrl)
                            } else {
                                wv.reload()
                            }
                        }
                    },
                )
            }
        }

        // HTML5 Fullscreen custom view overlay
        customFullscreenView?.let { customView ->
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        addView(
                            customView,
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT,
                            ),
                        )
                    }
                },
            )
        }

        // In-app popup WebView modal for target="_blank" or window.open()
        popupWebView?.let { childWebView ->
            InAppPopupOverlay(
                popupWebView = childWebView,
                onClose = closePopup,
            )
        }

        // Unobtrusive collapsible game controls pill anchored in top-end safe area
        if (customFullscreenView == null && popupWebView == null) {
            GameControlsOverlay(
                uiState = uiState,
                onToggleExpanded = {
                    viewModel.setControlsExpanded(!uiState.controlsExpanded)
                },
                onBack = {
                    webViewRef?.takeIf { it.canGoBack() }?.goBack()
                },
                onHome = {
                    viewModel.prepareRetry()
                    webViewRef?.loadUrl(uiState.targetUrl)
                    viewModel.setControlsExpanded(false)
                },
                onReload = {
                    viewModel.prepareRetry()
                    webViewRef?.reload()
                    viewModel.setControlsExpanded(false)
                },
                onCycleOrientation = viewModel::cycleOrientation,
                onToggleKeepAwake = viewModel::toggleKeepScreenOn,
                onToggleFullscreen = viewModel::toggleFullscreen,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 10.dp, end = 10.dp),
            )
        }
    }

    // JavaScript Alert / Confirm / Prompt Dialogs
    uiState.jsDialog?.let { dialog ->
        JsDialogModal(
            dialog = dialog,
            onDismiss = viewModel::clearJsDialog,
        )
    }
}

@Composable
private fun SimulatorLoadingOverlay(progress: Int) {
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
            .windowInsetsPadding(WindowInsets.safeDrawing)
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
            .windowInsetsPadding(WindowInsets.safeDrawing)
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
private fun GameControlsOverlay(
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
    Column(
        modifier = modifier,
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
private fun InAppPopupOverlay(
    popupWebView: WebView,
    onClose: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        color = Color(0xFF081421),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(
                color = Color(0xFF0F2942),
                tonalElevation = 4.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.popup_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp),
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

            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { popupWebView },
            )
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
