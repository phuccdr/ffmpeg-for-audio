package com.example.android.demo_ffmpeg.util
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin

/**
 * Xử lý file audio để tạo dữ liệu waveform
 */
class AudioProcessor {

    companion object {
        // Tạo dữ liệu waveform từ file audio
        suspend fun createWaveformFromAudio(
            context: Context,
            audioUri: Uri,
            targetSamplesPerPixel: Int = 256
        ): WaveformData? = withContext(Dispatchers.IO) {
            try {
                val extractor = MediaExtractor()
                extractor.setDataSource(context, audioUri, null)

                // Tìm track audio
                val audioTrackIndex = findAudioTrack(extractor) ?: return@withContext null
                extractor.selectTrack(audioTrackIndex)

                val format = extractor.getTrackFormat(audioTrackIndex)
                val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val duration = format.getLong(MediaFormat.KEY_DURATION) / 1000 // Convert to ms

                // Đọc dữ liệu audio
                val audioSamples = extractAudioSamples(extractor)
                extractor.release()

                // Chuyển đổi thành dữ liệu waveform
                val waveformSamples = downsampleAudioData(
                    audioSamples,
                    sampleRate,
                    channelCount,
                    targetSamplesPerPixel
                )

                WaveformData(
                    sampleRate = sampleRate,
                    samplesPerPixel = targetSamplesPerPixel,
                    samples = waveformSamples,
                    durationMs = duration
                )

            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        // Tạo dữ liệu mẫu (giống như trong demo gốc)
        fun createSampleWaveformData(): WaveformData {
            val samples = (0..2000).map { i ->
                // Tạo sóng sine với amplitude thay đổi
                val baseFreq = 0.05f
                val modFreq = 0.005f
                val amplitude = 0.3f + 0.7f * sin(i * modFreq)
                (sin(i * baseFreq) * amplitude).toFloat()
            }

            return WaveformData(
                sampleRate = 44100,
                samplesPerPixel = 256,
                samples = samples,
                durationMs = 20000L
            )
        }

        private fun findAudioTrack(extractor: MediaExtractor): Int? {
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    return i
                }
            }
            return null
        }

        private fun extractAudioSamples(extractor: MediaExtractor): List<Short> {
            val samples = mutableListOf<Short>()
            val buffer = ByteBuffer.allocate(1024 * 1024) // 1MB buffer

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                // Chuyển đổi từ ByteBuffer sang ShortArray
                buffer.rewind()
                val shortBuffer = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val tempSamples = ShortArray(shortBuffer.remaining())
                shortBuffer.get(tempSamples)

                samples.addAll(tempSamples.toList())
                extractor.advance()
                buffer.clear()
            }

            return samples
        }

        // Giảm mẫu dữ liệu audio để phù hợp với hiển thị
        private fun downsampleAudioData(
            samples: List<Short>,
            sampleRate: Int,
            channelCount: Int,
            samplesPerPixel: Int
        ): List<Float> {
            if (samples.isEmpty()) return emptyList()

            // Tính toán số samples per pixel dựa trên tỷ lệ mẫu
            val barPerSample = (0.01 * sampleRate * channelCount).toInt()
            val count = samples.size / barPerSample

            val result = mutableListOf<Float>()

            for (i in 0 until count) {
                val startIndex = i * barPerSample
                val endIndex = minOf((i + 1) * barPerSample, samples.size)

                // Tính giá trị trung bình của một nhóm samples
                var sum = 0.0
                for (j in startIndex until endIndex) {
                    sum += abs(samples[j].toDouble())
                }

                val average = if (endIndex > startIndex) {
                    sum / (endIndex - startIndex)
                } else {
                    0.0
                }

                // Normalize về [-1, 1]
                result.add((average / Short.MAX_VALUE).toFloat())
            }

            return result
        }

        // Lấy thông tin metadata của file audio
        suspend fun getAudioMetadata(context: Context, audioUri: Uri): AudioMetadata? =
            withContext(Dispatchers.IO) {
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(context, audioUri)

                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
                    val sampleRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toIntOrNull() ?: 44100

                    retriever.release()

                    AudioMetadata(
                        durationMs = duration,
                        bitrate = bitrate,
                        sampleRate = sampleRate
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
    }
}

data class AudioMetadata(
    val durationMs: Long,
    val bitrate: Int,
    val sampleRate: Int
)

// Extension function để dễ sử dụng
suspend fun Uri.toWaveformData(context: Context): WaveformData? {
    return AudioProcessor.createWaveformFromAudio(context, this)
}