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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

data class AudioTrimState(
    val audioPath: Uri? = null,
    val startTime: String = "00:00",
    val endTime: String = "00:30",
    val isProcessing: Boolean = false,
    val progress: String = "",
    val outputFilePath: String? = null,
    val outputUri: Uri? = null,
    val error: String? = null,
    val audioDuration: Int = 0,
    val startTimeMs: Float = 0f,
    val endTimeMs: Float = 30000f,
    val isPlaying: Boolean = false,
    val currentPlaybackPosition: Float = 0f,
    val playerProgress: Float = 0f
)

class TrimAudioViewModel : ViewModel() {
    private val _state = MutableStateFlow(AudioTrimState())
    val state: StateFlow<AudioTrimState> = _state.asStateFlow()
    
    private var mediaPlayer: MediaPlayer? = null
    private var progressUpdateJob: kotlinx.coroutines.Job? = null

    companion object {
        private const val TAG = "TrimAudioViewModel"
    }

    fun setAudioPath(uri: Uri, context: Context) {
        stopPlayback()
        
        viewModelScope.launch {
            try {
                // Extract audio duration
                val duration = withContext(Dispatchers.IO) {
                    getAudioDuration(uri, context)
                }
                
                val defaultEndTimeMs = minOf(30000f, duration.toFloat())
                
                _state.value = _state.value.copy(
                    audioPath = uri,
                    audioDuration = duration,
                    startTimeMs = 0f,
                    endTimeMs = defaultEndTimeMs,
                    startTime = "00:00",
                    endTime = formatMillisecondsToTimeString(defaultEndTimeMs.toLong())
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = "Không thể đọc thông tin file audio: ${e.message}"
                )
            }
        }
    }

    fun setTimeRange(startMs: Float, endMs: Float) {
        _state.value = _state.value.copy(
            startTimeMs = startMs,
            endTimeMs = endMs,
            startTime = formatMillisecondsToTimeString(startMs.toLong()),
            endTime = formatMillisecondsToTimeString(endMs.toLong())
        )
    }

    private suspend fun getAudioDuration(uri: Uri, context: Context): Int {
        return withContext(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                retriever.release()
                (durationStr?.toLongOrNull() ?: 30000L).toInt()
            } catch (e: Exception) {
                Log.e(TAG, "Error getting audio duration", e)
                30000 // Default to 30 seconds if unable to determine
            }
        }
    }
    
    fun formatMillisecondsToTimeString(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    fun setStartTime(time: String) {
        val cleanTime = formatTime(time)
        _state.value = _state.value.copy(startTime = cleanTime)
        
        // Update milliseconds value
        val startSeconds = timeToSeconds(cleanTime)
        val startMs = startSeconds * 1000f
        
        _state.value = _state.value.copy(startTimeMs = startMs)
        
        // Ensure end time is after start time
        val endSeconds = timeToSeconds(_state.value.endTime)
        if (endSeconds <= startSeconds) {
            val newEndTime = secondsToTime(startSeconds + 30) // Add 30 seconds
            val newEndMs = (startSeconds + 30) * 1000f
            _state.value = _state.value.copy(
                endTime = newEndTime,
                endTimeMs = minOf(newEndMs, _state.value.audioDuration.toFloat())
            )
        }
    }

    fun setEndTime(time: String) {
        val cleanTime = formatTime(time)
        _state.value = _state.value.copy(endTime = cleanTime)
        
        // Update milliseconds value
        val endSeconds = timeToSeconds(cleanTime)
        val endMs = endSeconds * 1000f
        
        _state.value = _state.value.copy(endTimeMs = endMs)
        
        // Ensure end time is after start time
        val startSeconds = timeToSeconds(_state.value.startTime)
        if (endSeconds <= startSeconds) {
            val newStartTime = secondsToTime(maxOf(0, endSeconds - 30)) // Subtract 30 seconds
            val newStartMs = (maxOf(0, endSeconds - 30)) * 1000f
            _state.value = _state.value.copy(
                startTime = newStartTime,
                startTimeMs = newStartMs
            )
        }
    }

    fun playAudioPreview(context: Context) {
        stopPlayback()
        
        val audioUri = _state.value.audioPath ?: return
        val startMs = _state.value.startTimeMs.toLong()
        
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, audioUri)
                prepare()
                seekTo(startMs.toInt())
                
                setOnCompletionListener {
                    _state.value = _state.value.copy(
                        isPlaying = false, 
                        currentPlaybackPosition = _state.value.startTimeMs
                    )
                    stopPlayback()
                }
                
                // Set up a listener to stop playback when we reach the end time
                setOnInfoListener { mp, what, extra ->
                    if (mp.currentPosition >= _state.value.endTimeMs) {
                        stopPlayback()
                        _state.value = _state.value.copy(
                            isPlaying = false,
                            currentPlaybackPosition = _state.value.startTimeMs
                        )
                        return@setOnInfoListener true
                    }
                    false
                }
                
                start()
            }
            
            _state.value = _state.value.copy(
                isPlaying = true,
                currentPlaybackPosition = startMs.toFloat()
            )
            
            // Start updating progress
            startProgressUpdate()
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = "Lỗi khi phát audio: ${e.message}")
        }
    }
    
    private fun startProgressUpdate() {
        progressUpdateJob?.cancel()
        progressUpdateJob = viewModelScope.launch(Dispatchers.IO) {
            while (_state.value.isPlaying && mediaPlayer != null) {
                try {
                    val position = mediaPlayer?.currentPosition?.toFloat() ?: 0f
                    
                    // Check if we've reached the end position
                    if (position >= _state.value.endTimeMs) {
                        withContext(Dispatchers.Main) {
                            stopPlayback()
                            _state.value = _state.value.copy(
                                currentPlaybackPosition = _state.value.startTimeMs
                            )
                        }
                        break
                    }
                    
                    _state.value = _state.value.copy(
                        currentPlaybackPosition = position,
                        playerProgress = (position - _state.value.startTimeMs) / 
                                        (_state.value.endTimeMs - _state.value.startTimeMs)
                    )
                    
                    kotlinx.coroutines.delay(100) // Update every 100ms
                } catch (e: Exception) {
                    break
                }
            }
        }
    }
    
    fun stopPlayback() {
        mediaPlayer?.apply {
            if (isPlaying) {
                stop()
            }
            release()
        }
        mediaPlayer = null
        progressUpdateJob?.cancel()
        _state.value = _state.value.copy(isPlaying = false)
    }

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun trimAudio(context: Context) {
        val currentState = _state.value
        if (currentState.audioPath == null) {
            _state.value = currentState.copy(error = "Vui lòng chọn file audio")
            return
        }

        val startMs = currentState.startTimeMs
        val endMs = currentState.endTimeMs
        
        if (endMs <= startMs) {
            _state.value = currentState.copy(error = "Thời gian kết thúc phải lớn hơn thời gian bắt đầu")
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
                    currentState.audioPath,
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
            }
        }
    }

    private suspend fun trimAudioFile(
        context: Context,
        uri: Uri,
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
                copyUriToFile(context, uri, inputFile)
                
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
                        Log.i(TAG, "Output file created successfully: ${tempOutputFile.absolutePath} (${tempOutputFile.length()} bytes)")
                        
                        withContext(Dispatchers.Main) {
                            _state.value = _state.value.copy(progress = "Đang lưu vào thư viện nhạc...")
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
                            val fallbackDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
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
                                _state.value = _state.value.copy(progress = "Đang lưu vào thư viện nhạc...")
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
                                val fallbackDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
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
                            throw Exception("Phương pháp thay thế đã thành công nhưng không tạo được file output")
                        }
                    } else {
                        // Both methods failed, provide detailed error
                        val alternativeLogs = alternativeSession.allLogsAsString
                        Log.e(TAG, "Alternative FFmpeg approach also failed: $alternativeReturnCode")
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
        stopPlayback()
        _state.value = AudioTrimState()
    }
} 