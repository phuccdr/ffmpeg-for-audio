package com.example.android.demo_ffmpeg.util
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.*

// Data class để lưu thông tin âm thanh
data class WaveformData(
    val sampleRate: Int,                    // Tỷ lệ mẫu (Hz)
    val samplesPerPixel: Int,               // Số mẫu trên mỗi pixel
    val samples: List<Float>,               // Dữ liệu âm thanh
    val durationMs: Long                    // Thời lượng (ms)
) {
    fun getSamplePerMs(): Double = samples.size / durationMs.toDouble()
}

// Utility functions tương tự WaveUtil
object WaveformUtil {
    fun dataPixelsToTime(dataPixels: Int, sampleRate: Int, samplesPerPixel: Int): Long {
        return (dataPixels.toLong() * samplesPerPixel / sampleRate)
    }

    fun timeToPixels(milliseconds: Long, sampleRate: Int, samplesPerPixel: Int, scale: Float): Int {
        return (milliseconds * sampleRate / samplesPerPixel * scale).toInt()
    }

    fun pixelsToTime(pixels: Float, sampleRate: Int, samplesPerPixel: Int, scale: Float): Long {
        return (pixels * samplesPerPixel / (sampleRate * scale)).toLong()
    }
}

@Composable
fun WaveformSelectionView(
    waveformData: WaveformData,
    modifier: Modifier = Modifier,
    startTimeMs: Long = 0L,
    scale: Float = 1f,
    selectionStartMs: Long = 1000L,
    selectionEndMs: Long = 5000L,
    onSelectionChanged: (Long, Long) -> Unit = { _, _ -> }
) {
    val density = LocalDensity.current

    // State variables
    var currentSelectionStart by remember { mutableLongStateOf(selectionStartMs) }
    var currentSelectionEnd by remember { mutableLongStateOf(selectionEndMs) }
    var isDraggingStart by remember { mutableStateOf(false) }
    var isDraggingEnd by remember { mutableStateOf(false) }
    var dragStartTime by remember { mutableLongStateOf(0L) }
    var initialDragX by remember { mutableFloatStateOf(0f) }

    // Colors
    val waveformColor = Color.Red
    val selectionColor = Color.Yellow
    val maskColor = Color.Yellow.copy(alpha = 0.3f)

    // Handler size
    val handlerSize = with(density) { 50.dp.toPx() }
    val cursorWidth = with(density) { 2.dp.toPx() }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .clipToBounds()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        // Tính toán vị trí của các thanh kéo
                        val startPixel = calculateHandlerPosition(
                            currentSelectionStart,
                            startTimeMs,
                            waveformData.getSamplePerMs(),
                            scale
                        )
                        val endPixel = calculateHandlerPosition(
                            currentSelectionEnd,
                            startTimeMs,
                            waveformData.getSamplePerMs(),
                            scale
                        )

                        // Kiểm tra xem đang chạm vào thanh nào
                        when {
                            abs(offset.x - startPixel) <= handlerSize -> {
                                isDraggingStart = true
                                isDraggingEnd = false
                                dragStartTime = startTimeMs + WaveformUtil.pixelsToTime(
                                    offset.x,
                                    waveformData.sampleRate,
                                    waveformData.samplesPerPixel,
                                    scale
                                )
                                initialDragX = offset.x
                            }
                            abs(offset.x - endPixel) <= handlerSize -> {
                                isDraggingStart = false
                                isDraggingEnd = true
                                dragStartTime = startTimeMs + WaveformUtil.pixelsToTime(
                                    offset.x,
                                    waveformData.sampleRate,
                                    waveformData.samplesPerPixel,
                                    scale
                                )
                                initialDragX = offset.x
                            }
                        }
                    },
                    onDrag = { change, dragAmount ->
                        when {
                            isDraggingStart -> {
                                // Sử dụng dragAmount.x để có hiệu suất tốt hơn
                                val timeChange = WaveformUtil.pixelsToTime(
                                    dragAmount.x,
                                    waveformData.sampleRate,
                                    waveformData.samplesPerPixel,
                                    scale
                                )
                                currentSelectionStart = (currentSelectionStart + timeChange).coerceIn(
                                    0L,
                                    currentSelectionEnd
                                )
                                onSelectionChanged(currentSelectionStart, currentSelectionEnd)
                            }
                            isDraggingEnd -> {
                                // Sử dụng dragAmount.x để có hiệu suất tốt hơn
                                val timeChange = WaveformUtil.pixelsToTime(
                                    dragAmount.x,
                                    waveformData.sampleRate,
                                    waveformData.samplesPerPixel,
                                    scale
                                )
                                currentSelectionEnd = (currentSelectionEnd + timeChange).coerceIn(
                                    currentSelectionStart,
                                    waveformData.durationMs
                                )
                                onSelectionChanged(currentSelectionStart, currentSelectionEnd)
                            }
                        }
                    },
                    onDragEnd = {
                        isDraggingStart = false
                        isDraggingEnd = false
                    }
                )
            }
    ) {
        // Vẽ waveform
        drawWaveform(waveformData, startTimeMs, scale, waveformColor)

        // Vẽ vùng selection
        drawSelectionArea(
            waveformData,
            startTimeMs,
            scale,
            currentSelectionStart,
            currentSelectionEnd,
            selectionColor,
            maskColor,
            handlerSize,
            cursorWidth
        )
    }
}

// Hàm tính toán vị trí pixel của thanh kéo
private fun calculateHandlerPosition(
    selectionTime: Long,
    startTime: Long,
    samplePerMs: Double,
    scale: Float
): Float {
    val relativeTime = selectionTime - startTime
    return (samplePerMs * relativeTime * scale).toFloat()
}

// Hàm vẽ waveform
private fun DrawScope.drawWaveform(
    waveformData: WaveformData,
    startTimeMs: Long,
    scale: Float,
    color: Color
) {
    val height = size.height
    val width = size.width

    // Tính toán vị trí bắt đầu trong dữ liệu
    val startDataIndex = (waveformData.getSamplePerMs() * startTimeMs).toInt()

    // Vẽ các đường waveform
    var x = 0f
    var dataIndex = startDataIndex

    while (x < width && dataIndex < waveformData.samples.size) {
        val sample = waveformData.samples.getOrNull(dataIndex) ?: 0f
        val amplitude = sample * height / 2

        // Vẽ đường từ center lên trên và xuống dưới
        drawLine(
            color = color,
            start = Offset(x, height / 2 - amplitude / 2),
            end = Offset(x, height / 2 + amplitude / 2),
            strokeWidth = 1.dp.toPx()
        )

        x += scale
        dataIndex++
    }
}

// Hàm vẽ vùng selection và các thanh kéo
private fun DrawScope.drawSelectionArea(
    waveformData: WaveformData,
    startTimeMs: Long,
    scale: Float,
    selectionStartMs: Long,
    selectionEndMs: Long,
    selectionColor: Color,
    maskColor: Color,
    handlerSize: Float,
    cursorWidth: Float
) {
    val height = size.height
    val width = size.width

    // Tính toán vị trí pixel của các thanh
    val startPixel = calculateHandlerPosition(selectionStartMs, startTimeMs, waveformData.getSamplePerMs(), scale)
    val endPixel = calculateHandlerPosition(selectionEndMs, startTimeMs, waveformData.getSamplePerMs(), scale)

    // Vẽ mask bên trái (vùng không được chọn)
    if (startPixel > 0) {
        drawRect(
            color = maskColor,
            topLeft = Offset(0f, 0f),
            size = androidx.compose.ui.geometry.Size(startPixel, height)
        )
    }

    // Vẽ mask bên phải (vùng không được chọn)
    if (endPixel < width) {
        drawRect(
            color = maskColor,
            topLeft = Offset(endPixel, 0f),
            size = androidx.compose.ui.geometry.Size(width - endPixel, height)
        )
    }

    // Vẽ thanh kéo start
    if (startPixel >= 0 && startPixel <= width) {
        // Đường thẳng dọc
        drawLine(
            color = selectionColor,
            start = Offset(startPixel, 0f),
            end = Offset(startPixel, height),
            strokeWidth = cursorWidth
        )

    }

    // Vẽ thanh kéo end
    if (endPixel >= 0 && endPixel <= width) {
        // Đường thẳng dọc
        drawLine(
            color = selectionColor,
            start = Offset(endPixel, 0f),
            end = Offset(endPixel, height),
            strokeWidth = cursorWidth
        )
    }
}