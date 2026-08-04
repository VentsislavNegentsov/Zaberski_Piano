package com.example.zaberskipiano

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.pow
import kotlin.math.sqrt

enum class ChordMode { NONE, MAJOR, MINOR }

data class BlackKeyInfo(
    val whiteIndex: Int,
    val semitone: Int,
    val label: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.statusBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

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
    val coroutineScope = rememberCoroutineScope()

    var currentOctave by remember { mutableFloatStateOf(4f) }
    var chordMode by remember { mutableStateOf(ChordMode.NONE) }
    var sustainEnabled by remember { mutableStateOf(false) }

    // Sensor readings & Peak Hold logic (100ms decay)
    var liveShakeMagnitude by remember { mutableFloatStateOf(0f) }
    var maxShakeMagnitude by remember { mutableFloatStateOf(0f) }
    var maxResetJob by remember { mutableStateOf<Job?>(null) }

    var lastPlayedStreamId by remember { mutableIntStateOf(0) }

    val directPressedNotes = remember { mutableStateSetOf<Int>() }
    val activeMidiNotes = remember { mutableStateSetOf<Int>() }
    val activeStreams = remember { mutableStateMapOf<Int, Int>() }

    val amberColor = Color(0xFFFFB300)

    val soundPool = remember {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        SoundPool.Builder()
            .setMaxStreams(16)
            .setAudioAttributes(attributes)
            .build()
    }

    val soundMap = remember { mutableMapOf<Int, Int>() }

    val sensorManager = remember {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    // Dynamic volume scaling with max acceleration at 0.1f
    fun getDynamicVolume(magnitude: Float): Float {
        val normalized = (magnitude / 0.1f).coerceIn(0f, 1f)
        return (0.15f + normalized * 0.85f).coerceIn(0.15f, 1.0f)
    }

    DisposableEffect(Unit) {
        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                event?.let {
                    val x = it.values[0]
                    val y = it.values[1]
                    val z = it.values[2]

                    val magnitude = sqrt(x * x + y * y + z * z)
                    liveShakeMagnitude = magnitude

                    // Track peak acceleration with fast 100ms decay
                    if (magnitude > maxShakeMagnitude) {
                        maxShakeMagnitude = magnitude
                        maxResetJob?.cancel()
                        maxResetJob = coroutineScope.launch {
                            delay(100L)
                            maxShakeMagnitude = 0f
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        if (accelSensor != null) {
            sensorManager.registerListener(listener, accelSensor, SensorManager.SENSOR_DELAY_FASTEST)
        }

        val loadSample = { resName: String, midiBase: Int ->
            val resId = context.resources.getIdentifier(resName, "raw", context.packageName)
            if (resId != 0) {
                soundMap[midiBase] = soundPool.load(context, resId, 1)
            }
        }

        loadSample("c2", 36)
        loadSample("c3", 48)
        loadSample("c4", 60)
        loadSample("c5", 72)
        loadSample("c6", 84)
        loadSample("c7", 96)

        onDispose {
            if (accelSensor != null) {
                sensorManager.unregisterListener(listener)
            }
            soundPool.release()
        }
    }

    fun playMidiNote(midiNote: Int, velocity: Float = 1.0f): Int {
        val anchors = listOf(36, 48, 60, 72, 84, 96)
        val baseMidi = anchors.minByOrNull { kotlin.math.abs(it - midiNote) } ?: 60

        val soundId = soundMap[baseMidi] ?: 0
        if (soundId == 0) return 0

        val semitoneDiff = midiNote - baseMidi
        val rate = 2.0.pow(semitoneDiff / 12.0).toFloat().coerceIn(0.5f, 2.0f)

        return soundPool.play(soundId, velocity, velocity, 1, 0, rate)
    }

    fun getChordMidiNotes(rootMidi: Int): List<Int> {
        return when (chordMode) {
            ChordMode.NONE -> listOf(rootMidi)
            ChordMode.MAJOR -> listOf(rootMidi, rootMidi + 4, rootMidi + 7)
            ChordMode.MINOR -> listOf(rootMidi, rootMidi + 3, rootMidi + 7)
        }
    }

    fun updateActiveNotes(newDirectNotes: Set<Int>) {
        val newExpandedNotes = mutableSetOf<Int>()
        newDirectNotes.forEach { root ->
            newExpandedNotes.addAll(getChordMidiNotes(root))
        }

        val started = newExpandedNotes - activeMidiNotes
        val ended = activeMidiNotes - newExpandedNotes

        // Capture peak acceleration within the 100ms window (or live fallback)
        val effectiveMagnitude = if (maxShakeMagnitude > 0f) maxShakeMagnitude else liveShakeMagnitude
        val initialVol = getDynamicVolume(effectiveMagnitude)

        started.forEach { note ->
            activeMidiNotes.add(note)
            activeStreams[note]?.let { soundPool.stop(it) }
            val streamId = playMidiNote(note, initialVol)
            if (streamId != 0) {
                activeStreams[note] = streamId
                lastPlayedStreamId = streamId
            }
        }

        ended.forEach { note ->
            activeMidiNotes.remove(note)
            if (!sustainEnabled) {
                activeStreams[note]?.let { streamId ->
                    activeStreams.remove(note)
                    coroutineScope.launch(Dispatchers.Default) {
                        val steps = 6
                        for (i in steps downTo 0) {
                            val vol = (i / steps.toFloat()) * initialVol
                            soundPool.setVolume(streamId, vol, vol)
                            delay(20L)
                        }
                        soundPool.stop(streamId)
                    }
                }
            }
        }

        directPressedNotes.clear()
        directPressedNotes.addAll(newDirectNotes)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- TOP BAR ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Title Header
            Box(
                modifier = Modifier
                    .border(width = 1.5.dp, color = amberColor, shape = RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Zaberski Piano",
                            color = amberColor,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "v1.2",
                            color = amberColor.copy(alpha = 0.8f),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text(
                        text = "by Ventsislav Negentsov",
                        color = Color.LightGray,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Normal
                    )
                    Text(
                        text = "dedicated to Zaberski father & son",
                        color = amberColor.copy(alpha = 0.8f),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Light
                    )
                }
            }

            // Dual High-Visibility Debug Sensor Display
            Box(
                modifier = Modifier
                    .background(Color(0xFF2E0000), RoundedCornerShape(8.dp))
                    .border(width = 1.dp, color = Color.Red, shape = RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "SENSOR / ACCELERATION",
                        color = Color.Red,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("LIVE", color = Color.Gray, fontSize = 9.sp)
                            Text(
                                text = String.format(Locale.US, "%.3f", liveShakeMagnitude),
                                color = Color.Yellow,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text("|", color = Color.Red, fontSize = 18.sp)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("MAX (100ms)", color = Color.Gray, fontSize = 9.sp)
                            Text(
                                text = String.format(Locale.US, "%.3f", maxShakeMagnitude),
                                color = Color(0xFFFF9800),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }

            // Controls & Chords
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ControlChip(
                    text = "Major Chord",
                    isActive = chordMode == ChordMode.MAJOR,
                    activeColor = amberColor,
                    onClick = {
                        chordMode = if (chordMode == ChordMode.MAJOR) ChordMode.NONE else ChordMode.MAJOR
                        updateActiveNotes(directPressedNotes.toSet())
                    }
                )

                ControlChip(
                    text = "Minor Chord",
                    isActive = chordMode == ChordMode.MINOR,
                    activeColor = amberColor,
                    onClick = {
                        chordMode = if (chordMode == ChordMode.MINOR) ChordMode.NONE else ChordMode.MINOR
                        updateActiveNotes(directPressedNotes.toSet())
                    }
                )

                ControlChip(
                    text = "Sustain",
                    isActive = sustainEnabled,
                    activeColor = Color(0xFF4CAF50),
                    onClick = {
                        sustainEnabled = !sustainEnabled
                    }
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Octave: ${currentOctave.toInt()}",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Slider(
                    value = currentOctave,
                    onValueChange = { currentOctave = it },
                    valueRange = 1f..6f,
                    steps = 4,
                    modifier = Modifier.width(120.dp)
                )
            }
        }

        // --- GLOBAL MULTI-TOUCH PIANO KEYBOARD ---
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black)
        ) {
            val totalWidthPx = constraints.maxWidth.toFloat()
            val totalHeightPx = constraints.maxHeight.toFloat()
            val baseOctave = currentOctave.toInt()

            val numWhiteKeys = 14
            val whiteKeyWidthPx = totalWidthPx / numWhiteKeys
            val blackKeyWidthPx = whiteKeyWidthPx * 0.6f
            val blackKeyHeightPx = totalHeightPx * 0.58f

            val blackKeys = remember {
                listOf(
                    BlackKeyInfo(0, 1, "C#"), BlackKeyInfo(1, 3, "D#"),
                    BlackKeyInfo(3, 6, "F#"), BlackKeyInfo(4, 8, "G#"), BlackKeyInfo(5, 10, "A#"),
                    BlackKeyInfo(7, 1, "C#"), BlackKeyInfo(8, 3, "D#"),
                    BlackKeyInfo(10, 6, "F#"), BlackKeyInfo(11, 8, "G#"), BlackKeyInfo(12, 10, "A#")
                )
            }

            fun resolveMidiNoteAt(offset: Offset): Int? {
                val x = offset.x
                val y = offset.y

                if (x < 0 || x > totalWidthPx || y < 0 || y > totalHeightPx) return null

                if (y <= blackKeyHeightPx) {
                    blackKeys.forEach { bk ->
                        val left = (whiteKeyWidthPx * (bk.whiteIndex + 1)) - (blackKeyWidthPx / 2f)
                        val right = left + blackKeyWidthPx
                        if (x in left..right) {
                            val octave = baseOctave + (if (bk.whiteIndex >= 7) 1 else 0)
                            return (octave + 1) * 12 + bk.semitone
                        }
                    }
                }

                val whiteIndex = (x / whiteKeyWidthPx).toInt().coerceIn(0, 13)
                val whiteSemitones = listOf(0, 2, 4, 5, 7, 9, 11)
                val octave = baseOctave + (if (whiteIndex >= 7) 1 else 0)
                val semitone = whiteSemitones[whiteIndex % 7]

                return (octave + 1) * 12 + semitone
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(baseOctave, chordMode) {
                        awaitEachGesture {
                            while (true) {
                                val event = awaitPointerEvent()

                                event.changes.forEach { it.consume() }

                                val pressedPointers = event.changes.filter { it.pressed }

                                val currentTouchedNotes = mutableSetOf<Int>()
                                pressedPointers.forEach { pointer ->
                                    resolveMidiNoteAt(pointer.position)?.let { note ->
                                        currentTouchedNotes.add(note)
                                    }
                                }

                                updateActiveNotes(currentTouchedNotes)

                                if (event.changes.none { it.pressed }) {
                                    break
                                }
                            }
                        }
                    }
            ) {
                // Render White Keys
                Row(modifier = Modifier.fillMaxSize()) {
                    val names = listOf("C", "D", "E", "F", "G", "A", "B")
                    val semitones = listOf(0, 2, 4, 5, 7, 9, 11)

                    for (octaveOffset in 0..1) {
                        names.forEachIndexed { index, name ->
                            val octave = baseOctave + octaveOffset
                            val semitone = semitones[index]
                            val midiNote = (octave + 1) * 12 + semitone

                            WhiteKeyVisual(
                                name = name,
                                octave = octave,
                                isPressed = activeMidiNotes.contains(midiNote),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Render Black Keys
                val density = LocalContext.current.resources.displayMetrics.density
                blackKeys.forEach { bk ->
                    val octave = baseOctave + (if (bk.whiteIndex >= 7) 1 else 0)
                    val midiNote = (octave + 1) * 12 + bk.semitone

                    val xOffsetDp = ((whiteKeyWidthPx * (bk.whiteIndex + 1)) - (blackKeyWidthPx / 2f)) / density
                    val widthDp = blackKeyWidthPx / density

                    BlackKeyVisual(
                        isPressed = activeMidiNotes.contains(midiNote),
                        modifier = Modifier
                            .width(widthDp.dp)
                            .fillMaxHeight(0.58f)
                            .offset(x = xOffsetDp.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ControlChip(
    text: String,
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (isActive) activeColor else Color(0xFF333333))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isActive) Color.Black else Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun WhiteKeyVisual(
    name: String,
    octave: Int,
    isPressed: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = 1.dp)
            .clip(RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
            .background(if (isPressed) Color(0xFFCCCCCC) else Color.White),
        contentAlignment = Alignment.BottomCenter
    ) {
        Text(
            text = "$name$octave",
            color = Color.DarkGray,
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }
}

@Composable
fun BlackKeyVisual(
    isPressed: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp))
            .background(if (isPressed) Color(0xFFFFB300) else Color(0xFF151515))
    )
}