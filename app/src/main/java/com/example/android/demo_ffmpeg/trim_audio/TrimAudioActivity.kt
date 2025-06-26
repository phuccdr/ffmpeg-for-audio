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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.android.demo_ffmpeg.ui.theme.Demo_ffmpegTheme
import kotlinx.coroutines.delay
import java.io.File

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
            Toast.makeText(context, "Cần quyền truy cập file để chọn audio", Toast.LENGTH_SHORT).show()
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
                when (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO)) {
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
                when (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)) {
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
                modifier = Modifier.fillMaxWidth()
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
                        text = state.error?:"",
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
                                        Toast.makeText(context, "Không thể mở file", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "Lỗi khi mở file: ${e.message}", Toast.LENGTH_SHORT).show()
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
    modifier: Modifier = Modifier
) {
    val primaryColor = Color(0xFF4CAF50)
    val secondaryColor = Color(0xFF4CAF50).copy(alpha = 0.3f)
    
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
            
            // Audio waveform visualization placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                // Audio waveform visualization (simplified for the demo)
                AudioWaveformVisualization(
                    modifier = Modifier.matchParentSize(),
                    startPercent = startTimeMs / audioDuration,
                    endPercent = endTimeMs / audioDuration,
                    currentPositionPercent = currentPosition / audioDuration,
                    primaryColor = primaryColor,
                    secondaryColor = secondaryColor
                )
                
                // Start handle
                AudioTrimHandle(
                    position = startTimeMs / audioDuration,
                    color = primaryColor,
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(8.dp)
                )
                
                // End handle
                AudioTrimHandle(
                    position = endTimeMs / audioDuration,
                    color = primaryColor,
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(8.dp)
                )
                
                // Playback position indicator
                if (isPlaying) {
                    Box(
                        modifier = Modifier
                            .offset(x = (currentPosition / audioDuration * 100).dp)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(Color.Red)
                    )
                }
            }
            
            // Custom range slider
            RangeSlider(
                value = startTimeMs / audioDuration..endTimeMs / audioDuration,
                onValueChange = { range ->
                    val newStart = (range.start * audioDuration).coerceAtLeast(0f)
                    val newEnd = (range.endInclusive * audioDuration).coerceAtMost(audioDuration)
                    onRangeChange(newStart, newEnd)
                },
                valueRange = 0f..1f,
                steps = 100,
                colors = SliderDefaults.colors(
                    thumbColor = primaryColor,
                    activeTrackColor = primaryColor,
                    inactiveTrackColor = secondaryColor
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            )
            
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

@Composable
fun AudioWaveformVisualization(
    modifier: Modifier = Modifier,
    startPercent: Float,
    endPercent: Float,
    currentPositionPercent: Float,
    primaryColor: Color,
    secondaryColor: Color
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        
        // Draw background for non-selected region
        drawRect(
            color = Color.Gray.copy(alpha = 0.2f),
            size = size
        )
        
        // Draw selected region background
        drawRect(
            color = primaryColor.copy(alpha = 0.2f),
            topLeft = Offset(startPercent * width, 0f),
            size = androidx.compose.ui.geometry.Size(
                (endPercent - startPercent) * width,
                height
            )
        )
        
        // Draw waveform bars
        val barCount = 60
        val barWidth = width / barCount
        val random = java.util.Random(0) // Use fixed seed for consistent visualization
        
        for (i in 0 until barCount) {
            val x = i * barWidth
            val barHeightPercent = 0.1f + random.nextFloat() * 0.8f // Random height between 10-90%
            val barHeight = height * barHeightPercent
            
            val barColor = when {
                x / width in startPercent..endPercent -> primaryColor
                else -> secondaryColor
            }
            
            // Draw bar
            drawRect(
                color = barColor,
                topLeft = Offset(x + barWidth * 0.1f, (height - barHeight) / 2),
                size = androidx.compose.ui.geometry.Size(barWidth * 0.8f, barHeight)
            )
        }
    }
}

@Composable
fun AudioTrimHandle(
    position: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .offset(
                x = (position * LocalContext.current.resources.displayMetrics.density * 100).dp
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(8.dp)
                .background(color)
        )
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

@OptIn(ExperimentalMaterial3Api::class)
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