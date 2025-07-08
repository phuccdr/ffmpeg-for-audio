package com.example.android.demo_ffmpeg.trim_audio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.android.demo_ffmpeg.compose_audiowaveform.AudioWaveform
import com.example.android.demo_ffmpeg.compose_audiowaveform.infiniteLinearGradient
import com.example.android.demo_ffmpeg.compose_audiowaveform.model.AmplitudeType
import com.example.android.demo_ffmpeg.compose_audiowaveform.model.WaveformAlignment
import com.example.android.demo_ffmpeg.trim_audio.TrimAudioActivityConstants.TAG
import com.example.android.demo_ffmpeg.ui.theme.Demo_ffmpegTheme
import com.example.android.demo_ffmpeg.util.AudioFileInfo
import com.example.android.demo_ffmpeg.util.WaveformData
import com.example.android.demo_ffmpeg.util.WaveformSelectionView
import kotlinx.coroutines.delay
import java.io.File

object TrimAudioActivityConstants {
    const val TAG = "TrimAudioActivity"
}

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
        viewModel.stopMediaPlayback()
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
        uri?.let { viewModel.getAudioFileInfo(it, context) }
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
            hasFile = state.audioInfo != null,
            onClick = { checkPermissionAndPickFile() },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Audio waveform and range slider
        if (state.audioInfo != null) {
            AudioTrimControls(
                modifier = Modifier.fillMaxWidth(),
                audioInfo = state.audioInfo!!,
                viewModel = viewModel,
                state = state
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
                            value = formatMillisecondsToTimeString(state.audioInfo!!.startTime.toLong()),
                            onValueChange = { viewModel.setStartTime(it) },
                            modifier = Modifier.weight(1f)
                        )

                        // End time
                        TimeInputField(
                            label = "Kết thúc",
                            value = formatMillisecondsToTimeString(state.audioInfo!!.endTime.toLong()),
                            onValueChange = { viewModel.setEndTime(it) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Duration info
                    val duration = calculateDurationFromMs(state.audioInfo!!.startTime, state.audioInfo!!.endTime)
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
            if (state.audioInfo != null) {
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
                enabled = !state.isProcessing && state.audioInfo != null,
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
    modifier: Modifier = Modifier,
    audioInfo: AudioFileInfo,
    viewModel: TrimAudioViewModel,
    state: AudioTrimState
) {
    val context = LocalContext.current
    val primaryColor = Color(0xFF4CAF50)
    val secondaryColor = Color(0xFF4CAF50).copy(alpha = 0.3f)

    // Calculate derived values from state
    val startTimeMs = audioInfo.startTime
    val endTimeMs = audioInfo.endTime
    val currentPosition = state.currentPlaybackPosition
    val isPlaying = state.isPlaying


    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header with time display
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatMillisecondsToTimeString(startTimeMs.toLong()),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = formatMillisecondsToTimeString(endTimeMs.toLong()),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Box(
                modifier = Modifier
                    .height(120.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
            ) {
                AudioWaveCompose(
                    audioInfo = audioInfo,
                    onSelectionChanged = { start, end ->
                        viewModel.setTimeRange(start.toFloat(), end.toFloat())
                    },
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .horizontalScroll(rememberScrollState())
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ){
                val colorBrush = SolidColor(Color.Magenta)

                val staticGradientBrush = Brush.linearGradient(colors = listOf(Color(0xff22c1c3), Color(0xfffdbb2d)))

                val animatedGradientBrush = Brush.infiniteLinearGradient(
                    colors = listOf(Color(0xff22c1c3), Color(0xfffdbb2d)),
                    animation = tween(durationMillis = 6000, easing = LinearEasing),
                    width = 128F
                )
                AudioWaveform(
                    modifier = Modifier,
                    style = Fill,
                    waveformAlignment = WaveformAlignment.Center,
                    amplitudeType = AmplitudeType.Avg,
                    // Colors could be updated with Brush API
                    progressBrush = animatedGradientBrush,
                    waveformBrush = SolidColor(Color.LightGray),
                    spikeWidth = 1.dp,
                    spikePadding = 1.dp,
                    spikeRadius = 2.dp,
                    progress = state.playerProgress,
                    amplitudes = state.amplitude,
                    onProgressChange = { state.playerProgress = it },
                    onProgressChangeFinished = {},
                    onDragTrim = { startProgress, endProgress ->
                        // Chuyển đổi progress (0-1) thành milliseconds
                        state.audioInfo?.let { audioInfo ->
                            val totalDuration = audioInfo.duration
                            val startMs = startProgress * totalDuration
                            val endMs = endProgress * totalDuration
                            
                            // Cập nhật time range trong ViewModel
                            viewModel.setTimeRange(startMs, endMs)
                            
                            Log.d("AudioWaveform", "Trim range updated: ${startMs}ms - ${endMs}ms")
                        }
                    }
                )
            }
            
            // Play button and current position
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Current position display
                Text(
                    text = "Vị trí: ${formatMillisecondsToTimeString(currentPosition.toLong())}",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                // Play/Pause button
                FilledIconButton(
                    onClick = { 
                        if (isPlaying) {
                            viewModel.pauseAudioPlayback()
                        } else {
                            viewModel.playAudioPreview(context)
                        }
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = primaryColor)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Stop/Reset button
                FilledIconButton(
                    onClick = { 
                        viewModel.stopMediaPlayback()
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = "Stop",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun AudioWaveCompose(
    audioInfo: AudioFileInfo,
    onSelectionChanged: (Long, Long) -> Unit = { _, _ -> }
) {
    val waveformData = remember(audioInfo) {
        val samplesPerPixel = 400
        val sampleRate = 512
        WaveformData(
            sampleRate = sampleRate,
            samplesPerPixel = samplesPerPixel,
            samples = audioInfo.amplitude,
            durationMs = audioInfo.duration.toLong()
        )
    }
    
    var selectionStart by remember(audioInfo) { mutableLongStateOf(audioInfo.startTime.toLong()) }
    var selectionEnd by remember(audioInfo) { mutableLongStateOf(audioInfo.endTime.toLong()) }

    WaveformSelectionView(
        waveformData = waveformData,
        selectionStartMs = selectionStart,
        selectionEndMs = selectionEnd,
        onSelectionChanged = { start, end ->
            selectionStart = start
            selectionEnd = end
            onSelectionChanged(start, end)
        }
    )


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

// Helper functions
fun formatMillisecondsToTimeString(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}

fun calculateDurationFromMs(startMs: Float, endMs: Float): Int {
    return maxOf(0, ((endMs - startMs) / 1000).toInt())
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






