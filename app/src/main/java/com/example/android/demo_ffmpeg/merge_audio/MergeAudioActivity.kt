package com.example.android.demo_ffmpeg.merge_audio

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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.android.demo_ffmpeg.ui.theme.Demo_ffmpegTheme
import java.io.File

class MergeAudioActivity : ComponentActivity() {
    private val viewModel: MergeAudioViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Demo_ffmpegTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MergeAudioScreen(
                        modifier = Modifier.padding(innerPadding),
                        viewModel = viewModel
                    )
                }
            }
        }
    }
}

@Composable
fun MergeAudioScreen(
    modifier: Modifier = Modifier,
    viewModel: MergeAudioViewModel
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    
    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, "Cần quyền truy cập file để chọn audio", Toast.LENGTH_SHORT).show()
        }
    }
    
    // File picker launchers
    val firstAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.setFirstAudioPath(it) }
    }
    
    val secondAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.setSecondAudioPath(it) }
    }
    
    // Check permission function
    fun checkPermissionAndPickFile(isFirstFile: Boolean) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                // For Android 13+ use READ_MEDIA_AUDIO permission
                when (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO)) {
                    PackageManager.PERMISSION_GRANTED -> {
                        if (isFirstFile) {
                            firstAudioLauncher.launch("audio/*")
                        } else {
                            secondAudioLauncher.launch("audio/*")
                        }
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
                        if (isFirstFile) {
                            firstAudioLauncher.launch("audio/*")
                        } else {
                            secondAudioLauncher.launch("audio/*")
                        }
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
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2196F3).copy(alpha = 0.1f))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Filled.Merge,
                    contentDescription = "Merge Audio",
                    modifier = Modifier.size(48.dp),
                    tint = Color(0xFF2196F3)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Ghép Audio",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2196F3)
                )
                Text(
                    text = "Nối các file audio với nhau",
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // File selection cards with preview controls
        AudioFileCardWithPreview(
            title = "Audio 1 (Đầu)",
            subtitle = "Chọn file audio đầu tiên",
            hasFile = state.firstAudioPath != null,
            isPlaying = state.playbackState == PlaybackState.PLAYING_FIRST,
            isPaused = state.playbackState == PlaybackState.PAUSED && state.currentPlayingUri == state.firstAudioPath,
            progress = if (state.playbackState == PlaybackState.PLAYING_FIRST || 
                         (state.playbackState == PlaybackState.PAUSED && state.currentPlayingUri == state.firstAudioPath)) 
                        state.playbackProgress else 0f,
            onSelectClick = { checkPermissionAndPickFile(true) },
            onPlayClick = { viewModel.previewAudio(context, true) },
            onPauseClick = { viewModel.pauseAudio() },
            onStopClick = { viewModel.stopAudio() },
            modifier = Modifier.fillMaxWidth()
        )
        
        // Arrow indicating merge direction
        Box(
            modifier = Modifier.padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "Plus",
                modifier = Modifier.size(32.dp),
                tint = Color(0xFF4CAF50)
            )
        }
        
        AudioFileCardWithPreview(
            title = "Audio 2 (Cuối)",
            subtitle = "Chọn file audio thứ hai",
            hasFile = state.secondAudioPath != null,
            isPlaying = state.playbackState == PlaybackState.PLAYING_SECOND,
            isPaused = state.playbackState == PlaybackState.PAUSED && state.currentPlayingUri == state.secondAudioPath,
            progress = if (state.playbackState == PlaybackState.PLAYING_SECOND || 
                         (state.playbackState == PlaybackState.PAUSED && state.currentPlayingUri == state.secondAudioPath)) 
                        state.playbackProgress else 0f,
            onSelectClick = { checkPermissionAndPickFile(false) },
            onPlayClick = { viewModel.previewAudio(context, false) },
            onPauseClick = { viewModel.pauseAudio() },
            onStopClick = { viewModel.stopAudio() },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
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
                            text = "Merge thành công!",
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
            if (state.firstAudioPath != null || state.secondAudioPath != null) {
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
                onClick = { viewModel.mergeAudio(context) },
                modifier = Modifier.weight(1f),
                enabled = !state.isProcessing && 
                         state.firstAudioPath != null && 
                         state.secondAudioPath != null,
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
                        imageVector = Icons.Filled.Merge,
                        contentDescription = "Merge",
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text("Ghép Audio")
            }
        }
    }
    
    // Clear error when it's shown
    LaunchedEffect(state.error) {
        if (state.error != null) {
            kotlinx.coroutines.delay(5000) // Auto clear error after 5 seconds
            viewModel.clearError()
        }
    }
}

@Composable
fun AudioFileCardWithPreview(
    title: String,
    subtitle: String,
    hasFile: Boolean,
    isPlaying: Boolean,
    isPaused: Boolean,
    progress: Float,
    onSelectClick: () -> Unit,
    onPlayClick: () -> Unit,
    onPauseClick: () -> Unit,
    onStopClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
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
        Column(modifier = Modifier.padding(16.dp)) {
            // File selection row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onSelectClick() }
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
                        text = title,
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp
                    )
                    Text(
                        text = if (hasFile) "✓ Đã chọn file" else subtitle,
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
            
            // Audio preview controls
            if (hasFile) {
                Spacer(modifier = Modifier.height(12.dp))
                Divider()
                Spacer(modifier = Modifier.height(12.dp))
                
                // Progress bar
                if (isPlaying || isPaused) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                        color = Color(0xFF4CAF50)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                
                // Control buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Play/Pause button
                    IconButton(
                        onClick = { if (isPlaying) onPauseClick() else onPlayClick() },
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color(0xFF4CAF50)
                        )
                    }
                    
                    // Stop button
                    if (isPlaying || isPaused) {
                        IconButton(
                            onClick = { onStopClick() }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Stop,
                                contentDescription = "Stop",
                                tint = Color(0xFFFF5722)
                            )
                        }
                    }
                }
            }
        }
    }
}