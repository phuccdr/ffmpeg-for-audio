package com.example.android.demo_ffmpeg.util

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linc.amplituda.Amplituda
import com.linc.amplituda.Compress
import com.linc.audiowaveform.AudioWaveform
import com.linc.audiowaveform.infiniteLinearGradient
import com.linc.audiowaveform.model.AmplitudeType
import com.linc.audiowaveform.model.WaveformAlignment
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max
import kotlin.math.min

private const val TAG = "AudioWaveForm"
private const val WAVEFORM_HEIGHT = 120
private const val AMPLITUDE_WINDOW_SIZE = 3
private const val PROGRESS_UPDATE_DELAY = 100L
private const val MIN_ZOOM_LEVEL = 1f
private const val MAX_ZOOM_LEVEL = 10f
private const val SCROLL_FRACTION = 10
private const val GRADIENT_ANIMATION_DURATION = 6000

/**
 * Xử lý biên độ âm thanh để hiển thị tốt hơn trên giao diện
 */
private fun processAmplitudes(amplitudes: List<Int>): List<Int> {
    if (amplitudes.isEmpty()) return emptyList()
    
    val result = mutableListOf<Int>()
    var i = 0
    
    while (i < amplitudes.size) {
        var sum = 0
        var count = 0
        
        for (j in 0 until AMPLITUDE_WINDOW_SIZE) {
            if (i + j < amplitudes.size) {
                sum += amplitudes[i + j]
                count++
            }
        }
        
        if (count > 0) {
            result.add(sum / count)
        }
        
        i += max(1, AMPLITUDE_WINDOW_SIZE / 2)
    }
    
    return result
}

/**
 * Sao chép Uri âm thanh thành file tạm để xử lý
 */
private fun copyUriToTempFile(context: Context, uri: Uri?): File? {
    return try {
        if (uri == null) return null
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val tempFile = File.createTempFile("audio_temp", ".mp3", context.cacheDir)
        tempFile.outputStream().use { outputStream ->
            inputStream.use { input -> 
                input.copyTo(outputStream)
            }
        }
        tempFile
    } catch (e: Exception) {
        Log.e(TAG, "Lỗi khi sao chép URI vào file tạm", e)
        null
    }
}

/**
 * AudioWaveForm Composable hiển thị dạng sóng âm thanh với các điều khiển phát lại
 */
@Composable
fun AudioWaveForm(
    context: Context,
    audioUri: Uri
) {
    // State
    var amplitudes by remember { mutableStateOf<List<Int>>(emptyList()) }
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var zoomLevel by remember { mutableStateOf(MIN_ZOOM_LEVEL) }
    var tempFile by remember { mutableStateOf<File?>(null) }
    
    // UI components
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    
    // Derived state
    val waveformWidth by remember(zoomLevel, screenWidthPx) {
        derivedStateOf { 
            if (zoomLevel <= 1f) screenWidthPx else screenWidthPx * zoomLevel
        }
    }
    
    // MediaPlayer
    val mediaPlayer = remember {
        createMediaPlayer(
            onCompletion = {
                isPlaying = false
                progress = 0f
            },
            onError = {
                isPlaying = false
            }
        )
    }

    // Safely handle current playing state for coroutines
    val currentIsPlaying by rememberUpdatedState(isPlaying)

    // Process audio data
    LaunchedEffect(audioUri) {
        initializeAudio(
            context = context,
            audioUri = audioUri, 
            mediaPlayer = mediaPlayer,
            onAmplitudesReady = { processedAmps ->
                amplitudes = processedAmps
            },
            onPlaybackReset = {
                isPlaying = false
                progress = 0f
            },
            onTempFileCreated = {
                tempFile = it
            }
        )
    }

    // Update progress during playback
    LaunchedEffect(currentIsPlaying) {
        trackPlaybackProgress(
            isPlaying = currentIsPlaying,
            mediaPlayer = mediaPlayer,
            zoomLevel = zoomLevel,
            scrollState = scrollState,
            coroutineScope = coroutineScope,
            onProgressUpdate = { newProgress ->
                progress = newProgress
            }
        )
    }

    // Handle zoom level changes
    LaunchedEffect(zoomLevel) {
        updateScrollPosition(scrollState, zoomLevel, progress)
    }

    // UI Components
    Column(modifier = Modifier.fillMaxSize()) {
        // Waveform display
        WaveformDisplay(
            amplitudes = amplitudes,
            progress = progress,
            zoomLevel = zoomLevel,
            waveformWidth = waveformWidth,
            scrollState = scrollState,
            onProgressChange = { newProgress ->
                handleProgressChange(
                    newProgress = newProgress,
                    mediaPlayer = mediaPlayer,
                    zoomLevel = zoomLevel,
                    scrollState = scrollState,
                    coroutineScope = coroutineScope,
                    onProgressUpdated = { progress = it }
                )
            },
            onProgressChangeFinished = {
                resetPlayback(mediaPlayer, scrollState, coroutineScope) {
                    progress = 0f
                    isPlaying = false
                }
            }
        )

        // Playback controls
        PlaybackControls(
            isPlaying = isPlaying,
            mediaPlayer = mediaPlayer,
            progress = progress,
            onPlayPause = { playing ->
                isPlaying = playing
            }
        )

        // Zoom controls
        ZoomControls(
            zoomLevel = zoomLevel,
            onZoomChange = { newZoom ->
                zoomLevel = newZoom
            }
        )
        
        // Scroll controls (only visible when zoomed in)
        if (zoomLevel > MIN_ZOOM_LEVEL) {
            ScrollControls(scrollState, coroutineScope)
        }
    }

    // Clean up resources
    DisposableEffect(Unit) {
        onDispose {
            cleanUpResources(mediaPlayer, tempFile)
        }
    }
}

/**
 * Tạo và cấu hình MediaPlayer
 */
private fun createMediaPlayer(
    onCompletion: () -> Unit,
    onError: () -> Unit
): MediaPlayer {
    return MediaPlayer().apply {
        setOnCompletionListener {
            onCompletion()
        }
        setOnErrorListener { _, _, _ ->
            onError()
            true
        }
    }
}

/**
 * Khởi tạo âm thanh và xử lý biên độ
 */
private suspend fun initializeAudio(
    context: Context,
    audioUri: Uri,
    mediaPlayer: MediaPlayer,
    onAmplitudesReady: (List<Int>) -> Unit,
    onPlaybackReset: () -> Unit,
    onTempFileCreated: (File) -> Unit
) {
    try {
        onPlaybackReset()
        
        if (mediaPlayer.isPlaying) {
            mediaPlayer.stop()
        }
        mediaPlayer.reset()
        
        val tempFile = copyUriToTempFile(context, audioUri)
        if (tempFile != null && tempFile.exists()) {
            onTempFileCreated(tempFile)
            
            val amplituda = Amplituda(context)
            amplituda
                .processAudio(tempFile, Compress.withParams(Compress.AVERAGE, 20))
                .get(
                    { result -> 
                        val rawAmplitudes = result.amplitudesAsList()
                        onAmplitudesReady(processAmplitudes(rawAmplitudes))
                    },
                    { error -> Log.e(TAG, "Lỗi xử lý biên độ: ${error.message}") }
                )

            mediaPlayer.setDataSource(context, audioUri)
            mediaPlayer.prepareAsync()
        }
    } catch (e: Exception) {
        Log.e(TAG, "Lỗi khởi tạo âm thanh", e)
    }
}

/**
 * Theo dõi tiến trình phát lại
 */
private suspend fun trackPlaybackProgress(
    isPlaying: Boolean,
    mediaPlayer: MediaPlayer,
    zoomLevel: Float,
    scrollState: androidx.compose.foundation.ScrollState,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    onProgressUpdate: (Float) -> Unit
) {
    while (isPlaying && mediaPlayer.isPlaying) {
        if (mediaPlayer.duration > 0) {
            val newProgress = mediaPlayer.currentPosition / mediaPlayer.duration.toFloat()
            onProgressUpdate(newProgress)
            
            if (zoomLevel > MIN_ZOOM_LEVEL) {
                val targetScroll = ((scrollState.maxValue) * newProgress).toInt()
                if (kotlin.math.abs(targetScroll - scrollState.value) > scrollState.maxValue / 20) {
                    coroutineScope.launch {
                        scrollState.animateScrollTo(targetScroll)
                    }
                }
            }
        }
        delay(PROGRESS_UPDATE_DELAY)
    }
}

/**
 * Cập nhật vị trí cuộn khi thay đổi mức zoom
 */
private suspend fun updateScrollPosition(
    scrollState: androidx.compose.foundation.ScrollState, 
    zoomLevel: Float, 
    progress: Float
) {
    if (zoomLevel > MIN_ZOOM_LEVEL) {
        scrollState.scrollTo(((scrollState.maxValue) * progress).toInt())
    } else {
        scrollState.scrollTo(0)
    }
}

@Composable
private fun WaveformDisplay(
    amplitudes: List<Int>,
    progress: Float,
    zoomLevel: Float,
    waveformWidth: Float,
    scrollState: androidx.compose.foundation.ScrollState,
    onProgressChange: (Float) -> Unit,
    onProgressChangeFinished: () -> Unit
) {
    val animatedGradientBrush = Brush.infiniteLinearGradient(
        colors = listOf(Color(0xff22c1c3), Color(0xfffdbb2d)),
        animation = tween(durationMillis = GRADIENT_ANIMATION_DURATION, easing = LinearEasing),
        width = 128F
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(WAVEFORM_HEIGHT.dp)
    ) {
        // Sample the amplitudes based on zoom level
        val sampleStep by remember(zoomLevel) {
            derivedStateOf { 
                max(1, (4 / zoomLevel).toInt())
            }
        }
        
        // Sample amplitudes more precisely when zoomed in
        val displayedAmplitudes by remember(amplitudes, zoomLevel, sampleStep) {
            derivedStateOf {
                if (amplitudes.isEmpty()) {
                    emptyList()
                } else {
                    amplitudes.filterIndexed { index, _ -> index % max(1, sampleStep / 2) == 0 }
                }
            }
        }
        
        Box(
            modifier = if (zoomLevel > MIN_ZOOM_LEVEL) 
                Modifier
                    .fillMaxWidth()
                    .height(WAVEFORM_HEIGHT.dp)
                    .horizontalScroll(scrollState)
            else 
                Modifier
                    .fillMaxWidth()
                    .height(WAVEFORM_HEIGHT.dp)
        ) {
            AudioWaveform(
                modifier = if (zoomLevel > MIN_ZOOM_LEVEL) 
                    Modifier
                        .width(with(LocalDensity.current) { waveformWidth.toDp() })
                        .height(WAVEFORM_HEIGHT.dp)
                else
                    Modifier
                        .fillMaxWidth()
                        .height(WAVEFORM_HEIGHT.dp),
                amplitudes = displayedAmplitudes,
                progress = progress,
                style = Fill,
                spikeWidth = 2.dp,
                spikePadding = if (zoomLevel > MIN_ZOOM_LEVEL) (0.5 * zoomLevel).dp else 0.5.dp,
                spikeRadius = 1.dp,
                waveformAlignment = WaveformAlignment.Center,
                amplitudeType = AmplitudeType.Avg,
                waveformBrush = SolidColor(Color.LightGray),
                progressBrush = animatedGradientBrush,
                onProgressChange = onProgressChange,
                onProgressChangeFinished = onProgressChangeFinished
            )
        }
    }
}

/**
 * Điều khiển phát nhạc
 */
@Composable
private fun PlaybackControls(
    isPlaying: Boolean,
    mediaPlayer: MediaPlayer,
    progress: Float,
    onPlayPause: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Button(onClick = {
            try {
                if (mediaPlayer.isPlaying) {
                    mediaPlayer.pause()
                    onPlayPause(false)
                } else {
                    if (progress == 0f) {
                        mediaPlayer.seekTo(0)
                    }
                    mediaPlayer.start()
                    onPlayPause(true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi điều khiển phát lại", e)
            }
        }) {
            Text(if (isPlaying) "Tạm dừng" else "Phát")
        }
    }
}

/**
 * Điều khiển mức zoom
 */
@Composable
private fun ZoomControls(
    zoomLevel: Float,
    onZoomChange: (Float) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        IconButton(
            onClick = { onZoomChange(max(zoomLevel - 1, MIN_ZOOM_LEVEL)) },
            enabled = zoomLevel > MIN_ZOOM_LEVEL
        ) {
            Icon(
                imageVector = Icons.Default.ZoomOut,
                contentDescription = "Thu nhỏ",
                tint = if (zoomLevel > MIN_ZOOM_LEVEL) Color.Green else Color.Gray
            )
        }
        
        Spacer(modifier = Modifier.width(4.dp))
        
        Text(
            text = "${zoomLevel.toInt()}x",
            modifier = Modifier
                .padding(4.dp)
                .align(Alignment.CenterVertically),
            fontSize = 12.sp
        )
        
        Spacer(modifier = Modifier.width(4.dp))
        
        IconButton(
            onClick = { onZoomChange(min(zoomLevel + 1, MAX_ZOOM_LEVEL)) },
            enabled = zoomLevel < MAX_ZOOM_LEVEL
        ) {
            Icon(
                imageVector = Icons.Default.ZoomIn,
                contentDescription = "Phóng to",
                tint = if (zoomLevel < MAX_ZOOM_LEVEL) Color.Green else Color.Gray
            )
        }
    }
}

/**
 * Điều khiển cuộn
 */
@Composable
private fun ScrollControls(
    scrollState: androidx.compose.foundation.ScrollState,
    coroutineScope: kotlinx.coroutines.CoroutineScope
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        IconButton(
            onClick = { 
                coroutineScope.launch {
                    val newPosition = max(0, scrollState.value - (scrollState.maxValue / SCROLL_FRACTION))
                    scrollState.animateScrollTo(newPosition)
                }
            }
        ) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "Cuộn trái",
                tint = Color.Green
            )
        }
        
        Spacer(modifier = Modifier.width(32.dp))
        
        IconButton(
            onClick = { 
                coroutineScope.launch {
                    val newPosition = min(scrollState.maxValue, scrollState.value + (scrollState.maxValue / SCROLL_FRACTION))
                    scrollState.animateScrollTo(newPosition)
                }
            }
        ) {
            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = "Cuộn phải",
                tint = Color.Green
            )
        }
    }
}

/**
 * Xử lý khi thay đổi tiến trình
 */
private fun handleProgressChange(
    newProgress: Float,
    mediaPlayer: MediaPlayer,
    zoomLevel: Float,
    scrollState: androidx.compose.foundation.ScrollState,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    onProgressUpdated: (Float) -> Unit
) {
    try {
        if (mediaPlayer.duration > 0) {
            onProgressUpdated(newProgress)
            val newPosition = (mediaPlayer.duration * newProgress).toInt()
            mediaPlayer.seekTo(newPosition)
            
            if (zoomLevel > MIN_ZOOM_LEVEL) {
                coroutineScope.launch {
                    scrollState.animateScrollTo(((scrollState.maxValue) * newProgress).toInt())
                }
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Lỗi khi di chuyển đến vị trí", e)
    }
}

/**
 * Đặt lại trạng thái phát nhạc
 */
private fun resetPlayback(
    mediaPlayer: MediaPlayer,
    scrollState: androidx.compose.foundation.ScrollState,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    onReset: () -> Unit
) {
    try {
        if (mediaPlayer.isPlaying) {
            mediaPlayer.pause()
        }
        mediaPlayer.seekTo(0)
        onReset()
        
        coroutineScope.launch {
            scrollState.animateScrollTo(0)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Lỗi khi đặt lại trạng thái phát nhạc", e)
    }
}

/**
 * Dọn dẹp tài nguyên
 */
private fun cleanUpResources(mediaPlayer: MediaPlayer, tempFile: File?) {
    try {
        if (mediaPlayer.isPlaying) {
            mediaPlayer.stop()
        }
        mediaPlayer.release()
        
        tempFile?.let {
            if (it.exists()) {
                it.delete()
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Lỗi khi dọn dẹp tài nguyên", e)
    }
}
