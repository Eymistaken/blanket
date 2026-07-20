package com.example.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.R
import com.example.audio.SoundType
import com.example.data.Preset
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlanketApp(
    viewModel: BlanketViewModel,
    onRetryServiceBind: () -> Unit = {}
) {
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val activeSoundIds by viewModel.activeSoundIds.collectAsStateWithLifecycle()
    val soundItems by viewModel.soundItems.collectAsStateWithLifecycle()
    val sleepTimerRemainingMs by viewModel.sleepTimerRemainingMs.collectAsStateWithLifecycle()
    val sleepTimerTotalMs by viewModel.sleepTimerTotalMs.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val highPrecisionSoundId by viewModel.highPrecisionSoundId.collectAsStateWithLifecycle()
    val serviceBindError by viewModel.serviceBindError.collectAsStateWithLifecycle()
    val userMessage by viewModel.userMessage.collectAsStateWithLifecycle()
    val pendingImport by viewModel.pendingImport.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(serviceBindError) {
        val errorMsg = serviceBindError
        if (errorMsg != null) {
            val result = snackbarHostState.showSnackbar(
                message = errorMsg,
                actionLabel = "Tekrar Dene",
                duration = SnackbarDuration.Indefinite
            )
            if (result == SnackbarResult.ActionPerformed) {
                onRetryServiceBind()
            }
        }
    }

    LaunchedEffect(userMessage) {
        val msg = userMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(
                message = msg,
                duration = SnackbarDuration.Short
            )
            viewModel.clearUserMessage()
        }
    }

    val activeSoundsCount = activeSoundIds.size
    val isHighLoad = activeSoundsCount >= 5

    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember { mutableStateOf(true) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            isResumed = event.targetState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val sharedTransition = rememberInfiniteTransition(label = "SharedAnimationTransition")
    val sharedProgress by if (isResumed && !isHighLoad) {
        sharedTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(3000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "SharedAnimationProgress"
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    var showTimerDialog by remember { mutableStateOf(false) }
    var showPresetDialog by remember { mutableStateOf(false) }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var savePresetName by remember { mutableStateOf("") }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.importSound(uri)
        }
    }

    // Visual Palette: Adapts dynamically to the system palette (Material You)
    val darkBlueBackground = MaterialTheme.colorScheme.background
    val indigoSurface = MaterialTheme.colorScheme.surfaceVariant
    val softTeal = MaterialTheme.colorScheme.primary
    val radiantIndigo = MaterialTheme.colorScheme.primaryContainer
    val softGreyText = MaterialTheme.colorScheme.onSurfaceVariant

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.logo),
                            contentDescription = "Blanket Logo",
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Blanket",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = darkBlueBackground
                )
            )
        },
        containerColor = darkBlueBackground
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(darkBlueBackground, MaterialTheme.colorScheme.surface)
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 600.dp)
                        .weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Master Control Card
                    MasterControlPanel(
                        isPlaying = isPlaying,
                        activeSoundsCount = activeSoundIds.size,
                        sleepTimerRemainingMs = sleepTimerRemainingMs,
                        sleepTimerTotalMs = sleepTimerTotalMs,
                        onPlayPauseToggle = { viewModel.togglePlayPause() },
                        onStopAll = { viewModel.stopAll() },
                        onTimerClick = { showTimerDialog = true },
                        onTimerCancel = { viewModel.cancelSleepTimer() },
                        onPresetClick = { showPresetDialog = true },
                        onSavePresetClick = { showSavePresetDialog = true },
                        themeColors = Triple(indigoSurface, softTeal, radiantIndigo)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Soundboard Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Ses Panosu",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                        if (isPlaying && activeSoundIds.isNotEmpty()) {
                            AudioEqualizerAnimation(
                                color = softTeal,
                                sharedProgress = sharedProgress,
                                isResumed = isResumed
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Sounds Grid
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(soundItems, key = { it.id }) { soundItem ->
                            val soundStateFlow = remember(soundItem.id) {
                                viewModel.soundState(soundItem.id)
                            }
                            SoundCard(
                                soundItem = soundItem,
                                soundStateFlow = soundStateFlow,
                                onVolumeChange = { vol -> viewModel.setVolume(soundItem.id, vol) },
                                onToggleActive = { viewModel.toggleSoundActive(soundItem.id) },
                                activeColor = radiantIndigo,
                                surfaceColor = indigoSurface,
                                accentColor = softTeal,
                                viewModel = viewModel,
                                sharedProgress = sharedProgress,
                                isHighLoad = isHighLoad,
                                isResumed = isResumed
                            )
                        }

                        // Dashed "Add Custom Sound" card
                        item(key = "add_custom_sound") {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(130.dp)
                                    .clip(RoundedCornerShape(28.dp))
                                    .clickable { filePickerLauncher.launch("audio/*") },
                                shape = RoundedCornerShape(28.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                ),
                                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Add,
                                        contentDescription = "Ses Ekle",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Özel Ses Ekle",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // Sleep Timer Dialog
            if (showTimerDialog) {
                SleepTimerDialog(
                    remainingMs = sleepTimerRemainingMs,
                    onDismiss = { showTimerDialog = false },
                    onSelectDuration = { minutes ->
                        viewModel.startSleepTimer(minutes)
                        showTimerDialog = false
                    },
                    onCancelTimer = {
                        viewModel.cancelSleepTimer()
                        showTimerDialog = false
                    },
                    dialogBackground = MaterialTheme.colorScheme.surface,
                    accentColor = softTeal
                )
            }

            // Presets List Dialog
            if (showPresetDialog) {
                PresetsListDialog(
                    presets = presets,
                    onDismiss = { showPresetDialog = false },
                    onLoadPreset = { preset ->
                        viewModel.loadPreset(preset)
                        showPresetDialog = false
                    },
                    onDeletePreset = { preset ->
                        viewModel.deletePreset(preset)
                    },
                    dialogBackground = MaterialTheme.colorScheme.surface,
                    accentColor = softTeal,
                    textColor = softGreyText
                )
            }

            // Save Preset Dialog
            if (showSavePresetDialog) {
                SavePresetDialog(
                    presetName = savePresetName,
                    onPresetNameChange = { savePresetName = it },
                    onDismiss = {
                        showSavePresetDialog = false
                        savePresetName = ""
                    },
                    onSave = {
                        if (savePresetName.isNotBlank()) {
                            viewModel.savePreset(savePresetName)
                            showSavePresetDialog = false
                            savePresetName = ""
                        }
                    },
                    dialogBackground = MaterialTheme.colorScheme.surface,
                    accentColor = softTeal
                )
            }

            // Option B Overwrite Confirmation Dialog for Custom Sound Import
            if (pendingImport != null) {
                AlertDialog(
                    onDismissRequest = { viewModel.cancelImport() },
                    title = {
                        Text(
                            text = "Dosya Zaten Var",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    text = {
                        Text(
                            text = "'${pendingImport?.cleanName}' isimli özel ses zaten mevcut. Üzerine yazmak istediğinize emin misiniz?",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { viewModel.confirmOverwriteImport() }) {
                            Text(
                                text = "Üzerine Yaz",
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.cancelImport() }) {
                            Text(text = "İptal")
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surface
                )
            }

            // High-precision Dim Background Overlay
            AnimatedVisibility(
                visible = highPrecisionSoundId != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                )
            }

            // High-precision Fine Tuning Card overlay (Spans 2 columns / full screen width)
            AnimatedVisibility(
                visible = highPrecisionSoundId != null,
                enter = fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) +
                        scaleIn(initialScale = 0.85f, animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)),
                exit = fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) +
                        scaleOut(targetScale = 0.85f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)),
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
            ) {
                val soundId = highPrecisionSoundId ?: return@AnimatedVisibility
                val sound = soundItems.find { it.id == soundId } ?: return@AnimatedVisibility
                
                // Collect volume flow locally to avoid root-level recomposition on sliders drag
                val volumeFlow = remember(soundId) {
                    viewModel.currentVolumes.map { it[soundId] ?: 0f }.distinctUntilChanged()
                }
                val volume by volumeFlow.collectAsState(initial = viewModel.currentVolumes.value[soundId] ?: 0f)

                // Smooth spring progress inside overlay
                val overlayAnimatedVolume by animateFloatAsState(
                    targetValue = volume,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )

                val tickColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)

                Card(
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .fillMaxWidth()
                        .height(140.dp)
                        .shadow(24.dp, shape = RoundedCornerShape(28.dp), spotColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Saturated liquid volume progress fill (spring animated)
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fraction = overlayAnimatedVolume)
                                .background(MaterialTheme.colorScheme.primaryContainer)
                        )

                        // Vertical ruler grid tick marks (Çizgiler)
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val tickCount = 21 // Ticks every 5%
                            val tickSpacing = size.width / (tickCount - 1)
                            for (i in 0 until tickCount) {
                                val x = i * tickSpacing
                                val isMajor = i % 5 == 0
                                val heightFraction = if (isMajor) 0.25f else 0.15f
                                val yStart = size.height * (1f - heightFraction)
                                drawLine(
                                    color = tickColor,
                                    start = Offset(x, yStart),
                                    end = Offset(x, size.height),
                                    strokeWidth = (if (isMajor) 2.dp else 1.dp).toPx()
                                )
                            }
                        }

                        // Content Overlay
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val customPainter = when (sound.id) {
                                        "rain" -> painterResource(id = R.drawable.ic_custom_rain)
                                        "birds" -> painterResource(id = R.drawable.ic_custom_bird)
                                        else -> null
                                    }
                                    if (customPainter != null) {
                                        Icon(
                                            painter = customPainter,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    } else {
                                        Icon(
                                            imageVector = if (sound.isCustom) Icons.Rounded.MusicNote else getIconForId(sound.id),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text(
                                        text = sound.label,
                                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Hassas Ayar Modu (Ayarlamak için kaydırın)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "%1 hassasiyetle ses seviyesi ayarı",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                                Text(
                                    text = "${(volume * 100).roundToInt()}%",
                                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MasterControlPanel(
    isPlaying: Boolean,
    activeSoundsCount: Int,
    sleepTimerRemainingMs: Long,
    sleepTimerTotalMs: Long,
    onPlayPauseToggle: () -> Unit,
    onStopAll: () -> Unit,
    onTimerClick: () -> Unit,
    onTimerCancel: () -> Unit,
    onPresetClick: () -> Unit,
    onSavePresetClick: () -> Unit,
    themeColors: Triple<Color, Color, Color>
) {
    val (surfaceColor, accentColor, primaryColor) = themeColors
    val panelBgColor = MaterialTheme.colorScheme.surfaceVariant
    val lavenderAccent = MaterialTheme.colorScheme.primary
    val deepVioletText = MaterialTheme.colorScheme.onPrimary
    val textLightColor = MaterialTheme.colorScheme.onSurface

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(16.dp, shape = RoundedCornerShape(28.dp)),
        colors = CardDefaults.cardColors(containerColor = panelBgColor),
        shape = RoundedCornerShape(28.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Master Action Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Preset Loader
                IconButton(
                    onClick = onPresetClick,
                    modifier = Modifier
                        .size(48.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Bookmarks,
                        contentDescription = "Saved Presets",
                        tint = textLightColor
                    )
                }

                // Main Play/Pause Button
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onPlayPauseToggle)
                        .background(lavenderAccent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Pause All" else "Play All",
                        tint = deepVioletText,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Preset Saver
                IconButton(
                    onClick = onSavePresetClick,
                    enabled = activeSoundsCount > 0,
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            if (activeSoundsCount > 0) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.01f),
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.BookmarkAdd,
                        contentDescription = "Save Mix",
                        tint = if (activeSoundsCount > 0) textLightColor else textLightColor.copy(alpha = 0.25f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Text Status Info
            val statusText = when {
                isPlaying && activeSoundsCount > 0 -> "$activeSoundsCount ortam sesi oynatılıyor"
                isPlaying && activeSoundsCount == 0 -> "Oynatmayı başlatmak için ses seçin"
                else -> "Oynatma duraklatıldı"
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium,
                    color = textLightColor
                )
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Divider
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), thickness = 1.dp)

            Spacer(modifier = Modifier.height(18.dp))

            // Sleep Timer / Reset Info Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Sleep Timer Display
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onTimerClick)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Timer,
                        contentDescription = "Uyku Zamanlayıcı",
                        tint = if (sleepTimerRemainingMs > 0) lavenderAccent else textLightColor.copy(alpha = 0.6f),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (sleepTimerRemainingMs > 0) {
                            val minutes = sleepTimerRemainingMs / 60000
                            val seconds = (sleepTimerRemainingMs % 60000) / 1000
                            String.format("%02d:%02d", minutes, seconds)
                        } else {
                            "Uyku Zamanlayıcı"
                        },
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = if (sleepTimerRemainingMs > 0) lavenderAccent else textLightColor
                        )
                    )
                    if (sleepTimerRemainingMs > 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Zamanlayıcıyı İptal Et",
                            tint = Color.Red.copy(alpha = 0.8f),
                            modifier = Modifier
                                .size(14.dp)
                                .clickable { onTimerCancel() }
                        )
                    }
                }

                // Reset Button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onStopAll)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.RestartAlt,
                        contentDescription = "Tümünü Durdur",
                        tint = Color.Red.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Sıfırla",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = textLightColor
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun SoundCard(
    soundItem: SoundItem,
    soundStateFlow: StateFlow<SoundState>,
    onVolumeChange: (Float) -> Unit,
    onToggleActive: () -> Unit,
    activeColor: Color,
    surfaceColor: Color,
    accentColor: Color,
    viewModel: BlanketViewModel,
    sharedProgress: Float = 0f,
    isHighLoad: Boolean = false,
    isResumed: Boolean = true
) {
    val soundState by soundStateFlow.collectAsStateWithLifecycle()
    val volume = soundState.volume
    val isActive = soundState.isActive
    var cardWidth by remember { mutableStateOf(1) }

    var localVolume by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(volume) {
        if (!isDragging) {
            localVolume = volume
        }
    }

    // Adapt background volume animation based on system load (spring for normal, lightweight tween for high load)
    val volumeAnimSpec: AnimationSpec<Float> = remember(isHighLoad) {
        if (isHighLoad) {
            tween(durationMillis = 120, easing = FastOutSlowInEasing)
        } else {
            spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        }
    }

    val animatedVolume by animateFloatAsState(
        targetValue = localVolume,
        animationSpec = volumeAnimSpec
    )

    // Gentle pulsating breath animation on active icons phase-shifted from single shared progress source
    val pulseScale = remember(isActive, isHighLoad, isResumed, sharedProgress, soundItem.id) {
        if (isActive && !isHighLoad && isResumed) {
            val phase = (soundItem.id.hashCode() and 0x7FFFFFFF) % 100 / 100f
            val shiftedProgress = (sharedProgress + phase) % 1f
            val sineWave = sin(shiftedProgress * 2 * Math.PI.toFloat())
            1.0f + sineWave * 0.06f
        } else {
            1.0f
        }
    }

    val cardBackground = MaterialTheme.colorScheme.surfaceVariant
    // Saturated solid primaryContainer color (looks rich and full, no dilution)
    val progressColor = MaterialTheme.colorScheme.primaryContainer
    val contentColor = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val contentAlpha = if (isActive) 1f else 0.7f

    val haptic = LocalHapticFeedback.current
    var lastTickType by remember { mutableStateOf(-1) } // -1 = none, 0 = 0.0, 1 = 0.5, 2 = 1.0
    var lastHapticTime by remember { mutableLongStateOf(0L) }
    var lastHapticVolume by remember { mutableFloatStateOf(0f) }

    // Capture state references without cancelling gesture scope
    val currentIsActive by rememberUpdatedState(isActive)
    val currentVolume by rememberUpdatedState(volume)
    val currentOnVolumeChange by rememberUpdatedState(onVolumeChange)
    val currentOnToggleActive by rememberUpdatedState(onToggleActive)

    var dragAccumulator by remember { mutableStateOf(0f) }
    var fineDragAccumulator by remember { mutableStateOf(0f) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(130.dp)
            .shadow(
                elevation = if (isActive && !isHighLoad) 6.dp else 0.dp,
                shape = RoundedCornerShape(28.dp),
                spotColor = MaterialTheme.colorScheme.primary
            )
            .clip(RoundedCornerShape(28.dp))
            .onGloballyPositioned { coordinates ->
                cardWidth = coordinates.size.width
            }
            .pointerInput(soundItem.id) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        isDragging = true
                        // Auto-activate card if dragged while inactive
                        if (!viewModel.activeSoundIds.value.contains(soundItem.id)) {
                            viewModel.toggleSoundActive(soundItem.id)
                            localVolume = viewModel.currentVolumes.value[soundItem.id] ?: 0.5f
                        }
                        dragAccumulator = localVolume
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        // Ensure card is active
                        if (!viewModel.activeSoundIds.value.contains(soundItem.id)) {
                            viewModel.toggleSoundActive(soundItem.id)
                            localVolume = viewModel.currentVolumes.value[soundItem.id] ?: 0.5f
                            dragAccumulator = localVolume
                        }
                        
                        // Relative delta-based dragging with 1.8f sensitivity factor
                        val dragFraction = dragAmount / cardWidth
                        val sensitivity = 1.8f
                        dragAccumulator = (dragAccumulator + dragFraction * sensitivity).coerceIn(0f, 1f)
                        
                        val snappedVal = when {
                            dragAccumulator < 0.03f -> 0f
                            dragAccumulator in 0.47f..0.53f -> 0.5f
                            dragAccumulator > 0.97f -> 1.0f
                            else -> dragAccumulator
                        }

                        // Time-throttled snap point haptic trigger (max once per 50ms)
                        val currentTickType = when (snappedVal) {
                            0f -> 0
                            0.5f -> 1
                            1.0f -> 2
                            else -> -1
                        }
                        if (currentTickType != -1 && currentTickType != lastTickType) {
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - lastHapticTime >= 50L) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                lastHapticTime = now
                                lastHapticVolume = snappedVal
                            }
                        }
                        lastTickType = currentTickType

                        localVolume = snappedVal
                        viewModel.setVolume(soundItem.id, snappedVal, immediate = false)
                    },
                    onDragEnd = {
                        isDragging = false
                        viewModel.setVolume(soundItem.id, localVolume, immediate = true)
                    },
                    onDragCancel = {
                        isDragging = false
                        viewModel.setVolume(soundItem.id, localVolume, immediate = true)
                    }
                )
            }
            .pointerInput(soundItem.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        isDragging = true
                        if (!viewModel.activeSoundIds.value.contains(soundItem.id)) {
                            viewModel.toggleSoundActive(soundItem.id)
                            localVolume = viewModel.currentVolumes.value[soundItem.id] ?: 0.5f
                        }
                        // Start high-precision mode
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        lastHapticTime = android.os.SystemClock.uptimeMillis()
                        lastHapticVolume = localVolume
                        viewModel.setHighPrecisionMode(soundItem.id, true)
                        fineDragAccumulator = localVolume
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        
                        // Finer relative dragging: sensitivity = 0.25f
                        val dragFraction = dragAmount.x / cardWidth
                        val sensitivity = 0.25f
                        fineDragAccumulator = (fineDragAccumulator + dragFraction * sensitivity).coerceIn(0f, 1f)
                        
                        // Round to exact 1% increments (0.01f steps)
                        val fineVol = (fineDragAccumulator * 100).roundToInt() / 100f
                        
                        if (fineVol != localVolume) {
                            val now = android.os.SystemClock.uptimeMillis()
                            val volDelta = kotlin.math.abs(fineVol - lastHapticVolume)
                            
                            // Time-based throttling: trigger haptic at most once every 50ms OR on >= 5% (0.05f) volume step
                            if (now - lastHapticTime >= 50L || volDelta >= 0.05f) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                lastHapticTime = now
                                lastHapticVolume = fineVol
                            }

                            localVolume = fineVol
                            viewModel.setVolume(soundItem.id, fineVol, immediate = false)
                        }
                    },
                    onDragEnd = {
                        isDragging = false
                        viewModel.setHighPrecisionMode(soundItem.id, false)
                        viewModel.setVolume(soundItem.id, localVolume, immediate = true)
                    },
                    onDragCancel = {
                        isDragging = false
                        viewModel.setHighPrecisionMode(soundItem.id, false)
                        viewModel.setVolume(soundItem.id, localVolume, immediate = true)
                    }
                )
            }
            .clickable {
                onToggleActive()
            },
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = if (isActive) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(cardBackground)
        ) {
            // Liquid Volume Progress Fill (with spring animation)
            if (isActive) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction = animatedVolume)
                        .background(progressColor)
                )
            }

            // Delete button for custom sounds in top-right
            if (soundItem.isCustom) {
                IconButton(
                    onClick = {
                        viewModel.deleteCustomSound(soundItem)
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = "Delete Custom Sound",
                        tint = contentColor.copy(alpha = if (isActive) 0.6f else 0.4f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Foreground Content Overlay
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Row: Icon + Label
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    // Icon Wrapper
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                if (isActive) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        val customPainter = when (soundItem.id) {
                            "rain" -> painterResource(id = R.drawable.ic_custom_rain)
                            "birds" -> painterResource(id = R.drawable.ic_custom_bird)
                            else -> null
                        }
                        if (customPainter != null) {
                            Icon(
                                painter = customPainter,
                                contentDescription = soundItem.label,
                                tint = contentColor.copy(alpha = contentAlpha),
                                modifier = Modifier
                                    .size(22.dp)
                                    .graphicsLayer {
                                        if (isActive) {
                                            scaleX = pulseScale
                                            scaleY = pulseScale
                                        }
                                    }
                            )
                        } else {
                            Icon(
                                imageVector = if (soundItem.isCustom) Icons.Rounded.MusicNote else getIconForId(soundItem.id),
                                contentDescription = soundItem.label,
                                tint = contentColor.copy(alpha = contentAlpha),
                                modifier = Modifier
                                    .size(22.dp)
                                    .graphicsLayer {
                                        if (isActive) {
                                            scaleX = pulseScale
                                            scaleY = pulseScale
                                        }
                                    }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Sound Title
                    Text(
                        text = soundItem.label,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                            fontSize = 15.sp,
                            color = contentColor.copy(alpha = contentAlpha)
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    if (soundItem.isCustom) {
                        Spacer(modifier = Modifier.width(28.dp))
                    }
                }

                // Bottom Row: Status / Volume Percent Text
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val percentColor = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    
                    Text(
                        text = if (isActive) {
                            if (localVolume == 0f) "Sessiz" else "${(localVolume * 100).roundToInt()}%"
                        } else {
                            "Etkinleştirmek için dokunun"
                        },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = percentColor
                        )
                    )

                    if (isActive && localVolume > 0f) {
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = percentColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AudioEqualizerAnimation(
    color: Color,
    sharedProgress: Float = 0f,
    isResumed: Boolean = true
) {
    val height1 = if (isResumed) 0.2f + 0.7f * (0.5f + 0.5f * sin((sharedProgress * 10f) * 2 * Math.PI.toFloat())) else 0.5f
    val height2 = if (isResumed) 0.4f + 0.6f * (0.5f + 0.5f * sin((sharedProgress * 13.33f + 0.3f) * 2 * Math.PI.toFloat())) else 0.7f
    val height3 = if (isResumed) 0.1f + 0.7f * (0.5f + 0.5f * sin((sharedProgress * 8f + 0.7f) * 2 * Math.PI.toFloat())) else 0.4f

    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.height(18.dp)
    ) {
        Box(modifier = Modifier.width(3.dp).fillMaxHeight(height1).background(color, RoundedCornerShape(1.dp)))
        Box(modifier = Modifier.width(3.dp).fillMaxHeight(height2).background(color, RoundedCornerShape(1.dp)))
        Box(modifier = Modifier.width(3.dp).fillMaxHeight(height3).background(color, RoundedCornerShape(1.dp)))
    }
}

@Composable
fun SleepTimerDialog(
    remainingMs: Long,
    onDismiss: () -> Unit,
    onSelectDuration: (Int) -> Unit,
    onCancelTimer: () -> Unit,
    dialogBackground: Color,
    accentColor: Color
) {
    var hours by remember { mutableStateOf(0) }
    var minutes by remember { mutableStateOf(30) }

    // Elastic spring animations for timer hour and minute sliders
    val animatedHours by animateFloatAsState(
        targetValue = hours.toFloat(),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        )
    )
    val animatedMinutes by animateFloatAsState(
        targetValue = minutes.toFloat(),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        )
    )

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = dialogBackground),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 450.dp)
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Uyku Zamanlayıcı",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (remainingMs > 0L) {
                    val remainingMins = remainingMs / 60000
                    val remainingSecs = (remainingMs % 60000) / 1000
                    Text(
                        text = String.format("%02d:%02d kaldı", remainingMins, remainingSecs),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = accentColor
                        ),
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                    Button(
                        onClick = onCancelTimer,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.7f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Zamanlayıcıyı Durdur", color = Color.White)
                    }
                } else {
                    Text(
                        text = "Sesi otomatik olarak şu süreden sonra kapat:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Redesigned Hour / Minute Cards mimicking the board style
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Hour Selector Card
                        Card(
                            shape = RoundedCornerShape(28.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Start
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.HourglassTop,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "Saat: $hours",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Slider(
                                    value = animatedHours,
                                    onValueChange = { hours = it.roundToInt() },
                                    valueRange = 0f..12f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = MaterialTheme.colorScheme.primary,
                                        activeTrackColor = MaterialTheme.colorScheme.primary,
                                        inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        // Minute Selector Card
                        Card(
                            shape = RoundedCornerShape(28.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Start
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Timer,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "Dakika: $minutes",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Slider(
                                    value = animatedMinutes,
                                    onValueChange = { minutes = it.roundToInt() },
                                    valueRange = 0f..59f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = MaterialTheme.colorScheme.primary,
                                        activeTrackColor = MaterialTheme.colorScheme.primary,
                                        inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Preset Quick Action Buttons
                    val quickPresets = listOf(15, 30, 45, 60)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        quickPresets.forEach { mins ->
                            val label = if (mins >= 60) "${mins / 60} Sa" else "$mins Dk"
                            Button(
                                onClick = {
                                    hours = mins / 60
                                    minutes = mins % 60
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                                ),
                                shape = RoundedCornerShape(14.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Primary Action Button
                    Button(
                        onClick = {
                            val totalMins = hours * 60 + minutes
                            if (totalMins > 0) {
                                onSelectDuration(totalMins)
                            }
                        },
                        enabled = (hours * 60 + minutes) > 0,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accentColor,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("Zamanlayıcıyı Başlat")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                TextButton(onClick = onDismiss) {
                    Text("Kapat", color = accentColor)
                }
            }
        }
    }
}

@Composable
fun PresetsListDialog(
    presets: List<Preset>,
    onDismiss: () -> Unit,
    onLoadPreset: (Preset) -> Unit,
    onDeletePreset: (Preset) -> Unit,
    dialogBackground: Color,
    accentColor: Color,
    textColor: Color
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = dialogBackground),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Kayıtlı Şablonlar",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (presets.isEmpty()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(vertical = 24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Bookmarks,
                            contentDescription = null,
                            tint = textColor.copy(alpha = 0.4f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Henüz kayıtlı miks yok.\nSesleri ayarlayın ve kaydetmek için panodaki yer işareti düğmesine tıklayın!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                    ) {
                        presets.forEach { preset ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                                    .clickable { onLoadPreset(preset) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = preset.name,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    )
                                    // Summarize preset sound contents
                                    val summary = preset.soundVolumes.split(",")
                                        .mapNotNull {
                                            val id = it.split(":").firstOrNull()
                                            val type = SoundType.values().find { s -> s.id == id }
                                            type?.label ?: id?.replace("custom_", "")?.replace("_", " ")
                                                ?.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase() else char.toString() }
                                        }.joinToString(", ")
                                    Text(
                                        text = summary,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = textColor
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { onDeletePreset(preset) }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Şablonu Sil",
                                        tint = Color.Red.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                TextButton(onClick = onDismiss) {
                    Text("Kapat", color = accentColor)
                }
            }
        }
    }
}

@Composable
fun SavePresetDialog(
    presetName: String,
    onPresetNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    dialogBackground: Color,
    accentColor: Color
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = dialogBackground),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Mevcut Miksi Kaydet",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Özel ortam atmosferinize bir ad verin:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = presetName,
                    onValueChange = onPresetNameChange,
                    label = { Text("Şablon Adı", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentColor,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedLabelColor = accentColor,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("İptal", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(
                        onClick = onSave,
                        enabled = presetName.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accentColor,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Şablonu Kaydet")
                    }
                }
            }
        }
    }
}

fun getIconForId(soundId: String): ImageVector {
    val type = SoundType.values().find { it.id == soundId }
    return when (type) {
        SoundType.RAIN -> Icons.Rounded.Umbrella
        SoundType.STORM -> Icons.Rounded.Thunderstorm
        SoundType.WIND -> Icons.Rounded.Air
        SoundType.WAVES -> Icons.Rounded.Tsunami
        SoundType.STREAM -> Icons.Rounded.Water
        SoundType.FIREPLACE -> Icons.Rounded.LocalFireDepartment
        SoundType.BIRDS -> Icons.Rounded.FlutterDash
        SoundType.CRICKETS -> Icons.Rounded.NightsStay
        SoundType.TRAIN -> Icons.Rounded.Train
        SoundType.COFFEE_SHOP -> Icons.Rounded.LocalCafe
        SoundType.WHITE_NOISE -> Icons.Rounded.BlurOn
        SoundType.PINK_NOISE -> Icons.Rounded.Grain
        SoundType.BOAT -> Icons.Rounded.Sailing
        SoundType.CITY -> Icons.Rounded.LocationCity
        else -> Icons.Rounded.MusicNote
    }
}
