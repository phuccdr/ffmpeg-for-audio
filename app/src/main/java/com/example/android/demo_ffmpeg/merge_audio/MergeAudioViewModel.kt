package com.example.android.demo_ffmpeg.merge_audio

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
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
import java.io.OutputStream

enum class PlaybackState {
    IDLE,
    PLAYING_FIRST,
    PLAYING_SECOND,
    PAUSED
}

data class AudioMergeState(
    val firstAudioPath: Uri? = null,
    val secondAudioPath: Uri? = null,
    val isProcessing: Boolean = false,
    val progress: String = "",
    val outputFilePath: String? = null,
    val outputUri: Uri? = null,
    val error: String? = null,
    val playbackState: PlaybackState = PlaybackState.IDLE,
    val playbackProgress: Float = 0f,
    val audioDuration: Int = 0,
    val currentPlayingUri: Uri? = null
)

class MergeAudioViewModel : ViewModel() {
    private val _state = MutableStateFlow(AudioMergeState())
    val state: StateFlow<AudioMergeState> = _state.asStateFlow()
    
    private var mediaPlayer: MediaPlayer? = null
    private var currentPlayingUri: Uri? = null

    companion object {
        private const val TAG = "MergeAudioViewModel"
    }

    override fun onCleared() {
        super.onCleared()
        releaseMediaPlayer()
    }

    fun setFirstAudioPath(uri: Uri) {
        _state.value = _state.value.copy(firstAudioPath = uri)
    }

    fun setSecondAudioPath(uri: Uri) {
        _state.value = _state.value.copy(secondAudioPath = uri)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
    
    fun previewAudio(context: Context, isFirstAudio: Boolean) {
        val uri = if (isFirstAudio) _state.value.firstAudioPath else _state.value.secondAudioPath
        
        if (uri == null) {
            _state.value = _state.value.copy(error = "Không có file audio để phát")
            return
        }
        
        // If already playing this URI, pause it
        if (uri == currentPlayingUri && mediaPlayer?.isPlaying == true) {
            pauseAudio()
            return
        }
        
        // If playing the other audio file, stop it first
        releaseMediaPlayer()
        
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, uri)
                setOnPreparedListener { mp ->
                    mp.start()
                    _state.value = _state.value.copy(
                        playbackState = if (isFirstAudio) PlaybackState.PLAYING_FIRST else PlaybackState.PLAYING_SECOND,
                        audioDuration = mp.duration,
                        currentPlayingUri = uri
                    )
                    
                    // Update progress periodically
                    viewModelScope.launch {
                        while (mediaPlayer?.isPlaying == true) {
                            val progress = mediaPlayer?.currentPosition?.toFloat()?.div(mp.duration) ?: 0f
                            _state.value = _state.value.copy(playbackProgress = progress)
                            kotlinx.coroutines.delay(100)
                        }
                    }
                }
                setOnCompletionListener {
                    _state.value = _state.value.copy(
                        playbackState = PlaybackState.IDLE,
                        playbackProgress = 0f,
                        currentPlayingUri = null
                    )
                    releaseMediaPlayer()
                }
                setOnErrorListener { _, _, _ ->
                    _state.value = _state.value.copy(
                        error = "Lỗi khi phát file audio",
                        playbackState = PlaybackState.IDLE,
                        currentPlayingUri = null
                    )
                    releaseMediaPlayer()
                    true
                }
                prepareAsync()
            }
            currentPlayingUri = uri
            
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                error = "Không thể phát file audio: ${e.message}",
                playbackState = PlaybackState.IDLE,
                currentPlayingUri = null
            )
            releaseMediaPlayer()
        }
    }
    
    fun pauseAudio() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                _state.value = _state.value.copy(playbackState = PlaybackState.PAUSED)
            } else {
                it.start()
                _state.value = _state.value.copy(
                    playbackState = if (currentPlayingUri == _state.value.firstAudioPath) 
                        PlaybackState.PLAYING_FIRST 
                    else 
                        PlaybackState.PLAYING_SECOND
                )
            }
        }
    }
    
    fun stopAudio() {
        releaseMediaPlayer()
        _state.value = _state.value.copy(
            playbackState = PlaybackState.IDLE,
            playbackProgress = 0f,
            currentPlayingUri = null
        )
    }
    
    private fun releaseMediaPlayer() {
        mediaPlayer?.apply {
            if (isPlaying) {
                stop()
            }
            release()
        }
        mediaPlayer = null
        currentPlayingUri = null
    }

    fun mergeAudio(context: Context) {
        // Stop any playing audio before merging
        stopAudio()
        
        val currentState = _state.value
        if (currentState.firstAudioPath == null || currentState.secondAudioPath == null) {
            _state.value = currentState.copy(error = "Vui lòng chọn cả 2 file audio")
            return
        }

        viewModelScope.launch {
            try {
                _state.value = currentState.copy(
                    isProcessing = true,
                    progress = "Đang xử lý...",
                    error = null
                )
                val (outputPath, outputUri) = mergeAudioFiles(
                    context,
                    currentState.firstAudioPath,
                    currentState.secondAudioPath
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
                    error = "Lỗi khi merge audio: ${e.message}"
                )
            }
        }
    }

    private suspend fun mergeAudioFiles(
        context: Context,
        uri1: Uri,
        uri2: Uri
    ): Pair<String, Uri?> {
        return withContext(Dispatchers.IO) {
            // Tạo tên file với timestamp để tránh trùng lặp
            val timestamp =
                java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                    .format(java.util.Date())
            val outputFileName = "merged_audio_${timestamp}.mp3"
            
            // Tạo temp directory trong cache để lưu trung gian
            val tempDir = File(context.cacheDir, "temp_audio")
            if (!tempDir.exists()) {
                tempDir.mkdirs()
            }
            
            val inputFile1 = File(tempDir, "input1.mp3")
            val inputFile2 = File(tempDir, "input2.mp3")
            val tempOutputFile = File(tempDir, outputFileName)
            
            // Copy files
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(progress = "Đang copy file đầu tiên...")
            }
            copyUriToFile(context, uri1, inputFile1)
            
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(progress = "Đang copy file thứ hai...")
            }
            copyUriToFile(context, uri2, inputFile2)
            
            Log.i(TAG, "Files copied successfully")
            Log.i(TAG, "Input1: ${inputFile1.length()} bytes, Input2: ${inputFile2.length()} bytes")
            
            // Update progress
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(progress = "Đã copy files, đang merge...")
            }
            
            // Validate input files
            if (!inputFile1.exists() || inputFile1.length() == 0L) {
                throw Exception("File audio đầu tiên không hợp lệ")
            }
            if (!inputFile2.exists() || inputFile2.length() == 0L) {
                throw Exception("File audio thứ hai không hợp lệ")
            }

            Log.i(TAG, "Input validation passed:")
            Log.i(TAG, "  Input1: ${inputFile1.absolutePath} (${inputFile1.length()} bytes)")
            Log.i(TAG, "  Input2: ${inputFile2.absolutePath} (${inputFile2.length()} bytes)")
            Log.i(TAG, "  Temp Output: ${tempOutputFile.absolutePath}")
            
            // Test FFmpeg with a simple command first
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(progress = "Kiểm tra FFmpeg...")
            }
            
            try {
                val testSession = FFmpegKit.execute("-version")
                Log.i(TAG, "FFmpeg test successful, return code: ${testSession.returnCode}")
            } catch (e: Exception) {
                Log.e(TAG, "FFmpeg test failed: ${e.message}")
                throw Exception("FFmpeg không khả dụng: ${e.message}")
            }
            
            // Try multiple FFmpeg approaches for merging
            var success = false
            
            // Method 1: Simple concat demuxer (works best for same format files)
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(progress = "Thử phương pháp 1: Concat demuxer...")
            }
            
            try {
                // Create concat file
                val concatFile = File(tempDir, "concat_list.txt")
                concatFile.writeText("file '${inputFile1.absolutePath}'\nfile '${inputFile2.absolutePath}'")
                
                val ffmpegCommand1 = "-f concat -safe 0 -i \"${concatFile.absolutePath}\" " +
                        "-c:a mp3 -b:a 192k \"${tempOutputFile.absolutePath}\""
                
                Log.i(TAG, "Trying Method 1 - FFmpeg command: $ffmpegCommand1")
                
                val session1 = FFmpegKit.execute(ffmpegCommand1)
                val returnCode1 = session1.returnCode
                
                if (ReturnCode.isSuccess(returnCode1) && tempOutputFile.exists() && tempOutputFile.length() > 0) {
                    Log.i(TAG, "Method 1 successful!")
                    success = true
                    concatFile.delete()
                } else {
                    Log.w(TAG, "Method 1 failed with return code: $returnCode1")
                    val logs1 = session1.allLogsAsString
                    Log.w(TAG, "Method 1 logs: $logs1")
                    concatFile.delete()
                    tempOutputFile.delete() // Clean up failed attempt
                }
            } catch (e: Exception) {
                Log.w(TAG, "Method 1 exception: ${e.message}")
            }
            
            // Method 2: Filter complex with proper audio format handling
            if (!success) {
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(progress = "Thử phương pháp 2: Filter complex...")
                }
                
                try {
                    val ffmpegCommand2 = "-i \"${inputFile1.absolutePath}\" -i \"${inputFile2.absolutePath}\" " +
                            "-filter_complex \"[0:a:0][1:a:0]concat=n=2:v=0:a=1[outa]\" " +
                            "-map \"[outa]\" -ac 2 -c:a mp3 -b:a 192k \"${tempOutputFile.absolutePath}\""
                    
                    Log.i(TAG, "Trying Method 2 - FFmpeg command: $ffmpegCommand2")
                    
                    val session2 = FFmpegKit.execute(ffmpegCommand2)
                    val returnCode2 = session2.returnCode
                    
                    if (ReturnCode.isSuccess(returnCode2) && tempOutputFile.exists() && tempOutputFile.length() > 0) {
                        Log.i(TAG, "Method 2 successful!")
                        success = true
                    } else {
                        Log.w(TAG, "Method 2 failed with return code: $returnCode2")
                        val logs2 = session2.allLogsAsString
                        Log.w(TAG, "Method 2 logs: $logs2")
                        tempOutputFile.delete()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Method 2 exception: ${e.message}")
                }
            }
            
            // Method 3: Simple approach - convert to same format first then concat
            if (!success) {
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(progress = "Thử phương pháp 3: Convert và concat...")
                }
                
                try {
                    // Convert both files to same format first
                    val convertedFile1 = File(tempDir, "converted1.mp3")
                    val convertedFile2 = File(tempDir, "converted2.mp3")
                    
                    // Convert file 1
                    val convertCmd1 = "-i \"${inputFile1.absolutePath}\" -c:a mp3 -ar 44100 -ac 2 -b:a 192k \"${convertedFile1.absolutePath}\""
                    val convertSession1 = FFmpegKit.execute(convertCmd1)
                    
                    // Convert file 2  
                    val convertCmd2 = "-i \"${inputFile2.absolutePath}\" -c:a mp3 -ar 44100 -ac 2 -b:a 192k \"${convertedFile2.absolutePath}\""
                    val convertSession2 = FFmpegKit.execute(convertCmd2)
                    
                    if (ReturnCode.isSuccess(convertSession1.returnCode) && 
                        ReturnCode.isSuccess(convertSession2.returnCode) &&
                        convertedFile1.exists() && convertedFile2.exists()) {
                        
                        // Now concat the converted files
                        val concatFile = File(tempDir, "concat_list2.txt")
                        concatFile.writeText("file '${convertedFile1.absolutePath}'\nfile '${convertedFile2.absolutePath}'")
                        
                        val ffmpegCommand3 = "-f concat -safe 0 -i \"${concatFile.absolutePath}\" " +
                                "-c copy \"${tempOutputFile.absolutePath}\""
                        
                        Log.i(TAG, "Trying Method 3 - FFmpeg command: $ffmpegCommand3")
                        
                        val session3 = FFmpegKit.execute(ffmpegCommand3)
                        val returnCode3 = session3.returnCode
                        
                        if (ReturnCode.isSuccess(returnCode3) && tempOutputFile.exists() && tempOutputFile.length() > 0) {
                            Log.i(TAG, "Method 3 successful!")
                            success = true
                        } else {
                            Log.w(TAG, "Method 3 failed with return code: $returnCode3")
                            val logs3 = session3.allLogsAsString
                            Log.w(TAG, "Method 3 logs: $logs3")
                        }
                        
                        // Cleanup
                        concatFile.delete()
                        convertedFile1.delete()
                        convertedFile2.delete()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Method 3 exception: ${e.message}")
                }
            }
            
            if (success) {
                Log.i(TAG, "FFmpeg execution completed successfully in temp location: ${tempOutputFile.absolutePath}")
                
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(progress = "Đang lưu vào thư viện nhạc...")
                }
                
                // Save to public Music directory using MediaStore API
                var outputUri: Uri? = null
                
                try {
                    outputUri = saveToMediaStore(context, tempOutputFile, outputFileName)
                    Log.i(TAG, "Saved to MediaStore with URI: $outputUri")
                    
                    // Cleanup temp files
                    inputFile1.delete()
                    inputFile2.delete()
                    
                    // We keep the temp output file until app closes
                    return@withContext Pair(tempOutputFile.absolutePath, outputUri)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save to MediaStore: ${e.message}")
                    
                    // Fallback to app-specific directory
                    val fallbackDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                    val fallbackFile = File(fallbackDir, outputFileName)
                    tempOutputFile.copyTo(fallbackFile, overwrite = true)
                    
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
                throw Exception("Tất cả phương pháp merge đều thất bại. Vui lòng thử với file audio khác hoặc kiểm tra format file.")
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
            val outputStream = FileOutputStream(destFile)
            inputStream.use { input ->
                outputStream.use { output ->
                    val bytesCopied = input.copyTo(output)
                    Log.d(TAG, "Copied $bytesCopied bytes from $uri to ${destFile.absolutePath}")
                }
            }
            if (!destFile.exists() || destFile.length() == 0L) {
                Log.e(TAG, "File copy failed or resulted in empty file: ${destFile.absolutePath}")
                throw Exception("Copy file thất bại. File rỗng hoặc không tồn tại.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error copying file from $uri to ${destFile.absolutePath}", e)
            throw Exception("Copy file thất bại: ${e.message}")
        }
    }

    fun resetState() {
        stopAudio()
        _state.value = AudioMergeState()
    }
}