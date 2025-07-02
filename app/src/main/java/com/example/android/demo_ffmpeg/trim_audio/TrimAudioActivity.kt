package com.example.android.demo_ffmpeg.trim_audio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.android.demo_ffmpeg.ui.theme.Demo_ffmpegTheme
import com.example.android.demo_ffmpeg.util.AudioWaveForm
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TrimAudioActivity : ComponentActivity() {
    private val viewModel: TrimAudioViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Demo_ffmpegTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TrimAudioScreen(
                        modifier = Modifier.padding(innerPadding),
                        viewModel = viewModel
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.stopPlayback()
    }
}

@Composable
fun TrimAudioScreen(
    modifier: Modifier = Modifier,
    viewModel: TrimAudioViewModel
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, "Cần quyền truy cập file để chọn audio", Toast.LENGTH_SHORT)
                .show()
        }
    }

    // File picker launcher
    val audioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.setAudioPath(it, context) }
    }

    // Check permission function
    fun checkPermissionAndPickFile() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                // For Android 13+ use READ_MEDIA_AUDIO permission
                when (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_MEDIA_AUDIO
                )) {
                    PackageManager.PERMISSION_GRANTED -> {
                        audioLauncher.launch("audio/*")
                    }

                    else -> {
                        permissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
                    }
                }
            }

            else -> {
                // For older versions use READ_EXTERNAL_STORAGE
                when (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                )) {
                    PackageManager.PERMISSION_GRANTED -> {
                        audioLauncher.launch("audio/*")
                    }

                    else -> {
                        permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF4CAF50).copy(alpha = 0.1f))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCut,
                    contentDescription = "Trim Audio",
                    modifier = Modifier.size(48.dp),
                    tint = Color(0xFF4CAF50)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Cắt Audio",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF4CAF50)
                )
                Text(
                    text = "Cắt đoạn audio theo thời gian",
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // File selection card
        AudioFileSelectionCard(
            hasFile = state.audioPath != null,
            onClick = { checkPermissionAndPickFile() },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Audio waveform and range slider
        if (state.audioPath != null) {
            AudioTrimControls(
                audioDuration = state.audioDuration.toFloat(),
                startTimeMs = state.startTimeMs,
                endTimeMs = state.endTimeMs,
                currentPosition = state.currentPlaybackPosition,
                isPlaying = state.isPlaying,
                onRangeChange = { start, end ->
                    viewModel.setTimeRange(start, end)
                },
                onPlayPause = {
                    if (state.isPlaying) {
                        viewModel.stopPlayback()
                    } else {
                        viewModel.playAudioPreview(context)
                    }
                },
                formatTime = { ms -> viewModel.formatMillisecondsToTimeString(ms.toLong()) },
                modifier = Modifier.fillMaxWidth(),
                audioAmplitudes = state.floatListSample,
                audioPath = state.audioPath,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Time selection section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Điều chỉnh thời gian chính xác",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Start time
                        TimeInputField(
                            label = "Bắt đầu",
                            value = state.startTime,
                            onValueChange = { viewModel.setStartTime(it) },
                            modifier = Modifier.weight(1f)
                        )

                        // End time
                        TimeInputField(
                            label = "Kết thúc",
                            value = state.endTime,
                            onValueChange = { viewModel.setEndTime(it) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Duration info
                    val duration = calculateDuration(state.startTime, state.endTime)
                    if (duration > 0) {
                        Text(
                            text = "Thời lượng: ${duration}s",
                            fontSize = 14.sp,
                            color = Color(0xFF4CAF50),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Progress section
        if (state.isProcessing || state.progress.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (state.isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = Color(0xFF4CAF50)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Text(
                        text = state.progress,
                        textAlign = TextAlign.Center,
                        fontSize = 14.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Error message
        if (state.error != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Error,
                        contentDescription = "Error",
                        tint = Color(0xFFD32F2F),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = state.error ?: "",
                        color = Color(0xFFD32F2F),
                        fontSize = 14.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Success message with output path
        if (state.outputFilePath != null && !state.isProcessing) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E8))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = "Success",
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Cắt audio thành công!",
                            color = Color(0xFF4CAF50),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "File đã được lưu vào thư mục Music trên thiết bị",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    // Display technical path info (hidden in production apps)
                    Text(
                        text = "Đường dẫn: ${state.outputFilePath}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Button to open the file
                    Button(
                        onClick = {
                            try {
                                // Try to use the MediaStore URI first (best option for public music)
                                if (state.outputUri != null) {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(state.outputUri, "audio/mp3")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(intent)
                                } else {
                                    // Fallback to file path
                                    val file = File(state.outputFilePath!!)
                                    if (file.exists()) {
                                        val uri = Uri.fromFile(file)
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(uri, "audio/mp3")
                                        }
                                        context.startActivity(intent)
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "Không thể mở file",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    "Lỗi khi mở file: ${e.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayCircle,
                            contentDescription = "Play Audio",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Mở file audio")
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Spacer(modifier = Modifier.weight(1f))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.audioPath != null) {
                OutlinedButton(
                    onClick = {
                        viewModel.resetState()
                        viewModel.clearError()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !state.isProcessing
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Reset",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Làm mới")
                }
            }

            Button(
                onClick = { viewModel.trimAudio(context) },
                modifier = Modifier.weight(1f),
                enabled = !state.isProcessing && state.audioPath != null,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
            ) {
                if (state.isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.ContentCut,
                        contentDescription = "Trim",
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text("Cắt Audio")
            }
        }
    }

    // Clear error when it's shown
    LaunchedEffect(state.error) {
        if (state.error != null) {
            delay(5000) // Auto clear error after 5 seconds
            viewModel.clearError()
        }
    }
}

@Composable
fun AudioTrimControls(
    audioDuration: Float,
    startTimeMs: Float,
    endTimeMs: Float,
    currentPosition: Float,
    isPlaying: Boolean,
    onRangeChange: (Float, Float) -> Unit,
    onPlayPause: () -> Unit,
    formatTime: (Float) -> String,
    modifier: Modifier = Modifier,
    audioAmplitudes: List<Float>,
    audioPath: Uri?
) {
    val primaryColor = Color(0xFF4CAF50)
    val secondaryColor = Color(0xFF4CAF50).copy(alpha = 0.3f)

    Card(
        modifier = modifier.fillMaxSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(2.dp)
        ) {
            // Header with time display
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatTime(startTimeMs),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = formatTime(endTimeMs),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Box(
                modifier = Modifier
                    .padding(2.dp)
                    .wrapContentSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
            ) {
//                ImprovedAudioWaveform(
//                    modifier = Modifier.matchParentSize(),
//                    startPercent = startTimeMs / audioDuration,
//                    endPercent = endTimeMs / audioDuration,
//                    primaryColor = primaryColor,
//                    secondaryColor = secondaryColor,
//                    audioAmplitudes = audioAmplitudes,
//                    currentPosition = if (isPlaying) currentPosition / audioDuration else null,
//                    onRangeChange = onRangeChange,
//                    audioDuration = audioDuration
//                )
//                AudioWaveFormAmplitudes(
//                    modifier = Modifier.matchParentSize(),
//                    startPercent = startTimeMs / audioDuration,
//                    endPercent = endTimeMs / audioDuration,
//                    primaryColor = primaryColor,
//                    secondaryColor = secondaryColor,
//                    audioAmplitudes = audioAmplitudes,
//                    currentPosition = if (isPlaying) currentPosition / audioDuration else null,
//                    onRangeChange = onRangeChange,
//                    audioDuration = audioDuration
//                )
                audioPath?.let {
                    AudioWaveForm(context = LocalContext.current, audioUri = it)
                }
            }
            // Play button and current position + Zoom controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Current position display
                Text(
                    text = "Vị trí: ${formatTime(currentPosition)}",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                // Play/Pause button
                FilledIconButton(
                    onClick = onPlayPause,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = primaryColor)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

//@Composable
//fun AudioWaveFormAmplitudes(
//    audioAmplitudes: List<Float>,
//    modifier: Modifier,
//    startPercent: Float,
//    endPercent: Float,
//    primaryColor: Color,
//    secondaryColor: Color,
//    currentPosition: Float?,
//    onRangeChange: (Float, Float) -> Unit,
//    audioDuration: Float
//){
//    val amplitudes = audioAmplitudes.map{it.toInt()}
//    var waveformProgress by remember { mutableStateOf(0F) }
//    AudioWaveform(
//        modifier = modifier,
//        style = Fill,
//        waveformAlignment = WaveformAlignment.Center,
//        amplitudeType = AmplitudeType.Avg,
//        progressBrush = SolidColor(Color.Magenta),
//        waveformBrush = SolidColor(Color.LightGray),
//        spikeWidth = 4.dp,
//        spikePadding = 2.dp,
//        spikeRadius = 4.dp,
//        progress = waveformProgress,
//        amplitudes = amplitudes,
//        onProgressChange = { waveformProgress = it },
//        onProgressChangeFinished = {}
//    )
//
//}

@Composable
fun ImprovedAudioWaveform(
    audioAmplitudes: List<Float>,
    modifier: Modifier,
    startPercent: Float,
    endPercent: Float,
    primaryColor: Color,
    secondaryColor: Color,
    currentPosition: Float?,
    onRangeChange: (Float, Float) -> Unit,
    audioDuration: Float
) {
    val density = LocalDensity.current
    var isDraggingLeft by remember { mutableStateOf(false) }
    var isDraggingRight by remember { mutableStateOf(false) }
    var isPanning by remember { mutableStateOf(false) }

    // Handle size and touch area
    val handleWidth = with(density) { 1.dp.toPx() }
    val handleHeight = with(density) { 48.dp.toPx() }
    val handleTouchRadius = with(density) { 24.dp.toPx() }

    // Zoom and pan state
    var zoomLevel by remember { mutableStateOf(1f) }
    val maxZoomLevel = 3f
    var offsetX by remember { mutableStateOf(0f) }

    // Max zoom level


    // Handle edge case for empty amplitudes list
    val safeAmplitudes = remember(audioAmplitudes) {
        if (audioAmplitudes.isEmpty()) listOf(0.1f) else audioAmplitudes
    }
    val surfaceColor = MaterialTheme.colorScheme.surface
    val scrollState = rememberScrollState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Add zoom controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(
                onClick = {
                    // Zoom out: reduce zoom level but not below 1
                    zoomLevel = max(1f, zoomLevel - 1f)

                },
                enabled = zoomLevel > 1f
            ) {
                Icon(
                    imageVector = Icons.Default.ZoomOut,
                    contentDescription = "Phóng nhỏ",
                    tint = if (zoomLevel > 1f) primaryColor else Color.Gray
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Zoom level indicator
            Text(
                text = "${zoomLevel.toInt()}x",
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .padding(horizontal = 4.dp),
                fontSize = 12.sp,
                color = primaryColor
            )

            Spacer(modifier = Modifier.width(4.dp))

            IconButton(
                onClick = {
                    // Zoom in: increase zoom level but not above maxZoomLevel
                    zoomLevel = min(maxZoomLevel, zoomLevel + 1f)
                },
                enabled = zoomLevel < maxZoomLevel
            ) {
                Icon(
                    imageVector = Icons.Default.ZoomIn,
                    contentDescription = "Phóng to",
                    tint = if (zoomLevel < maxZoomLevel) primaryColor else Color.Gray
                )
            }
        }

        // Waveform with horizontal scroll when zoomed in
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clipToBounds()
                .then(
                    if (zoomLevel > 1f) {
                        Modifier.horizontalScroll(scrollState)
                    } else {
                        Modifier
                    }
                )
        ) {
            // Calculate the total width of the content when zoomed
            val contentWidth = with(density) {
                if (zoomLevel > 1f) {
                    (100.dp * zoomLevel).toPx()
                } else {
                    0f
                }
            }

            Canvas(
                modifier = Modifier
                    .fillMaxHeight()
                    .then(
                        if (zoomLevel > 1f) {
                            Modifier.width(with(density) { contentWidth.toDp() })
                        } else {
                            Modifier.fillMaxWidth()
                        }
                    )
                    .pointerInput(zoomLevel) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                // Determine which handle the user is dragging
                                val width = size.width
                                val leftHandleX =
                                    (startPercent * width).coerceIn(0f, width.toFloat())
                                val rightHandleX =
                                    (endPercent * width).coerceIn(0f, width.toFloat())

                                // Use larger touch area for better UX
                                isDraggingLeft = abs(offset.x - leftHandleX) <= handleTouchRadius
                                isDraggingRight =
                                    !isDraggingLeft && abs(offset.x - rightHandleX) <= handleTouchRadius

                                // If not dragging handles, enable panning when zoomed in
                                isPanning = !isDraggingLeft && !isDraggingRight && zoomLevel > 1f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val width = size.width

                                when {
                                    isDraggingLeft -> {
                                        // Calculate drag percentage based on total width
                                        val dragXPercent = dragAmount.x / width
                                        val newStartPercent =
                                            (startPercent + dragXPercent).coerceIn(
                                                0f,
                                                endPercent - 0.05f
                                            )
                                        onRangeChange(
                                            newStartPercent * audioDuration,
                                            endPercent * audioDuration
                                        )
                                    }

                                    isDraggingRight -> {
                                        val dragXPercent = dragAmount.x / width
                                        val newEndPercent = (endPercent + dragXPercent).coerceIn(
                                            startPercent + 0.05f,
                                            1f
                                        )
                                        onRangeChange(
                                            startPercent * audioDuration,
                                            newEndPercent * audioDuration
                                        )
                                    }

                                    isPanning && zoomLevel > 1f -> {
                                        // Just track the pan amount but don't try to scroll directly
                                        // The scroll offset will be handled by the horizontalScroll modifier
                                    }
                                }
                            },
                            onDragEnd = {
                                isDraggingLeft = false
                                isDraggingRight = false
                                isPanning = false
                            }
                        )
                    }
            ) {
                val width = size.width
                val height = size.height
                val centerY = height / 2

                // Draw background gradient
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            surfaceColor.copy(alpha = 0.7f),
                            surfaceColor.copy(alpha = 0.4f)
                        )
                    ),
                    size = size
                )

                // Draw grid lines for time markers adjusted for zoom level
                val timeIntervalSecs = when {
                    audioDuration <= 10000 -> 0.5f  // 0.5s intervals for short audio (<10s)
                    audioDuration <= 30000 -> 1f    // 1s intervals for medium audio (<30s)
                    audioDuration <= 120000 -> 5f   // 5s intervals for longer audio (<2min)
                    else -> 10f                     // 10s intervals for very long audio
                }
                val totalDurationSecs = audioDuration / 1000f

                // Adjust interval based on zoom level
                val adjustedInterval =
                    if (zoomLevel > 3f) timeIntervalSecs / 2 else timeIntervalSecs
                val timeMarkCount = (totalDurationSecs / adjustedInterval).toInt() + 1

                // Draw grid lines for time markers
                for (i in 0 until timeMarkCount) {
                    val timeSec = i * adjustedInterval
                    val timePercent = timeSec / totalDurationSecs
                    // Calculate X position considering zoom level
                    val timeX = timePercent * width

                    // Draw time marker line
                    drawLine(
                        color = Color.Gray.copy(alpha = 0.3f),
                        start = Offset(timeX, 0f),
                        end = Offset(timeX, height),
                        strokeWidth = 0.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 4f), 0f)
                    )

                    // Show more time markers when zoomed in
                    val shouldShowLabel = zoomLevel <= 3f || i % (zoomLevel.toInt() / 2 + 1) == 0
                    if (shouldShowLabel) {
                        val timeText = String.format("%.1f", timeSec)
                        drawIntoCanvas { canvas ->
                            val textPaint = Paint().asFrameworkPaint().apply {
                                color = android.graphics.Color.GRAY
                                alpha = 150
                                textSize = 8.sp.toPx()
                                textAlign = android.graphics.Paint.Align.CENTER
                            }
                            canvas.nativeCanvas.drawText(
                                timeText,
                                timeX,
                                height - 5.dp.toPx(),
                                textPaint
                            )
                        }
                    }
                }

                // Draw selection area with gradient
                val selectStartX = (startPercent * width).coerceIn(0f, width)
                val selectEndX = (endPercent * width).coerceIn(0f, width)
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = 0.25f),
                            primaryColor.copy(alpha = 0.15f)
                        )
                    ),
                    topLeft = Offset(selectStartX, 0f),
                    size = Size(selectEndX - selectStartX, height)
                )

                // Draw waveform adjusted for zoom level
                val totalDataPoints = safeAmplitudes.size

                // Calculate sampling rate based on zoom level
                val samplingStep = when {
                    zoomLevel >= 5f -> 1 // No sampling when zoomed in a lot
                    zoomLevel >= 3f -> 2 // Less sampling when zoomed in
                    else -> max(1, (totalDataPoints / (width / 2)).toInt()) // Normal sampling
                }

                // Draw waveform bars with mirror effect (top and bottom)
                for (i in 0 until totalDataPoints step samplingStep) {
                    val amplitude = safeAmplitudes[i]
                    val xPercent = i.toFloat() / totalDataPoints
                    val x = xPercent * width

                    // Skip if outside visible area
                    if (zoomLevel > 1f && (x < scrollState.value - 10 || x > scrollState.value + width + 10)) {
                        continue
                    }

                    // Ensure minimum bar height for visibility
                    val barHeight = (amplitude * height * 0.4f).coerceAtLeast(2f)
                    val isInSelectedRegion = xPercent in startPercent..endPercent

                    // Gradient colors for bars based on selection state
                    val barColor = if (isInSelectedRegion) {
                        // Create more vibrant bars in selected region
                        Brush.verticalGradient(
                            colors = listOf(
                                primaryColor.copy(alpha = 0.9f),
                                primaryColor.copy(alpha = 0.7f)
                            )
                        )
                    } else {
                        // Softer colors for unselected regions
                        Brush.verticalGradient(
                            colors = listOf(
                                secondaryColor.copy(alpha = 0.7f),
                                secondaryColor.copy(alpha = 0.5f)
                            )
                        )
                    }

                    // Calculate bar width - adjust based on zoom for better visuals 
                    val barWidth = when {
                        zoomLevel >= 5f -> 3.dp.toPx()
                        zoomLevel >= 2f -> 2.dp.toPx()
                        else -> 1.5.dp.toPx()
                    }

                    // Draw waveform with mirror effect (top half)
                    drawRect(
                        brush = barColor,
                        topLeft = Offset(x, centerY - barHeight),
                        size = Size(
                            width = barWidth,
                            height = barHeight
                        ),
                        alpha = if (isInSelectedRegion) 1f else 0.8f
                    )

                    // Draw bottom half (mirror)
                    drawRect(
                        brush = barColor,
                        topLeft = Offset(x, centerY),
                        size = Size(
                            width = barWidth,
                            height = barHeight
                        ),
                        alpha = if (isInSelectedRegion) 0.85f else 0.65f
                    )
                }

                // Draw current playback position
                currentPosition?.let {
                    val posX = (it * width).coerceIn(0f, width)

                    // Draw playhead line
                    drawLine(
                        color = Color.Red.copy(alpha = 0.9f),
                        start = Offset(posX, 0f),
                        end = Offset(posX, height),
                        strokeWidth = 2.dp.toPx(),
                    )

                    // Draw playhead indicator at top
                    drawCircle(
                        color = Color.Red,
                        radius = 4.dp.toPx(),
                        center = Offset(posX, 8.dp.toPx())
                    )
                }

                // Draw handles with shadow and borders
                val leftHandleX = (startPercent * width).coerceIn(0f, width)
                val rightHandleX = (endPercent * width).coerceIn(0f, width)

                // Start handle shadow effect
                drawRoundRect(
                    color = primaryColor.copy(alpha = 0.4f),
                    topLeft = Offset(
                        leftHandleX - handleWidth / 2 - 1,
                        centerY - handleHeight / 2 - 1
                    ),
                    size = Size(handleWidth + 2, handleHeight + 2),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )

                // Start handle border
                drawRoundRect(
                    color = primaryColor,
                    topLeft = Offset(leftHandleX - handleWidth / 2, centerY - handleHeight / 2),
                    size = Size(handleWidth, handleHeight),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx())
                )

                // Handle grip lines for left handle
                val leftGripY = centerY - 10.dp.toPx()
                for (i in 0..2) {
                    drawLine(
                        color = primaryColor,
                        start = Offset(leftHandleX - 2.dp.toPx(), leftGripY + i * 8.dp.toPx()),
                        end = Offset(leftHandleX + 2.dp.toPx(), leftGripY + i * 8.dp.toPx()),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                // End handle shadow effect
                drawRoundRect(
                    color = primaryColor.copy(alpha = 0.4f),
                    topLeft = Offset(
                        rightHandleX - handleWidth / 2 - 1,
                        centerY - handleHeight / 2 - 1
                    ),
                    size = Size(handleWidth + 2, handleHeight + 2),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )

                // End handle border
                drawRoundRect(
                    color = primaryColor,
                    topLeft = Offset(rightHandleX - handleWidth / 2, centerY - handleHeight / 2),
                    size = Size(handleWidth, handleHeight),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx())
                )

                // Handle grip lines for right handle
                val rightGripY = centerY - 10.dp.toPx()
                for (i in 0..2) {
                    drawLine(
                        color = primaryColor,
                        start = Offset(rightHandleX - 2.dp.toPx(), rightGripY + i * 8.dp.toPx()),
                        end = Offset(rightHandleX + 2.dp.toPx(), rightGripY + i * 8.dp.toPx()),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                // Connection line between handles
                drawLine(
                    color = primaryColor.copy(alpha = 0.6f),
                    start = Offset(leftHandleX, centerY),
                    end = Offset(rightHandleX, centerY),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
                )
            }
        }

        // Show scroll hint when zoomed in
        if (zoomLevel > 1f) {
            Text(
                text = "Kéo ngang để xem chi tiết sóng âm",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 4.dp)
            )
        }
    }
}

@Composable
fun AudioFileSelectionCard(
    hasFile: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .clickable { onClick() }
            .border(
                width = if (hasFile) 2.dp else 1.dp,
                color = if (hasFile) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(12.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (hasFile)
                Color(0xFF4CAF50).copy(alpha = 0.1f)
            else
                MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        if (hasFile) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (hasFile) Icons.Filled.CheckCircle else Icons.Filled.AudioFile,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "File Audio",
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp
                )
                Text(
                    text = if (hasFile) "✓ Đã chọn file" else "Chọn file audio để cắt",
                    fontSize = 14.sp,
                    color = if (hasFile)
                        Color(0xFF4CAF50)
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = "Select",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
fun TimeInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("MM:SS") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF4CAF50),
                focusedLabelColor = Color(0xFF4CAF50)
            )
        )
    }
}

fun calculateDuration(startTime: String, endTime: String): Int {
    fun timeToSeconds(time: String): Int {
        val parts = time.split(":")
        return if (parts.size >= 2) {
            (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
        } else {
            0
        }
    }

    val startSeconds = timeToSeconds(startTime)
    val endSeconds = timeToSeconds(endTime)
    return maxOf(0, endSeconds - startSeconds)
}






