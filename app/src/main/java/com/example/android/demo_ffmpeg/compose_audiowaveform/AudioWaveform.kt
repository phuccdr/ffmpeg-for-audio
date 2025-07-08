package com.example.android.demo_ffmpeg.compose_audiowaveform


import android.util.Log
import android.view.MotionEvent
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import com.example.android.demo_ffmpeg.compose_audiowaveform.model.AmplitudeType
import com.example.android.demo_ffmpeg.compose_audiowaveform.model.WaveformAlignment

private val MinSpikeWidthDp: Dp = 1.dp
private val MaxSpikeWidthDp: Dp = 24.dp
private val MinSpikePaddingDp: Dp = 0.dp
private val MaxSpikePaddingDp: Dp = 12.dp
private val MinSpikeRadiusDp: Dp = 0.dp
private val MaxSpikeRadiusDp: Dp = 12.dp

private const val MinProgress: Float = 0F
private const val MaxProgress: Float = 1F

private const val MinSpikeHeight: Float = 1F
private const val DefaultGraphicsLayerAlpha: Float = 0.99F

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AudioWaveform(
    modifier: Modifier = Modifier,
    style: DrawStyle = Fill,
    waveformBrush: Brush = SolidColor(Color.White),
    progressBrush: Brush = SolidColor(Color.Blue),
    waveformAlignment: WaveformAlignment = WaveformAlignment.Center,
    amplitudeType: AmplitudeType = AmplitudeType.Avg,
    onProgressChangeFinished: (() -> Unit)? = null,
    spikeAnimationSpec: AnimationSpec<Float> = tween(500),
    spikeWidth: Dp = 4.dp,
    spikeRadius: Dp = 2.dp,
    spikePadding: Dp = 1.dp,
    progress: Float = 0F,
    amplitudes: List<Int>,
    onProgressChange: (Float) -> Unit,
    onDragTrim: (Float,Float) ->Unit,
    trimStartProgress: Float = 0.25f,  // Trim start position (0-1)
    trimEndProgress: Float = 0.75f     // Trim end position (0-1)
) {
    val _progress = remember(progress) { progress.coerceIn(MinProgress, MaxProgress) }
    Log.d("progress", _progress.toString())
    val _spikeWidth = remember(spikeWidth) { spikeWidth.coerceIn(MinSpikeWidthDp, MaxSpikeWidthDp) }
    val _spikePadding = remember(spikePadding) { spikePadding.coerceIn(MinSpikePaddingDp, MaxSpikePaddingDp) }
    val _spikeRadius = remember(spikeRadius) { spikeRadius.coerceIn(MinSpikeRadiusDp, MaxSpikeRadiusDp) }
    val _spikeTotalWidth = remember(spikeWidth, spikePadding) { _spikeWidth + _spikePadding }
    var canvasSize by remember { mutableStateOf(Size(0f, 0f)) }
    var spikes by remember { mutableStateOf(0F) }
    val spikesAmplitudes = remember(amplitudes, spikes, amplitudeType) {
        amplitudes.toDrawableAmplitudes(
            amplitudeType = amplitudeType,
            spikes = spikes.toInt(),
            minHeight = MinSpikeHeight,
            maxHeight = canvasSize.height.coerceAtLeast(MinSpikeHeight)
        )
    }.map { animateFloatAsState(it, spikeAnimationSpec).value }
    
    // Trim lines state
    var startPixelX by remember { mutableStateOf(0f) }
    var endPixelX by remember { mutableStateOf(0f) }
    
    // Drag state
    var isDragging by remember { mutableStateOf(false) }
    var dragTarget by remember { mutableStateOf<DragTarget?>(null) }
    
    // Touch tolerance for detecting line hits
    val density = LocalDensity.current
    val touchTolerance = with(density) { 20.dp.toPx() }
    
    Canvas(
        modifier = Modifier
            .width(400.dp)
            .requiredHeight(48.dp)
            .graphicsLayer(alpha = DefaultGraphicsLayerAlpha)
//            .pointerInteropFilter {
//                return@pointerInteropFilter when (it.action) {
//                    MotionEvent.ACTION_DOWN,
//                    MotionEvent.ACTION_MOVE -> {
//                        // Chỉ xử lý progress khi không đang drag trim lines
//                        if (!isDragging && it.x in 0F..canvasSize.width) {
//                            onProgressChange(it.x / canvasSize.width)
//                            true
//                        } else false
//                    }
//                    MotionEvent.ACTION_UP -> {
//                        if (!isDragging) {
//                            onProgressChangeFinished?.invoke()
//                        }
//                        true
//                    }
//                    else -> false
//                }
//            }
            .pointerInput(canvasSize) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val x = offset.x
                        
                        // Kiểm tra xem touch có gần line nào không
                        when {
                            kotlin.math.abs(x - startPixelX) <= touchTolerance -> {
                                dragTarget = DragTarget.START
                                isDragging = true
                                Log.d("AudioWaveform", "Start dragging START line at x=$x")
                            }
                            kotlin.math.abs(x - endPixelX) <= touchTolerance -> {
                                dragTarget = DragTarget.END
                                isDragging = true
                                Log.d("AudioWaveform", "Start dragging END line at x=$x")
                            }
                            else -> {
                                dragTarget = null
                                isDragging = false
                            }
                        }
                    },
                    onDrag = { change: PointerInputChange, dragAmount: Offset ->
                        if (isDragging && dragTarget != null) {
                            when (dragTarget) {
                                DragTarget.START -> {
                                    val newStartX = (startPixelX + dragAmount.x)
                                        .coerceIn(0f, endPixelX - 50f) // Đảm bảo không vượt quá endPixelX
                                    startPixelX = newStartX
                                    
                                    // Call callback với tỷ lệ (0-1)
                                    val startProgress = startPixelX / canvasSize.width
                                    val endProgress = endPixelX / canvasSize.width
                                    onDragTrim(startProgress, endProgress)
                                    
                                    Log.d("AudioWaveform", "Dragging START to x=$newStartX, progress=$startProgress")
                                }
                                DragTarget.END -> {
                                    val newEndX = (endPixelX + dragAmount.x)
                                        .coerceIn(startPixelX + 50f, canvasSize.width) // Đảm bảo không nhỏ hơn startPixelX
                                    endPixelX = newEndX
                                    
                                    // Call callback với tỷ lệ (0-1)
                                    val startProgress = startPixelX / canvasSize.width
                                    val endProgress = endPixelX / canvasSize.width
                                    onDragTrim(startProgress, endProgress)
                                    
                                    Log.d("AudioWaveform", "Dragging END to x=$newEndX, progress=$endProgress")
                                }
                                null -> { /* Do nothing */ }
                            }
                        }
                    },
                    onDragEnd = {
                        if (isDragging) {
                            Log.d("AudioWaveform", "Drag ended")
                            isDragging = false
                            dragTarget = null
                            
                            // Final callback khi kết thúc drag
                            val startProgress = startPixelX / canvasSize.width
                            val endProgress = endPixelX / canvasSize.width
                            onDragTrim(startProgress, endProgress)
                        }
                    }
                )
            }
            .then(modifier)
    ) {
        canvasSize = size
        
        // Khởi tạo startPixelX và endPixelX từ trim progress hoặc lần đầu
        if (startPixelX == 0f && endPixelX == 0f) {
            startPixelX = trimStartProgress * size.width
            endPixelX = trimEndProgress * size.width
        }
        
        spikes = size.width / _spikeTotalWidth.toPx()
        spikesAmplitudes.forEachIndexed { index, amplitude ->
            drawRoundRect(
                brush = waveformBrush,
                topLeft = Offset(
                    x = index * _spikeTotalWidth.toPx(),
                    y = when(waveformAlignment) {
                        WaveformAlignment.Top -> 0F
                        WaveformAlignment.Bottom -> size.height - amplitude
                        WaveformAlignment.Center -> size.height / 2F - amplitude / 2F
                    }
                ),
                size = Size(
                    width = _spikeWidth.toPx(),
                    height = amplitude
                ),
                cornerRadius = CornerRadius(_spikeRadius.toPx(), _spikeRadius.toPx()),
                style = style
            )
        }
        
        // Vẽ vùng trim (highlight)
        drawRect(
            brush = progressBrush,
            topLeft = Offset(startPixelX, 0f),
            size = Size(
                width = endPixelX - startPixelX,
                height = size.height
            ),
            blendMode = BlendMode.SrcAtop
        )
        
        // Vẽ line progress (playback position)
        drawLine(
            color = Color.White,
            start = Offset(_progress * size.width, 0f),
            end = Offset(_progress * size.width, size.height),
            strokeWidth = 1.dp.toPx()
        )
        
        // Vẽ start trim line với visual feedback khi drag
        drawLine(
            color = if (dragTarget == DragTarget.START) Color.Red else Color.White,
            start = Offset(startPixelX, 0f),
            end = Offset(startPixelX, size.height),
            strokeWidth = if (dragTarget == DragTarget.START) 3.dp.toPx() else 2.dp.toPx()
        )
        
        // Vẽ end trim line với visual feedback khi drag  
        drawLine(
            color = if (dragTarget == DragTarget.END) Color.Red else Color.White,
            start = Offset(endPixelX, 0f),
            end = Offset(endPixelX, size.height),
            strokeWidth = if (dragTarget == DragTarget.END) 3.dp.toPx() else 2.dp.toPx()
        )
        
        // Vẽ handle để dễ grab (optional - tạo vùng grab dễ hơn)
        val handleSize = 8.dp.toPx()
        val handleY = size.height / 2
        
        // Start handle
        drawCircle(
            color = if (dragTarget == DragTarget.START) Color.Red else Color.White,
            radius = handleSize / 2,
            center = Offset(startPixelX, handleY)
        )
        
        // End handle  
        drawCircle(
            color = if (dragTarget == DragTarget.END) Color.Red else Color.White,
            radius = handleSize / 2,
            center = Offset(endPixelX, handleY)
        )
    }
}

// Enum để track line nào đang được drag
private enum class DragTarget {
    START, END
}

private fun List<Int>.toDrawableAmplitudes(
    amplitudeType: AmplitudeType,
    spikes: Int,
    minHeight: Float,
    maxHeight: Float
): List<Float> {
    val amplitudes = map(Int::toFloat)
    if(amplitudes.isEmpty() || spikes == 0) {
        return List(spikes) { minHeight }
    }
    val transform = { data: List<Float> ->
        when(amplitudeType) {
            AmplitudeType.Avg -> data.average()
            AmplitudeType.Max -> data.max()
            AmplitudeType.Min -> data.min()
        }.toFloat().coerceIn(minHeight, maxHeight)
    }
    return when {
        spikes > amplitudes.count() -> amplitudes.fillToSize(spikes, transform)
        else -> amplitudes.chunkToSize(spikes, transform)
    }.normalize(minHeight, maxHeight)
}