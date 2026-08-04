package com.example.zaberskipiano

import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.pow

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF1E1E1E)
                ) {
                    PianoScreen()
                }
            }
        }
    }
}

@Composable
fun PianoScreen() {
    val context = LocalContext.current
    var currentOctave by remember { mutableFloatStateOf(3f) }

    // High-performance low-latency SoundPool engine
    val soundPool = remember {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        SoundPool.Builder()
            .setMaxStreams(12)
            .setAudioAttributes(attributes)
            .build()
    }

    // Load multi-sampled anchor notes (c2 through c7)
    val soundMap = remember { mutableMapOf<Int, Int>() }

    DisposableEffect(Unit) {
        val loadSample = { resName: String, midiBase: Int ->
            val resId = context.resources.getIdentifier(resName, "raw", context.packageName)
            if (resId != 0) {
                soundMap[midiBase] = soundPool.load(context, resId, 1)
            }
        }

        // Map lowercase file names to MIDI note values
        loadSample("c2", 36)
        loadSample("c3", 48)
        loadSample("c4", 60)
        loadSample("c5", 72)
        loadSample("c6", 84)
        loadSample("c7", 96)

        onDispose {
            soundPool.release()
        }
    }

    // Play note by selecting the nearest anchor sample
    fun playNote(octave: Int, semitone: Int) {
        val midiNote = (octave + 1) * 12 + semitone

        // Find nearest C sample (36, 48, 60, 72, 84, or 96)
        val anchors = listOf(36, 48, 60, 72, 84, 96)
        val baseMidi = anchors.minByOrNull { kotlin.math.abs(it - midiNote) } ?: 60

        val soundId = soundMap[baseMidi] ?: 0
        if (soundId == 0) return

        // Calculate minimal pitch shift ratio
        val semitoneDiff = midiNote - baseMidi
        val rate = 2.0.pow(semitoneDiff / 12.0).toFloat().coerceIn(0.5f, 2.0f)

        soundPool.play(soundId, 1.0f, 1.0f, 1, 0, rate)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- TOP BAR: Octave Slider ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Base Octave: ${currentOctave.toInt()}",
                color = Color.White,
                fontSize = 16.sp
            )
            Slider(
                value = currentOctave,
                onValueChange = { currentOctave = it },
                valueRange = 1f..6f,
                steps = 4,
                modifier = Modifier.width(300.dp)
            )
        }

        // --- PIANO KEYBOARD ---
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black)
        ) {
            val totalWidth = maxWidth
            val baseOctave = currentOctave.toInt()
            val numWhiteKeys = 14
            val whiteKeyWidth = totalWidth / numWhiteKeys

            // 14 White Keys
            Row(modifier = Modifier.fillMaxSize()) {
                val notes = listOf("C", "D", "E", "F", "G", "A", "B")
                val semitones = listOf(0, 2, 4, 5, 7, 9, 11)

                for (octaveOffset in 0..1) {
                    notes.forEachIndexed { index, name ->
                        val octave = baseOctave + octaveOffset
                        val semitone = semitones[index]

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(horizontal = 1.dp)
                                .clip(RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
                                .background(Color.White)
                                .clickable {
                                    playNote(octave, semitone)
                                },
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Text(
                                text = "$name$octave",
                                color = Color.DarkGray,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                    }
                }
            }

            // 10 Black Keys Overlay
            val blackKeyWidth = whiteKeyWidth * 0.6f
            val blackKeyPositions = listOf(
                Triple(0, 1, "C#"), Triple(1, 3, "D#"),
                Triple(3, 6, "F#"), Triple(4, 8, "G#"), Triple(5, 10, "A#"),
                Triple(7, 1, "C#"), Triple(8, 3, "D#"),
                Triple(10, 6, "F#"), Triple(11, 8, "G#"), Triple(12, 10, "A#")
            )

            blackKeyPositions.forEach { (whiteIndex, semitone, _) ->
                val octave = baseOctave + (if (whiteIndex >= 7) 1 else 0)
                val xOffset = (whiteKeyWidth * (whiteIndex + 1)) - (blackKeyWidth / 2)

                Box(
                    modifier = Modifier
                        .width(blackKeyWidth)
                        .fillMaxHeight(0.58f)
                        .offset(x = xOffset)
                        .clip(RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp))
                        .background(Color(0xFF151515))
                        .clickable {
                            playNote(octave, semitone)
                        }
                )
            }
        }
    }
}