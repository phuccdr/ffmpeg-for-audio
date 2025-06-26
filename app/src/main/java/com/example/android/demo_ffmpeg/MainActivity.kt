package com.example.android.demo_ffmpeg

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.android.demo_ffmpeg.merge_audio.MergeAudioActivity
import com.example.android.demo_ffmpeg.trim_audio.TrimAudioActivity
import com.example.android.demo_ffmpeg.ui.theme.Demo_ffmpegTheme


data class AudioEditor(
    val id: Int = 0,
    val icon: ImageVector,
    val title: String,
    val description: String,
    val color: Color
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Demo_ffmpegTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AudioEditingScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun AudioEditingScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val audioFunctions = listOf(
        AudioEditor(
            id=0,
            icon = Icons.Filled.ContentCut,
            title = "Cắt Audio",
            description = "Cắt và chỉnh sửa file âm thanh",
            color = Color(0xFF4CAF50)
        ),
        AudioEditor(
            id=1,
            icon = Icons.Filled.Merge,
            title = "Ghép Audio",
            description = "Ghép nhiều file âm thanh thành một",
            color = Color(0xFF2196F3)
        ),
        AudioEditor(
            id=2,
            icon = Icons.Filled.Layers,
            title = "Lồng Audio",
            description = "Lồng và trộn các file âm thanh",
            color = Color(0xFFFF9800)
        ),
        AudioEditor(
            id=3,
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            title = "Điều chỉnh âm lượng",
            description = "Thay đổi âm lượng file audio",
            color = Color(0xFF9C27B0)
        ),
        AudioEditor(
            id=4,
            icon = Icons.Filled.Speed,
            title = "Thay đổi tốc độ",
            description = "Tăng hoặc giảm tốc độ phát",
            color = Color(0xFFE91E63)
        ),
        AudioEditor(
            id=5,
            icon = Icons.Filled.GraphicEq,
            title = "Equalizer",
            description = "Điều chỉnh âm sắc và tần số",
            color = Color(0xFF607D8B)
        )
    )

    Column( modifier = modifier
        .fillMaxSize()
        .padding(16.dp)
    ){
        Text(
            text= "Audio Editor",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp),
            textAlign = TextAlign.Center
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()
        ){
            items(audioFunctions){ item ->
                AudioFunctionCard(
                    audioEditor = item,
                    onClick = {
                        when(item.id){
                            0 ->{
                                context.startActivity(Intent(context, TrimAudioActivity::class.java))
                            }
                            1 ->{
                                context.startActivity(Intent(context, MergeAudioActivity::class.java))
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun AudioFunctionCard(
    audioEditor: AudioEditor,
    onClick:() ->Unit,
    modifier:Modifier = Modifier,
){
    Card(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.9f)
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        shape = RoundedCornerShape(16.dp)
    ){
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    color = audioEditor.color.copy(alpha = 0.1f)
                )
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ){
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(30.dp))
                    .background(audioEditor.color),
                contentAlignment = Alignment.Center
            ){
                Icon(
                    imageVector = audioEditor.icon,
                    contentDescription = audioEditor.title,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))


            Text(
                text = audioEditor.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = audioEditor.description,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                lineHeight = 14.sp
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun AudioEditingPreview() {
    Demo_ffmpegTheme {
        AudioEditingScreen()
    }
}