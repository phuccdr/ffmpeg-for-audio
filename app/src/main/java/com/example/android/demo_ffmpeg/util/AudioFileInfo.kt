package com.example.android.demo_ffmpeg.util

import android.net.Uri

data class AudioFileInfo(
    val name: String="",
    var startTime: Float=0f,
    var endTime: Float=0f,
    var duration: Float=0f,
    var amplitude: List<Float> = emptyList(),
    var audioPath: Uri? = null,
    val index: Int=0,
    var amplitudeInt: List<Int> = emptyList()
)
