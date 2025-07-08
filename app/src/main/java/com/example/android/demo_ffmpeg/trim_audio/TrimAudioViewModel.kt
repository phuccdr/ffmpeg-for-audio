package com.example.android.demo_ffmpeg.trim_audio

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.example.android.demo_ffmpeg.util.AudioFileInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.abs

data class AudioTrimState(
    val isProcessing: Boolean = false,
    val progress: String = "",
    val outputFilePath: String? = null,
    val outputUri: Uri? = null,
    val error: String? = null,
    val isPlaying: Boolean = false,
    val currentPlaybackPosition: Float = 0f,
    var playerProgress: Float = 0f,
    var audioInfo: AudioFileInfo? = null,
    var amplitude: List<Int> = emptyList(),
    var currentPlayAudioPosition: Float = 0f
)

class TrimAudioViewModel : ViewModel() {
    private val _state = MutableStateFlow(AudioTrimState())
    val state: StateFlow<AudioTrimState> = _state.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var progressUpdateJob: kotlinx.coroutines.Job? = null

    companion object {
        private const val TAG = "TrimAudioViewModel"
        private const val SAMPLES_PER_SECOND = 10 // 10 điểm sample per giây
        private const val MAX_SAMPLES_FOR_LARGE_FILES = 500 // Giới hạn cho file lớn
        private const val LARGE_FILE_SIZE_THRESHOLD = 50 * 1024 * 1024 // 50MB
        private const val BUFFER_SIZE_SMALL = 32 * 1024 // 32KB cho file nhỏ
        private const val BUFFER_SIZE_LARGE = 128 * 1024 // 128KB cho file lớn
        private const val EXTREME_FILE_SIZE_THRESHOLD = 100 * 1024 * 1024 // 100MB - file cực lớn
        private const val MIN_SAMPLES_FOR_EXTREME_FILES = 100 // Tối thiểu cho file cực lớn
    }

    fun getAudioFileInfo(uri: Uri, context: Context) {
        resetState()
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(
                    isProcessing = true,
                    progress = "Đang đọc thông tin file audio..."
                )
                Log.d(TAG, "Reading audio file info...")
                
                // Lấy tên file từ URI
                val fileName = getFileNameFromUri(uri, context)

                // Lấy thời lượng audio
                val duration = getAudioDuration(uri, context)

                // Tính số sample dựa trên thời lượng và kích thước file
                val durationInSeconds = (duration / 1000f).toInt()
                val sampleCount = calculateOptimalSampleCount(durationInSeconds, uri, context)
                
                Log.d(TAG, "Audio duration: ${durationInSeconds}s, optimal sample count: $sampleCount")

                // Tạo dữ liệu waveform
                _state.value = _state.value.copy(progress = "Đang tạo waveform...")
                val waveformData = generateWaveformData(uri, context, sampleCount)

                // Tạo AudioFileInfo object với đầy đủ thông tin
                val audioInfo = AudioFileInfo(
                    name = fileName,
                    startTime = 0f,
                    endTime = duration,
                    duration = duration,
                    amplitude = waveformData,
                    audioPath = uri,
                    index = 0
                )
                Log.d(TAG, "Audio info loaded successfully: $audioInfo")

                // Cập nhật state với AudioFileInfo
                _state.value = _state.value.copy(
                    isProcessing = false,
                    progress = "Hoàn thành!",
                    audioInfo = audioInfo,
                    error = null
                )

                Log.d(TAG, "Audio info loaded successfully: $fileName, duration: ${duration}ms")

            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isProcessing = false,
                    progress = "",
                    error = "Không thể đọc thông tin file audio: ${e.message}"
                )
                Log.e(TAG, "Error reading audio file", e)
            }
        }
    }

    /**
     * Tính toán số sample tối ưu dựa trên thời lượng và kích thước file
     * để tránh OutOfMemoryError với file lớn
     */
    private suspend fun calculateOptimalSampleCount(durationInSeconds: Int, uri: Uri, context: Context): Int {
        return withContext(Dispatchers.IO) {
            try {
                // Ước tính kích thước file
                val fileSize = getFileSize(uri, context)
                
                val baseSampleCount = durationInSeconds * SAMPLES_PER_SECOND
                
                // Điều chỉnh số sample cho file lớn
                val optimalSampleCount = when {
                    fileSize > EXTREME_FILE_SIZE_THRESHOLD -> {
                        // File cực lớn (>100MB): giảm mạnh số sample
                        val extremelySafeCount = minOf(baseSampleCount, MIN_SAMPLES_FOR_EXTREME_FILES)
                        Log.d(TAG, "Extremely large file detected (${fileSize / 1024 / 1024}MB), using minimal samples: $extremelySafeCount")
                        extremelySafeCount
                    }
                    fileSize > LARGE_FILE_SIZE_THRESHOLD -> {
                        // File lớn: giảm density của sample
                        val reducedSamples = minOf(baseSampleCount, MAX_SAMPLES_FOR_LARGE_FILES)
                        Log.d(TAG, "Large file detected (${fileSize / 1024 / 1024}MB), reducing samples to $reducedSamples")
                        reducedSamples
                    }
                    durationInSeconds > 300 -> {
                        // File dài (>5 phút): giảm sample rate
                        val reducedSamples = minOf(baseSampleCount, durationInSeconds * 5)
                        Log.d(TAG, "Long duration file (${durationInSeconds}s), reducing samples to $reducedSamples")
                        reducedSamples
                    }
                    else -> baseSampleCount
                }
                
                Log.d(TAG, "File size: ${fileSize / 1024 / 1024}MB, duration: ${durationInSeconds}s, samples: $optimalSampleCount")
                return@withContext optimalSampleCount
                
            } catch (e: Exception) {
                Log.e(TAG, "Error calculating optimal sample count", e)
                return@withContext durationInSeconds * SAMPLES_PER_SECOND
            }
        }
    }

    /**
     * Lấy kích thước file từ URI
     */
    private fun getFileSize(uri: Uri, context: Context): Long {
        return try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val sizeIndex = it.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    if (sizeIndex != -1) {
                        return it.getLong(sizeIndex)
                    }
                }
            }
            // Fallback: estimate from content resolver
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.available().toLong()
            } ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file size", e)
            0L
        }
    }

    /**
     * Tạo dữ liệu waveform từ file audio sử dụng FFmpeg để trích xuất PCM data
     */
    private fun generateWaveformData(uri: Uri, context: Context, sampleCount: Int): List<Float> {
        return try {
            Log.d(TAG, "Generating waveform data using FFmpeg for $sampleCount samples")

            // Tạo thư mục tạm cho xử lý
            val tempDir = File(context.cacheDir, "waveform_temp")
            if (!tempDir.exists()) {
                tempDir.mkdirs()
            }

            // Copy input file to temp directory
            val inputFile = File(tempDir, "input_waveform.mp3")
            val pcmFile = File(tempDir, "output_waveform.pcm")

            try {
                // Copy URI to local file
                copyUriToFile(context, uri, inputFile)

                // Sử dụng FFmpeg để chuyển đổi audio thành PCM 16-bit mono
                val ffmpegCommand = "-y -i ${inputFile.absolutePath} " +
                        "-f s16le " +        // 16-bit signed little endian PCM
                        "-acodec pcm_s16le " + // PCM codec
                        "-ac 1 " +           // Mono (1 channel)
                        "-ar 44100 " +       // Sample rate 44.1kHz
                        "${pcmFile.absolutePath}"

                Log.d(TAG, "Executing FFmpeg command for waveform: $ffmpegCommand")

                val session = FFmpegKit.execute(ffmpegCommand)
                val returnCode = session.returnCode

                if (!ReturnCode.isSuccess(returnCode)) {
                    Log.e(TAG, "FFmpeg failed to generate PCM data: $returnCode")
                    Log.e(TAG, "FFmpeg logs: ${session.allLogsAsString}")
                    return generateFallbackWaveform(sampleCount)
                }

                if (!pcmFile.exists() || pcmFile.length() == 0L) {
                    Log.e(TAG, "PCM file not created or is empty")
                    return generateFallbackWaveform(sampleCount)
                }

                Log.d(TAG, "PCM file generated successfully: ${pcmFile.length()} bytes")

                // Đọc PCM data và tính toán amplitude
                val waveformData = processRawPCMData(pcmFile, sampleCount)

                generateAmplitudeList(pcmFile, sampleCount)

                // Cleanup temp files
                inputFile.delete()
                pcmFile.delete()

                Log.d(TAG, "Waveform generation completed with ${waveformData.size} samples")
                return waveformData

            } catch (e: Exception) {
                Log.e(TAG, "Error in waveform generation process", e)
                // Cleanup on error
                inputFile.delete()
                pcmFile.delete()
                return generateFallbackWaveform(sampleCount)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error generating waveform data with FFmpeg", e)
            return generateFallbackWaveform(sampleCount)
        }
    }

    /**
     * Tạo amplitude list tương tự thư viện Amplituda
     * Trả về List<Int> với các giá trị amplitude được normalize từ 0-100
     * Sử dụng stream processing để tránh OutOfMemoryError với file lớn
     */
    private fun generateAmplitudeList(pcmFile: File, sampleCount: Int) {
        try {
            if (!pcmFile.exists() || pcmFile.length() == 0L) {
                Log.w(TAG, "PCM file is empty or doesn't exist, using fallback amplitude")
                setFallbackAmplitude(sampleCount)
                return
            }

            Log.d(TAG, "Processing PCM file with stream: ${pcmFile.length()} bytes")
            
            // Sử dụng stream processing thay vì đọc toàn bộ file
            val amplitudeList = processAmplitudesWithStream(pcmFile, sampleCount)
            
            Log.d(TAG, "Generated amplitude list with ${amplitudeList.size} values")
            Log.d(TAG, "Amplitude range: ${amplitudeList.minOrNull()} - ${amplitudeList.maxOrNull()}")

            // Cập nhật state với amplitude data
            _state.value = _state.value.copy(
                amplitude = amplitudeList,
            )
            _state.value.audioInfo = _state.value.audioInfo?.copy(
                amplitudeInt = amplitudeList
            )
            Log.d(TAG, "Amplitude data updated: ${state.value.audioInfo?.amplitudeInt}")
            Log.d(TAG, "Amplitude data updated: ${state.value.amplitude}")

        } catch (e: Exception) {
            Log.e(TAG, "Error generating amplitude list", e)
            setFallbackAmplitude(sampleCount)
        }
    }

    /**
     * Xử lý amplitude với stream processing để tránh OutOfMemoryError
     * Đọc file PCM theo chunk nhỏ thay vì load toàn bộ vào memory
     */
    private fun processAmplitudesWithStream(pcmFile: File, targetCount: Int): List<Int> {
        // Chọn buffer size phù hợp với kích thước file
        val chunkSize = if (pcmFile.length() > LARGE_FILE_SIZE_THRESHOLD) {
            BUFFER_SIZE_LARGE
        } else {
            BUFFER_SIZE_SMALL
        }
        
        val totalBytes = pcmFile.length()
        val totalSamples = (totalBytes / 2).toInt() // 16-bit = 2 bytes per sample
        
        if (totalSamples == 0) {
            return List(targetCount) { 0 }
        }

        val samplesPerBucket = maxOf(1, totalSamples / targetCount)
        val amplitudes = mutableListOf<Int>()
        
        Log.d(TAG, "Stream processing: $totalBytes bytes, $totalSamples samples, $samplesPerBucket samples per bucket, chunk size: $chunkSize")
        
        try {
            pcmFile.inputStream().buffered(chunkSize).use { inputStream ->
                val buffer = ByteArray(chunkSize)
                var bytesRead: Int
                var totalSamplesProcessed = 0
                var currentBucket = 0
                var currentBucketPeak = 0
                var samplesInCurrentBucket = 0
                
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    // Xử lý từng chunk
                    var i = 0
                    while (i < bytesRead - 1) {
                        // Đọc 16-bit signed sample (little endian)
                        val low = buffer[i].toInt() and 0xFF
                        val high = buffer[i + 1].toInt()
                        var sample = (high shl 8) or low
                        if (sample > 32767) {
                            sample -= 65536
                        }
                        
                        // Cập nhật peak cho bucket hiện tại
                        val absSample = abs(sample)
                        if (absSample > currentBucketPeak) {
                            currentBucketPeak = absSample
            }
                        
                        samplesInCurrentBucket++
                        totalSamplesProcessed++
                        
                        // Kiểm tra xem có hoàn thành bucket hiện tại không
                        if (samplesInCurrentBucket >= samplesPerBucket || totalSamplesProcessed >= totalSamples) {
            // Normalize peak về 0-100
                            val normalized = ((currentBucketPeak / 32767.0) * 100).toInt().coerceIn(0, 100)
            amplitudes.add(normalized)
                            
                            // Reset cho bucket tiếp theo
                            currentBucket++
                            currentBucketPeak = 0
                            samplesInCurrentBucket = 0
                            
                            // Nếu đã có đủ buckets, thoát
                            if (currentBucket >= targetCount) {
                                break
                            }
                        }
                        
                        i += 2
                    }
                    
                    // Nếu đã xử lý đủ samples, thoát
                    if (currentBucket >= targetCount) {
                        break
                    }
                    
                    // Gợi ý garbage collection mỗi 100 iterations để giải phóng memory
                    if (totalSamplesProcessed % 100000 == 0) {
                        System.gc()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in stream processing", e)
            return List(targetCount) { 0 }
        }
        
        // Đảm bảo có đủ số lượng amplitudes
        while (amplitudes.size < targetCount) {
            amplitudes.add(0)
        }
        
        // Cleanup và gợi ý garbage collection
        System.gc()
        
        return amplitudes
    }

    /**
     * Tạo amplitude data fallback khi không thể xử lý file
     */
    private fun setFallbackAmplitude(sampleCount: Int) {
        val fallbackAmplitudes = List(sampleCount) {
            // Tạo pattern giống waveform thực với random variations
            val baseAmplitude = (kotlin.math.sin(it.toDouble() / sampleCount * 4 * Math.PI) * 30).toInt()
            val randomVariation = (-5..5).random()
            (baseAmplitude + randomVariation).coerceIn(0, 50)
        }

        _state.value = _state.value.copy(
            amplitude = fallbackAmplitudes,
        )
        _state.value.audioInfo = _state.value.audioInfo?.copy(
            amplitudeInt = fallbackAmplitudes
        )
    }

    /**
     * Xử lý dữ liệu PCM thô để tạo ra waveform amplitude values
     * Sử dụng stream processing để tránh OutOfMemoryError
     */
    private fun processRawPCMData(pcmFile: File, sampleCount: Int): List<Float> {
        try {
            if (!pcmFile.exists() || pcmFile.length() == 0L) {
                return generateFallbackWaveform(sampleCount)
            }

            val totalBytes = pcmFile.length()
            val totalSamples = (totalBytes / 2).toInt() // 16-bit = 2 bytes per sample
            if (totalSamples == 0) {
                return generateFallbackWaveform(sampleCount)
            }

            val samplesPerBucket = maxOf(1, totalSamples / sampleCount)
            val waveformData = mutableListOf<Float>()

            // Chọn buffer size phù hợp với kích thước file
            val chunkSize = if (pcmFile.length() > LARGE_FILE_SIZE_THRESHOLD) {
                BUFFER_SIZE_LARGE
            } else {
                BUFFER_SIZE_SMALL
            }

            Log.d(TAG, "Processing PCM data with stream: $totalBytes bytes, $totalSamples samples, chunk size: $chunkSize")
            Log.d(TAG, "Samples per bucket: $samplesPerBucket")

            pcmFile.inputStream().buffered(chunkSize).use { inputStream ->
                val buffer = ByteArray(chunkSize)
                var bytesRead: Int
                var totalSamplesProcessed = 0
                var currentBucket = 0
                var maxAmplitude = 0f
                var samplesInCurrentBucket = 0
                
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    var i = 0
                    while (i < bytesRead - 1) {
                        // Đọc 16-bit signed sample (little endian)
                        val low = buffer[i].toInt() and 0xFF
                        val high = buffer[i + 1].toInt()
                        var sample = (high shl 8) or low

                        // Chuyển đổi từ signed 16-bit về float (-1.0 to 1.0)
                        val normalizedSample = if (sample > 32767) {
                            (sample - 65536) / 32768f
                        } else {
                            sample / 32768f
                        }

                        // Cập nhật amplitude lớn nhất trong bucket hiện tại
                        maxAmplitude = maxOf(maxAmplitude, abs(normalizedSample))

                        samplesInCurrentBucket++
                        totalSamplesProcessed++
                        
                        // Kiểm tra xem có hoàn thành bucket hiện tại không
                        if (samplesInCurrentBucket >= samplesPerBucket || totalSamplesProcessed >= totalSamples) {
                // Đảm bảo giá trị nằm trong khoảng [0.1, 1.0] để hiển thị tốt
                val clampedAmplitude = maxOf(0.1f, minOf(1.0f, maxAmplitude))
                waveformData.add(clampedAmplitude)
                            
                            // Reset cho bucket tiếp theo
                            currentBucket++
                            maxAmplitude = 0f
                            samplesInCurrentBucket = 0
                            
                            // Nếu đã có đủ buckets, thoát
                            if (currentBucket >= sampleCount) {
                                break
                            }
                        }
                        
                        i += 2
                    }
                    
                    // Nếu đã xử lý đủ samples, thoát
                    if (currentBucket >= sampleCount) {
                        break
                    }
                    
                    // Gợi ý garbage collection mỗi 100k samples để giải phóng memory
                    if (totalSamplesProcessed % 100000 == 0) {
                        System.gc()
                    }
                }
            }

            // Đảm bảo có đủ số lượng mẫu
            while (waveformData.size < sampleCount) {
                waveformData.add(0.1f)
            }

            // Cleanup và gợi ý garbage collection
            System.gc()

            Log.d(TAG, "Waveform processing completed: ${waveformData.size} amplitude values")
            return waveformData

        } catch (e: Exception) {
            Log.e(TAG, "Error processing PCM data with stream", e)
            return generateFallbackWaveform(sampleCount)
        }
    }

    /**
     * Tạo waveform fallback khi không thể xử lý audio
     */
    private fun generateFallbackWaveform(sampleCount: Int): List<Float> {
        Log.w(TAG, "Using fallback waveform generation")
        return List(sampleCount) {
            // Tạo waveform giả với pattern hình sin để trông tự nhiên hơn
            val x = it.toFloat() / sampleCount * 4 * Math.PI
            0.3f + 0.4f * kotlin.math.sin(x).toFloat() * kotlin.math.abs(kotlin.math.sin(x * 0.5))
                .toFloat()
        }
    }

    fun setTimeRange(startMs: Float, endMs: Float) {
        val audioInfo = _state.value.audioInfo ?: return

        val validStartMs = startMs.coerceAtLeast(0f)
        val validEndMs = endMs.coerceAtMost(audioInfo.duration)
            .coerceAtLeast(validStartMs + 1000) // Đảm bảo khoảng thời gian tối thiểu 1 giây

        // Cập nhật AudioFileInfo
        audioInfo.startTime = validStartMs
        audioInfo.endTime = validEndMs

        // Cập nhật state với AudioFileInfo đã được cập nhật
        _state.value = _state.value.copy(
            audioInfo = audioInfo
        )
    }

    private suspend fun getAudioDuration(uri: Uri, context: Context): Float {
        return withContext(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                val durationStr =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                retriever.release()
                (durationStr?.toLongOrNull() ?: 30000L).toFloat()
            } catch (e: Exception) {
                Log.e(TAG, "Error getting audio duration", e)
                30000f // Default to 30 seconds if unable to determine
            }
        }
    }

    private fun getFileNameFromUri(uri: Uri, context: Context): String {
        Log.d(TAG, "getFileNameFromUri: $uri")
        return try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val displayNameIndex = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    if (displayNameIndex != -1) {
                        return it.getString(displayNameIndex) ?: "Unknown Audio"
                    }
                }
            }

            // Fallback: try to get filename from URI path
            val path = uri.path
            if (path != null) {
                val lastSlash = path.lastIndexOf('/')
                if (lastSlash != -1 && lastSlash < path.length - 1) {
                    return path.substring(lastSlash + 1)
                }
            }
            "Unknown Audio"
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file name from URI", e)
            "Unknown Audio"
        }
    }

    fun formatMillisecondsToTimeString(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    fun setStartTime(time: String) {
        val audioInfo = _state.value.audioInfo ?: return
        val cleanTime = formatTime(time)

        // Update milliseconds value
        val startSeconds = timeToSeconds(cleanTime)
        val startMs = startSeconds * 1000f

        // Đảm bảo end time luôn lớn hơn start time ít nhất 1 giây
        val currentEndMs = audioInfo.endTime
        val validStartMs = startMs.coerceAtMost(currentEndMs - 1000)

        // Cập nhật AudioFileInfo
        audioInfo.startTime = validStartMs

        // Cập nhật state với AudioFileInfo đã được cập nhật
        _state.value = _state.value.copy(
            audioInfo = audioInfo
        )
    }

    fun setEndTime(time: String) {
        val audioInfo = _state.value.audioInfo ?: return
        val cleanTime = formatTime(time)

        // Update milliseconds value
        val endSeconds = timeToSeconds(cleanTime)
        val endMs = endSeconds * 1000f

        // Đảm bảo end time luôn lớn hơn start time ít nhất 1 giây
        val currentStartMs = audioInfo.startTime
        val maxAllowedEndMs = audioInfo.duration
        val validEndMs = endMs.coerceAtLeast(currentStartMs + 1000).coerceAtMost(maxAllowedEndMs)

        // Cập nhật AudioFileInfo
        audioInfo.endTime = validEndMs

        // Cập nhật state với AudioFileInfo đã được cập nhật
        _state.value = _state.value.copy(
            audioInfo = audioInfo
        )
    }

    fun playAudioPreview(context: Context) {
        val audioInfo = _state.value.audioInfo ?: return
        val audioUri = audioInfo.audioPath ?: return
        
        // Sử dụng vị trí pause trước đó hoặc start time
        val resumePosition = if (_state.value.currentPlayAudioPosition > audioInfo.startTime) {
            _state.value.currentPlayAudioPosition.toLong()
        } else {
            audioInfo.startTime.toLong()
        }

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, audioUri)
                prepare()
                
                // Seek đến vị trí resume
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    seekTo(resumePosition, MediaPlayer.SEEK_NEXT_SYNC)
                } else {
                    seekTo(resumePosition.toInt())
                }

                setOnCompletionListener {
                    _state.value = _state.value.copy(
                        isPlaying = false,
                        currentPlaybackPosition = audioInfo.startTime,
                        currentPlayAudioPosition = audioInfo.startTime // Reset vị trí khi hoàn thành
                    )
                    stopMediaPlayback()
                }

                start()
            }

            _state.value = _state.value.copy(
                isPlaying = true,
                currentPlaybackPosition = resumePosition.toFloat()
            )

            Log.d(TAG, "Audio playback resumed from position: ${resumePosition}ms")
            
            // Start updating progress
            startProgressUpdate()
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = "Lỗi khi phát audio: ${e.message}")
            Log.e(TAG, "Error playing audio", e)
        }
    }

    private fun startProgressUpdate() {
        progressUpdateJob?.cancel()
        progressUpdateJob = viewModelScope.launch(Dispatchers.IO) {
            val audioInfo = _state.value.audioInfo ?: return@launch
            val endTimeMs = audioInfo.endTime
            val startTimeMs = audioInfo.startTime
            val updateInterval = 50L // Giảm xuống 50ms để phản hồi nhanh hơn

            while (_state.value.isPlaying && mediaPlayer != null) {
                try {
                    val position = mediaPlayer?.currentPosition?.toFloat() ?: 0f

                    // Check if we've reached the end position
                    if (position >= endTimeMs) {
                        withContext(Dispatchers.Main) {
                            stopMediaPlayback()
                            _state.value = _state.value.copy(
                                currentPlaybackPosition = startTimeMs
                            )
                        }
                        break
                    }

                    _state.value = _state.value.copy(
                        currentPlaybackPosition = position,
                        playerProgress = (position - startTimeMs) / (endTimeMs - startTimeMs)
                    )

                    kotlinx.coroutines.delay(updateInterval)
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating playback progress", e)
                    break
                }
            }
        }
    }

    fun pauseAudioPlayback() {
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    val currentPosition = player.currentPosition.toFloat()
                    player.pause()
                    
                    // Lưu vị trí hiện tại để resume sau
                    _state.value = _state.value.copy(
                        isPlaying = false,
                        currentPlayAudioPosition = currentPosition,
                        currentPlaybackPosition = currentPosition
                    )
                    
                    Log.d(TAG, "Audio paused at position: ${currentPosition}ms")
                    
                    // Stop progress update
                    progressUpdateJob?.cancel()
                } else {

                }
            } catch (e: Exception) {
                Log.e(TAG, "Error pausing audio", e)
            }
        }
    }

    fun stopMediaPlayback() {
        mediaPlayer?.apply {
            try {
                if (isPlaying) {
                    stop()
                }
                release()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping playback", e)
            }
        }
        mediaPlayer = null
        progressUpdateJob?.cancel()
        
        // Reset về start time khi stop hoàn toàn
        val audioInfo = _state.value.audioInfo
        _state.value = _state.value.copy(
            isPlaying = false,
            currentPlaybackPosition = audioInfo?.startTime ?: 0f,
            currentPlayAudioPosition = audioInfo?.startTime ?: 0f
        )
    }

    override fun onCleared() {
        super.onCleared()
        stopMediaPlayback()
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun trimAudio(context: Context) {
        val currentState = _state.value
        val audioInfo = currentState.audioInfo

        if (audioInfo == null || audioInfo.audioPath == null) {
            _state.value = currentState.copy(error = "Vui lòng chọn file audio")
            return
        }

        val startMs = audioInfo.startTime
        val endMs = audioInfo.endTime

        if (endMs <= startMs) {
            _state.value =
                currentState.copy(error = "Thời gian kết thúc phải lớn hơn thời gian bắt đầu")
            return
        }

        viewModelScope.launch {
            try {
                _state.value = currentState.copy(
                    isProcessing = true,
                    progress = "Đang xử lý...",
                    error = null
                )

                // Convert milliseconds to string format for ffmpeg
                val startTime = formatMillisecondsToTimeString(startMs.toLong())
                val endTime = formatMillisecondsToTimeString(endMs.toLong())

                val (outputPath, outputUri) = trimAudioFile(
                    context,
                    audioInfo.audioPath,
                    startTime,
                    endTime
                )
                _state.value = _state.value.copy(
                    isProcessing = false,
                    progress = "Hoàn thành!",
                    outputFilePath = outputPath,
                    outputUri = outputUri
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isProcessing = false,
                    progress = "",
                    error = "Lỗi khi trim audio: ${e.message}"
                )
                Log.e(TAG, "Error trimming audio", e)
            }
        }
    }

    private suspend fun trimAudioFile(
        context: Context,
        uri: Uri?,
        startTime: String,
        endTime: String
    ): Pair<String, Uri?> {
        return withContext(Dispatchers.IO) {
            // Create temp directories for processing
            val tempDir = File(context.cacheDir, "temp_audio")
            if (!tempDir.exists()) {
                tempDir.mkdirs()
            } else {
                // Clean up any previous temporary files
                tempDir.listFiles()?.forEach { it.delete() }
            }

            // Use a simple filename without special characters for processing
            val inputFile = File(tempDir, "input_audio.mp3")

            // Tạo tên file với timestamp để tránh trùng lặp
            val timestamp =
                java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                    .format(java.util.Date())
            val duration = timeToSeconds(endTime) - timeToSeconds(startTime)
            val outputFileName = "trimmed_audio_${duration}s_${timestamp}.mp3"
            val tempOutputFile = File(tempDir, "output_audio.mp3")

            // Copy file
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(progress = "Đang copy file...")
            }

            try {
                copyUriToFile(context, uri!!, inputFile)

                Log.i(TAG, "File copied successfully")
                Log.i(TAG, "Input: ${inputFile.length()} bytes")

                // Kiểm tra định dạng file để đảm bảo đó là audio hợp lệ
                val checkCodecCommand = "-i ${inputFile.absolutePath}"
                val codecSession = FFmpegKit.execute(checkCodecCommand)
                val codecLogs = codecSession.allLogsAsString

                Log.i(TAG, "File info: $codecLogs")

                // Update progress
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(progress = "Đã copy file, đang trim...")
                }

                // Validate input file
                if (!inputFile.exists() || inputFile.length() == 0L) {
                    throw Exception("File audio không hợp lệ")
                }

                Log.i(TAG, "Input validation passed:")
                Log.i(TAG, "  Input: ${inputFile.absolutePath} (${inputFile.length()} bytes)")
                Log.i(TAG, "  Temp Output: ${tempOutputFile.absolutePath}")
                Log.i(TAG, "  Start time: $startTime, End time: $endTime")

                // Calculate duration
                val durationSeconds = timeToSeconds(endTime) - timeToSeconds(startTime)

                // FFmpeg command để trim audio - thêm options để tăng tính tương thích
                val ffmpegCommand = "-y " +  // Tự động ghi đè file output nếu đã tồn tại
                        "-f mp3 " +  // Chỉ định rõ định dạng đầu vào
                        "-i ${inputFile.absolutePath} " +
                        "-ss $startTime " +
                        "-t ${durationSeconds} " +
                        "-acodec libmp3lame " +  // Chỉ định rõ codec
                        "-b:a 192k " +
                        "-map_metadata 0 " +  // Giữ lại metadata
                        "${tempOutputFile.absolutePath}"

                Log.i(TAG, "Executing FFmpeg command: $ffmpegCommand")

                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(progress = "Đang thực hiện trim audio...")
                }

                // Execute FFmpeg command
                val session = FFmpegKit.execute(ffmpegCommand)
                val returnCode = session.returnCode

                if (ReturnCode.isSuccess(returnCode)) {
                    Log.i(TAG, "FFmpeg execution completed successfully")

                    // Kiểm tra file output có được tạo thành công không
                    if (tempOutputFile.exists() && tempOutputFile.length() > 0) {
                        Log.i(
                            TAG,
                            "Output file created successfully: ${tempOutputFile.absolutePath} (${tempOutputFile.length()} bytes)"
                        )

                        withContext(Dispatchers.Main) {
                            _state.value =
                                _state.value.copy(progress = "Đang lưu vào thư viện nhạc...")
                        }

                        // Rename the file with the proper name
                        val finalTempFile = File(tempDir, outputFileName)
                        if (tempOutputFile.renameTo(finalTempFile)) {
                            Log.i(TAG, "Successfully renamed output file to: ${finalTempFile.name}")
                        } else {
                            // If rename fails, copy the file
                            tempOutputFile.copyTo(finalTempFile, overwrite = true)
                            Log.i(TAG, "Copied output file to: ${finalTempFile.name}")
                        }

                        // Save to Media Store for public access
                        var outputUri: Uri? = null

                        try {
                            outputUri = saveToMediaStore(context, finalTempFile, outputFileName)
                            Log.i(TAG, "Saved to MediaStore with URI: $outputUri")

                            // Cleanup temp files
                            inputFile.delete()
                            tempOutputFile.delete()

                            return@withContext Pair(finalTempFile.absolutePath, outputUri)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to save to MediaStore: ${e.message}")

                            // Fallback to app-specific directory
                            val fallbackDir =
                                context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                            val fallbackFile = File(fallbackDir, outputFileName)
                            finalTempFile.copyTo(fallbackFile, overwrite = true)

                            // Make the file visible to Media Scanner
                            MediaScannerConnection.scanFile(
                                context,
                                arrayOf(fallbackFile.absolutePath),
                                arrayOf("audio/mp3"),
                                null
                            )

                            Log.i(TAG, "Saved to fallback location: ${fallbackFile.absolutePath}")
                            return@withContext Pair(fallbackFile.absolutePath, null)
                        }
                    } else {
                        throw Exception("File output không được tạo hoặc rỗng")
                    }
                } else {
                    val logs = session.allLogsAsString
                    Log.e(TAG, "FFmpeg execution failed with return code: $returnCode")
                    Log.e(TAG, "FFmpeg logs: $logs")

                    // Try alternative approach with different options
                    Log.i(TAG, "Trying alternative FFmpeg approach...")

                    // Alternative command with different settings
                    val alternativeCommand = "-y -i ${inputFile.absolutePath} " +
                            "-ss $startTime " +
                            "-t ${durationSeconds} " +
                            "-vn -ar 44100 -ac 2 -ab 192k -f mp3 ${tempOutputFile.absolutePath}"

                    Log.i(TAG, "Executing alternative FFmpeg command: $alternativeCommand")
                    val alternativeSession = FFmpegKit.execute(alternativeCommand)
                    val alternativeReturnCode = alternativeSession.returnCode

                    if (ReturnCode.isSuccess(alternativeReturnCode)) {
                        Log.i(TAG, "Alternative FFmpeg approach succeeded")
                        if (tempOutputFile.exists() && tempOutputFile.length() > 0) {
                            // Continue with the normal flow...
                            withContext(Dispatchers.Main) {
                                _state.value =
                                    _state.value.copy(progress = "Đang lưu vào thư viện nhạc...")
                            }

                            // Rename the file with the proper name
                            val finalTempFile = File(tempDir, outputFileName)
                            if (tempOutputFile.renameTo(finalTempFile)) {
                                Log.i(
                                    TAG,
                                    "Successfully renamed output file to: ${finalTempFile.name}"
                                )
                            } else {
                                // If rename fails, copy the file
                                tempOutputFile.copyTo(finalTempFile, overwrite = true)
                                Log.i(TAG, "Copied output file to: ${finalTempFile.name}")
                            }

                            // Save to Media Store for public access
                            var outputUri: Uri? = null

                            try {
                                outputUri = saveToMediaStore(context, finalTempFile, outputFileName)
                                Log.i(TAG, "Saved to MediaStore with URI: $outputUri")

                                // Cleanup temp files
                                inputFile.delete()
                                tempOutputFile.delete()

                                return@withContext Pair(finalTempFile.absolutePath, outputUri)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to save to MediaStore: ${e.message}")

                                // Fallback to app-specific directory
                                val fallbackDir =
                                    context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                                val fallbackFile = File(fallbackDir, outputFileName)
                                finalTempFile.copyTo(fallbackFile, overwrite = true)

                                // Make the file visible to Media Scanner
                                MediaScannerConnection.scanFile(
                                    context,
                                    arrayOf(fallbackFile.absolutePath),
                                    arrayOf("audio/mp3"),
                                    null
                                )

                                Log.i(
                                    TAG,
                                    "Saved to fallback location: ${fallbackFile.absolutePath}"
                                )
                                return@withContext Pair(fallbackFile.absolutePath, null)
                            }
                        } else {
                            throw Exception("Phương pháp thay thế đã thành công nhưng không tạo được file output")
                        }
                    } else {
                        // Both methods failed, provide detailed error
                        val alternativeLogs = alternativeSession.allLogsAsString
                        Log.e(
                            TAG,
                            "Alternative FFmpeg approach also failed: $alternativeReturnCode"
                        )
                        Log.e(TAG, "Alternative logs: $alternativeLogs")

                        // Try to extract a more specific error message from the logs
                        val errorMessage = when {
                            logs.contains("No such file or directory") ->
                                "Không thể truy cập file audio. Đường dẫn không hợp lệ."

                            logs.contains("Invalid data found when processing input") ->
                                "File audio không hợp lệ hoặc định dạng không được hỗ trợ."

                            logs.contains("Permission denied") ->
                                "Không có quyền truy cập file audio. Vui lòng cấp quyền đọc/ghi file."

                            logs.contains("Encoder") && logs.contains("not found") ->
                                "Codec MP3 không được hỗ trợ trên thiết bị này. Vui lòng thử định dạng khác."

                            else -> "Trim audio thất bại. Mã lỗi: $returnCode. Vui lòng thử lại với file audio khác."
                        }

                        // Log full error details
                        Log.e(TAG, "Detailed error: $errorMessage")
                        Log.e(TAG, "Original command: $ffmpegCommand")
                        Log.e(TAG, "Alternative command: $alternativeCommand")

                        throw Exception(errorMessage)
                    }
                }
            } catch (e: Exception) {
                // Clean up any temporary files that might have been created
                try {
                    inputFile.delete()
                    tempOutputFile.delete()
                } catch (cleanupEx: Exception) {
                    Log.e(TAG, "Error during cleanup of temp files", cleanupEx)
                }

                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(
                        isProcessing = false,
                        progress = "",
                        error = "Lỗi khi xử lý: ${e.message}"
                    )
                }
                throw e
            }
        }
    }

    private fun saveToMediaStore(context: Context, sourceFile: File, displayName: String): Uri? {
        val contentResolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp3")

            // For Android 10+ (API 29+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val uri = contentResolver.insert(collection, contentValues) ?: return null

        contentResolver.openOutputStream(uri)?.use { outputStream ->
            sourceFile.inputStream().use { inputStream ->
                inputStream.copyTo(outputStream)
            }
        }

        // Mark the file as complete for Android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, contentValues, null, null)
        }

        return uri
    }

    private fun copyUriToFile(context: Context, uri: Uri, destFile: File) {
        try {
            Log.d(TAG, "Copying file from $uri to ${destFile.absolutePath}")
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Log.e(TAG, "Cannot open input stream for URI: $uri")
                throw Exception("Không thể đọc file audio từ URI: $uri")
            }

            // Đảm bảo file đích không tồn tại trước khi copy
            if (destFile.exists()) {
                destFile.delete()
            }

            val outputStream = FileOutputStream(destFile)
            val buffer = ByteArray(8 * 1024) // 8KB buffer
            var bytesRead: Int
            var totalBytesCopied = 0L

            inputStream.use { input ->
                outputStream.use { output ->
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesCopied += bytesRead
                    }
                    output.flush()
                }
            }

            Log.d(TAG, "Copied $totalBytesCopied bytes from $uri to ${destFile.absolutePath}")

            if (!destFile.exists() || destFile.length() == 0L) {
                Log.e(TAG, "File copy failed or resulted in empty file: ${destFile.absolutePath}")
                throw Exception("Copy file thất bại. File rỗng hoặc không tồn tại.")
            }

            // Đảm bảo file được ghi hoàn toàn xuống bộ nhớ
            val runtime = Runtime.getRuntime()
            runtime.exec("sync").waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "Error copying file from $uri to ${destFile.absolutePath}", e)
            throw Exception("Copy file thất bại: ${e.message}")
        }
    }

    private fun formatTime(time: String): String {
        // Ensure format is MM:SS
        val cleaned = time.replace("[^0-9:]".toRegex(), "")
        val parts = cleaned.split(":")

        return when {
            parts.size >= 2 -> {
                val minutes = parts[0].padStart(2, '0').take(2)
                val seconds = parts[1].padStart(2, '0').take(2)
                "$minutes:$seconds"
            }

            parts.size == 1 && parts[0].length <= 2 -> {
                "00:${parts[0].padStart(2, '0')}"
            }

            parts.size == 1 && parts[0].length > 2 -> {
                val allDigits = parts[0]
                val minutes = allDigits.substring(0, allDigits.length - 2).padStart(2, '0')
                val seconds = allDigits.substring(allDigits.length - 2).padStart(2, '0')
                "$minutes:$seconds"
            }

            else -> "00:00"
        }
    }

    private fun timeToSeconds(time: String): Int {
        val parts = time.split(":")
        return if (parts.size >= 2) {
            (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
        } else {
            0
        }
    }

    private fun secondsToTime(seconds: Int): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return String.format("%02d:%02d", mins, secs)
    }

    fun resetState() {
        stopMediaPlayback()
        _state.value = AudioTrimState()
    }
} 