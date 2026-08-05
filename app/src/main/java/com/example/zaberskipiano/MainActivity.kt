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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import kotlin.math.pow
import kotlin.math.sqrt

enum class ChordMode { NONE, MAJOR, MINOR }
enum class SustainMode { NONE, HALF, FULL }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        // Hide both status bar and navigation bars (square, triangle, circle) to prevent UI overlap
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
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

    // 49 total white keys across 7 octaves (C1 to B7).
    // Viewport displays 14 white keys at once.
    // firstWhiteKeyIndex = 21 corresponds to starting at C4 (Octave 4).
    var firstWhiteKeyIndex by remember { mutableIntStateOf(21) }
    var chordMode by remember { mutableStateOf(ChordMode.NONE) }
    var sustainMode by remember { mutableStateOf(SustainMode.NONE) }
    var dynamicEnabled by remember { mutableStateOf(false) }
    var navLocked by remember { mutableStateOf(false) }

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

        val initialVol = if (dynamicEnabled) {
            val effectiveMagnitude = if (maxShakeMagnitude > 0f) maxShakeMagnitude else liveShakeMagnitude
            getDynamicVolume(effectiveMagnitude)
        } else {
            1.0f
        }

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
            when (sustainMode) {
                SustainMode.FULL -> {}
                SustainMode.HALF -> {
                    activeStreams[note]?.let { streamId ->
                        activeStreams.remove(note)
                        coroutineScope.launch(Dispatchers.Default) {
                            val steps = 15
                            for (i in steps downTo 0) {
                                val vol = (i / steps.toFloat()) * initialVol
                                soundPool.setVolume(streamId, vol, vol)
                                delay(40L)
                            }
                            soundPool.stop(streamId)
                        }
                    }
                }
                SustainMode.NONE -> {
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
        }

        directPressedNotes.clear()
        directPressedNotes.addAll(newDirectNotes)
    }

    // Helper functions for dynamic single-key index math
    val whiteSemitoneOffsets = listOf(0, 2, 4, 5, 7, 9, 11)
    val whiteNoteNames = listOf("C", "D", "E", "F", "G", "A", "B")

    fun getMidiForWhiteKey(globalWhiteKeyIndex: Int): Int {
        val octave = (globalWhiteKeyIndex / 7) + 1
        val semitone = whiteSemitoneOffsets[globalWhiteKeyIndex % 7]
        return (octave + 1) * 12 + semitone
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
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
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Zaberski Piano",
                            color = amberColor,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "v1.3",
                            color = amberColor.copy(alpha = 0.8f),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text(
                        text = "by Ventsislav Negentsov",
                        color = Color.LightGray,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal
                    )
                    Text(
                        text = "dedicated to Zaberski father & son",
                        color = amberColor.copy(alpha = 0.8f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Light
                    )
                }
            }

            // Controls
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ControlChip(
                    text = "Dynamic",
                    isActive = dynamicEnabled,
                    activeColor = Color(0xFF2196F3),
                    onClick = { dynamicEnabled = !dynamicEnabled }
                )

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
                    text = "Sustain 1/2",
                    isActive = sustainMode == SustainMode.HALF,
                    activeColor = Color(0xFF81C784),
                    onClick = {
                        sustainMode = if (sustainMode == SustainMode.HALF) SustainMode.NONE else SustainMode.HALF
                    }
                )

                ControlChip(
                    text = "Sustain",
                    isActive = sustainMode == SustainMode.FULL,
                    activeColor = Color(0xFF4CAF50),
                    onClick = {
                        sustainMode = if (sustainMode == SustainMode.FULL) SustainMode.NONE else SustainMode.FULL
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        // --- MAIN PIANO KEYBOARD WITH SINGLE KEY SCROLLING SUPPORT ---
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black)
        ) {
            val totalWidthPx = constraints.maxWidth.toFloat()
            val totalHeightPx = constraints.maxHeight.toFloat()

            val numVisibleWhiteKeys = 14
            val whiteKeyWidthPx = totalWidthPx / numVisibleWhiteKeys
            val blackKeyWidthPx = whiteKeyWidthPx * 0.6f
            val blackKeyHeightPx = totalHeightPx * 0.58f

            fun resolveMidiNoteAt(offset: Offset): Int? {
                val x = offset.x
                val y = offset.y

                if (x < 0 || x > totalWidthPx || y < 0 || y > totalHeightPx) return null

                // Check black keys first (only indices 0, 1, 3, 4, 5 in octave have black key to their right)
                if (y <= blackKeyHeightPx) {
                    for (i in 0 until numVisibleWhiteKeys) {
                        val globalIndex = firstWhiteKeyIndex + i
                        val noteInOctave = globalIndex % 7
                        if (noteInOctave in listOf(0, 1, 3, 4, 5)) {
                            val left = (whiteKeyWidthPx * (i + 1)) - (blackKeyWidthPx / 2f)
                            val right = left + blackKeyWidthPx
                            if (x in left..right) {
                                return getMidiForWhiteKey(globalIndex) + 1
                            }
                        }
                    }
                }

                // Check white keys
                val keyRelativeIndex = (x / whiteKeyWidthPx).toInt().coerceIn(0, numVisibleWhiteKeys - 1)
                return getMidiForWhiteKey(firstWhiteKeyIndex + keyRelativeIndex)
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(firstWhiteKeyIndex, chordMode, dynamicEnabled, sustainMode) {
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

                                if (event.changes.none { it.pressed }) break
                            }
                        }
                    }
            ) {
                // Render White Keys
                Row(modifier = Modifier.fillMaxSize()) {
                    for (i in 0 until numVisibleWhiteKeys) {
                        val globalIndex = firstWhiteKeyIndex + i
                        val name = whiteNoteNames[globalIndex % 7]
                        val octave = (globalIndex / 7) + 1
                        val midiNote = getMidiForWhiteKey(globalIndex)

                        WhiteKeyVisual(
                            name = name,
                            octave = octave,
                            isPressed = activeMidiNotes.contains(midiNote),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Render Black Keys dynamically aligned to visible white keys
                val density = LocalContext.current.resources.displayMetrics.density
                for (i in 0 until numVisibleWhiteKeys) {
                    val globalIndex = firstWhiteKeyIndex + i
                    val noteInOctave = globalIndex % 7
                    if (noteInOctave in listOf(0, 1, 3, 4, 5)) {
                        val blackMidiNote = getMidiForWhiteKey(globalIndex) + 1

                        val xOffsetDp = ((whiteKeyWidthPx * (i + 1)) - (blackKeyWidthPx / 2f)) / density
                        val widthDp = blackKeyWidthPx / density

                        BlackKeyVisual(
                            isPressed = activeMidiNotes.contains(blackMidiNote),
                            modifier = Modifier
                                .width(widthDp.dp)
                                .fillMaxHeight(0.58f)
                                .offset(x = xOffsetDp.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // --- BOTTOM NAVIGATION & MINI-MAP OVERVIEW WITH SMOOTH SINGLE-KEY SCROLLING ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .background(Color(0xFF151515), RoundedCornerShape(6.dp))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Lock Button (Left)
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(if (navLocked) amberColor else Color(0xFF2A2A2A))
                    .clickable { navLocked = !navLocked },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (navLocked) "🔒" else "🔓",
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Mini Keyboard Strip with Drag & Tap for Smooth Key Scrolling
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF222222))
                    .pointerInput(navLocked) {
                        if (!navLocked) {
                            detectTapGestures { offset ->
                                val totalMiniWhiteKeys = 49f
                                val keyWidth = size.width / totalMiniWhiteKeys
                                val tappedIndex = (offset.x / keyWidth).toInt()
                                // Center the 14-key view around the tapped position
                                firstWhiteKeyIndex = (tappedIndex - 7).coerceIn(0, 35)
                            }
                        }
                    }
                    .pointerInput(navLocked) {
                        if (!navLocked) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                val totalMiniWhiteKeys = 49f
                                val keyWidth = size.width / totalMiniWhiteKeys
                                val draggedIndex = (change.position.x / keyWidth).toInt()
                                firstWhiteKeyIndex = (draggedIndex - 7).coerceIn(0, 35)
                            }
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val totalOctaves = 7
                    val whiteKeysPerOctave = 7
                    val totalWhiteKeys = totalOctaves * whiteKeysPerOctave
                    val miniKeyWidth = size.width / totalWhiteKeys
                    val miniBlackWidth = miniKeyWidth * 0.6f
                    val miniBlackHeight = size.height * 0.55f

                    val startVisibleWhite = firstWhiteKeyIndex
                    val visibleWhiteCount = 14

                    // Render mini white keys
                    for (i in 0 until totalWhiteKeys) {
                        val isVisibleRegion = i >= startVisibleWhite && i < (startVisibleWhite + visibleWhiteCount)
                        val keyColor = if (isVisibleRegion) Color(0xFFE0E0E0) else Color(0xFF555555)

                        drawRect(
                            color = keyColor,
                            topLeft = Offset(i * miniKeyWidth + 0.5f, 0f),
                            size = Size(miniKeyWidth - 1f, size.height)
                        )
                    }

                    // Render mini black keys
                    val blackPattern = listOf(0, 1, 3, 4, 5)
                    for (oct in 0 until totalOctaves) {
                        blackPattern.forEach { indexInOctave ->
                            val whiteIndex = oct * 7 + indexInOctave
                            val isVisibleRegion = whiteIndex >= startVisibleWhite && whiteIndex < (startVisibleWhite + visibleWhiteCount)
                            val blackColor = if (isVisibleRegion) Color.Black else Color(0xFF2A2A2A)

                            val xPos = ((whiteIndex + 1) * miniKeyWidth) - (miniBlackWidth / 2f)
                            drawRect(
                                color = blackColor,
                                topLeft = Offset(xPos, 0f),
                                size = Size(miniBlackWidth, miniBlackHeight)
                            )
                        }
                    }

                    // Highlight Active Viewport Frame
                    val highlightLeft = startVisibleWhite * miniKeyWidth
                    val highlightWidth = visibleWhiteCount * miniKeyWidth
                    drawRect(
                        color = Color(0xFFFFB300).copy(alpha = 0.35f),
                        topLeft = Offset(highlightLeft, 0f),
                        size = Size(highlightWidth, size.height)
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // (-) and (+) Single White Key Scroll Controls
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2A2A2A))
                        .clickable(enabled = !navLocked && firstWhiteKeyIndex > 0) {
                            firstWhiteKeyIndex = (firstWhiteKeyIndex - 1).coerceAtLeast(0)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "−",
                        color = if (firstWhiteKeyIndex > 0) amberColor else Color.Gray,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2A2A2A))
                        .clickable(enabled = !navLocked && firstWhiteKeyIndex < 35) {
                            firstWhiteKeyIndex = (firstWhiteKeyIndex + 1).coerceAtMost(35)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "+",
                        color = if (firstWhiteKeyIndex < 35) amberColor else Color.Gray,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
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