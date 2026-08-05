package com.example.zaberskipiano

import android.app.Activity
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

enum class HarmonyMode(val displayName: String) {
    NONE("Single Note"),
    MAJOR("Major"),
    MINOR("Minor"),
    MAJ7("Maj7"),
    MIN7("Min7"),
    DOM9("Dom9 Jazz"),
    QUARTAL("Quartal Modern"),
    DIM7("Diminished 7th"),
    LUSH11("Lush 11th")
}

enum class SustainMode { NONE, HALF, FULL }

enum class KeyPressedState { NONE, DIRECT, HARMONY }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
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

    var firstWhiteKeyIndex by remember { mutableIntStateOf(21) }
    var harmonyMode by remember { mutableStateOf(HarmonyMode.NONE) }
    var bassLineEnabled by remember { mutableStateOf(false) }
    var sustainMode by remember { mutableStateOf(SustainMode.HALF) }
    var dynamicEnabled by remember { mutableStateOf(false) }
    var isKeySizeEnlarged by remember { mutableStateOf(false) }
    var noLabels by remember { mutableStateOf(true) }
    var blackKeysEasyHit by remember { mutableStateOf(true) }
    var navLocked by remember { mutableStateOf(false) }
    var showCreditsDialog by remember { mutableStateOf(false) }
    var creditsLanguage by remember { mutableStateOf("BG") }

    val numVisibleWhiteKeys = if (isKeySizeEnlarged) 9 else 14
    val maxFirstIndex = 49 - numVisibleWhiteKeys

    LaunchedEffect(isKeySizeEnlarged) {
        firstWhiteKeyIndex = firstWhiteKeyIndex.coerceIn(0, maxFirstIndex)
    }

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
            .setMaxStreams(32)
            .setAudioAttributes(attributes)
            .build()
    }

    val soundMap = remember { mutableMapOf<Int, Int>() }

    val sensorManager = remember {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

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
        val baseMidi = anchors.minByOrNull { abs(it - midiNote) } ?: 60

        val soundId = soundMap[baseMidi] ?: 0
        if (soundId == 0) return 0

        val semitoneDiff = midiNote - baseMidi
        val rate = 2.0.pow(semitoneDiff / 12.0).toFloat().coerceIn(0.5f, 2.0f)

        return soundPool.play(soundId, velocity, velocity, 1, 0, rate)
    }

    fun getHarmonyMidiNotes(rootMidi: Int): List<Int> {
        val bassNotes = if (bassLineEnabled) {
            val bassRoot = (rootMidi - 12).coerceAtLeast(24)
            when (harmonyMode) {
                HarmonyMode.NONE -> listOf(bassRoot, bassRoot + 12)
                HarmonyMode.MAJOR -> listOf(bassRoot, bassRoot + 7, bassRoot + 16)
                HarmonyMode.MINOR -> listOf(bassRoot, bassRoot + 7, bassRoot + 15)
                HarmonyMode.MAJ7 -> listOf(bassRoot, bassRoot + 7, bassRoot + 16)
                HarmonyMode.MIN7 -> listOf(bassRoot, bassRoot + 7, bassRoot + 15)
                HarmonyMode.DOM9 -> listOf(bassRoot, bassRoot + 10, bassRoot + 16)
                HarmonyMode.QUARTAL -> listOf(bassRoot, bassRoot + 7, bassRoot + 14)
                HarmonyMode.DIM7 -> listOf(bassRoot, bassRoot + 6, bassRoot + 15)
                HarmonyMode.LUSH11 -> listOf(bassRoot, bassRoot + 7, bassRoot + 14, bassRoot + 18)
            }
        } else emptyList()

        val rightHandNotes = when (harmonyMode) {
            HarmonyMode.NONE -> listOf(rootMidi)
            HarmonyMode.MAJOR -> listOf(rootMidi, rootMidi + 4, rootMidi + 7)
            HarmonyMode.MINOR -> listOf(rootMidi, rootMidi + 3, rootMidi + 7)
            HarmonyMode.MAJ7 -> listOf(rootMidi, rootMidi + 4, rootMidi + 7, rootMidi + 11)
            HarmonyMode.MIN7 -> listOf(rootMidi, rootMidi + 3, rootMidi + 7, rootMidi + 10)
            HarmonyMode.DOM9 -> listOf(rootMidi, rootMidi + 4, rootMidi + 7, rootMidi + 10, rootMidi + 14)
            HarmonyMode.QUARTAL -> listOf(rootMidi, rootMidi + 5, rootMidi + 10, rootMidi + 15)
            HarmonyMode.DIM7 -> listOf(rootMidi, rootMidi + 3, rootMidi + 6, rootMidi + 9)
            HarmonyMode.LUSH11 -> listOf(rootMidi, rootMidi + 4, rootMidi + 7, rootMidi + 10, rootMidi + 14, rootMidi + 17)
        }

        return bassNotes + rightHandNotes
    }

    fun updateActiveNotes(newDirectNotes: Set<Int>) {
        val newExpandedNotes = mutableSetOf<Int>()
        newDirectNotes.forEach { root ->
            newExpandedNotes.addAll(getHarmonyMidiNotes(root))
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

    val whiteSemitoneOffsets = listOf(0, 2, 4, 5, 7, 9, 11)
    val whiteNoteNames = listOf("C", "D", "E", "F", "G", "A", "B")

    fun getMidiForWhiteKey(globalWhiteKeyIndex: Int): Int {
        val octave = (globalWhiteKeyIndex / 7) + 1
        val semitone = whiteSemitoneOffsets[globalWhiteKeyIndex % 7]
        return (octave + 1) * 12 + semitone
    }

    // --- CREDITS DIALOG ---
    if (showCreditsDialog) {
        AlertDialog(
            onDismissRequest = { showCreditsDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Zaberski Piano",
                        color = amberColor,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "v1.3",
                        color = amberColor.copy(alpha = 0.8f),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = if (creditsLanguage == "BG") "от Венцислав Негенцов" else "by Ventsislav Negentsov",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (creditsLanguage == "BG") "Посвещава се на баща и син Заберски" else "Dedicated to Zaberski father & son",
                        color = amberColor.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // Joint Photo (Father & Son)
                    Image(
                        painter = painterResource(id = R.drawable.az_fs),
                        contentDescription = "Angel Zaberski Father and Son",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color.Gray.copy(alpha = 0.3f))
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (creditsLanguage == "BG") {
                        // --- FATHER (BG) ---
                        Text(
                            text = "Ангел Заберски – баща (1936–2011)",
                            color = amberColor,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Ангел Заберски – баща (1936–2011) е изтъкнат български композитор, аранжор и музикален педагог, считан за една от ключовите фигури в изграждането на българската забавна и естрадна музика.\n\n" +
                                    "• Произход и образование: Роден е на 8 февруари 1936 г. Завършва Държавната музикална академия в София със специалност оперно пеене в класа на проф. Христо Бръмбаров.\n\n" +
                                    "• Професионален път: Работи дълги години като музикален редактор и аранжор в Българското национално радио, където създава и обработва стотици произведения за оркестри и популярни изпълнители.\n\n" +
                                    "• Емблематични композиции: Автор е на някои от най-големите класики в българската музика. Неговата песен „Калиакра“ (в изпълнение на Лили Иванова) печели първа награда на фестивала „Златният Орфей“ през 1966 г. Създава още поп класики като „Лилей“, „Бяла песен“, както и редица хитове за Маргарет Николова, Йорданка Христова, Бисер Киров и Орлин Горанов.\n\n" +
                                    "• Педагогическо наследство: Като дългогодишен преподавател подготвя поколения български поп и джаз пеещи таланти, предавайки им високи академични стандарти за вокална техника.\n\n" +
                                    "• Музикално семейство: Баща е на джаз пианиста и композитор Ангел Заберски-син и на певицата Неда Заберска, които продължават творческия род.",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Father Photos (az_f1 & az_f2)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.az_f1),
                                contentDescription = "Angel Zaberski Sr. 1",
                                modifier = Modifier
                                    .weight(1f)
                                    .height(130.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Image(
                                painter = painterResource(id = R.drawable.az_f2),
                                contentDescription = "Angel Zaberski Sr. 2",
                                modifier = Modifier
                                    .weight(1f)
                                    .height(130.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }

                        // --- 3 BLANK LINES ---
                        Spacer(modifier = Modifier.height(28.dp))

                        // --- SON (BG) ---
                        Text(
                            text = "Ангел Заберски – син (род. 1973 г.)",
                            color = amberColor,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Ангел Заберски – син (род. 1973 г.) е сред най-изявените съвременни български джаз пианисти, композитори, аранжори и музикални педагози.\n\n" +
                                    "• Произход и образование: Роден е на 1 февруари 1973 г. в София в музикалното семейство на композитора Ангел Заберски-баща и певицата Маргарита Радинска. Завършва Националното музикално училище „Любомир Пипков“ и Националната музикална академия „Проф. Панчо Владигеров“ със специалност композиция.\n\n" +
                                    "• Творческа кариера: Известен е с виртуозните си изпълнения и майсторското съчетаване на класическа музика с джаз елементи. Автор е на десетки пиеси за джаз трио, биг бенд, симфоничен оркестър и камерни състави.\n\n" +
                                    "• Значими проекти: Основател е на формации като „Ангел Заберски Биг Бенд“ и „Angel Zaberski Trio“. Сред най-успешните му авторски концерти и албуми са поредиците „Back to Classics“, „Symphony Jazz“ и „Jazz Standards“.\n\n" +
                                    "• Педагогическа дейност: Преподава джаз импровизация, пиано и аранжиране в Нов български университет, където е хабилитиран доцент.\n\n" +
                                    "• Отличия: Носител е на редица престижни награди за принос към българската култура и изкуство, сред които „Кристална лира“ и „Златно перо“.",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Son Photos (az_s1, az_s2, az_s3)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.az_s1),
                                contentDescription = "Angel Zaberski Jr. 1",
                                modifier = Modifier
                                    .weight(1f)
                                    .height(110.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Image(
                                painter = painterResource(id = R.drawable.az_s2),
                                contentDescription = "Angel Zaberski Jr. 2",
                                modifier = Modifier
                                    .weight(1f)
                                    .height(110.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Image(
                                painter = painterResource(id = R.drawable.az_s3),
                                contentDescription = "Angel Zaberski Jr. 3",
                                modifier = Modifier
                                    .weight(1f)
                                    .height(110.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                    } else {
                        // --- FATHER (ENG) ---
                        Text(
                            text = "Angel Zaberski - Father (1936–2011)",
                            color = amberColor,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Angel Zaberski - Father (1936–2011) was a prominent Bulgarian composer, arranger, and music educator, considered a key figure in Bulgarian pop music.\n\n" +
                                    "• Origin & Education: Born Feb 8, 1936. Graduated from the National Academy of Music in Opera Singing under Prof. Hristo Hristov.\n\n" +
                                    "• Career: Worked for many years as a music editor and arranger at Bulgarian National Radio.\n\n" +
                                    "• Iconic Works: Composed classic hits including 'Kaliakra' (1st prize at Golden Orpheus 1966), 'Liley', and 'Byala Pesen'.\n\n" +
                                    "• Legacy: Trained generations of Bulgarian pop and jazz singers.\n\n" +
                                    "• Musical Family: Father of jazz pianist Angel Zaberski Jr. and singer Neda Zaberska.",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Father Photos (az_f1 & az_f2)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.az_f1),
                                contentDescription = "Angel Zaberski Sr. 1",
                                modifier = Modifier
                                    .weight(1f)
                                    .height(130.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Image(
                                painter = painterResource(id = R.drawable.az_f2),
                                contentDescription = "Angel Zaberski Sr. 2",
                                modifier = Modifier
                                    .weight(1f)
                                    .height(130.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }

                        // --- 3 BLANK LINES ---
                        Spacer(modifier = Modifier.height(28.dp))

                        // --- SON (ENG) ---
                        Text(
                            text = "Angel Zaberski - Son (b. 1973)",
                            color = amberColor,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Angel Zaberski - Son (b. 1973) is among the most prominent contemporary Bulgarian jazz pianists, composers, arrangers, and music educators.\n\n" +
                                    "• Origin & Education: Born Feb 1, 1973, in Sofia to composer Angel Zaberski Sr. and singer Margarita Radinska. Graduated from Lubomir Pipkov National School of Music and Pancho Vladigerov National Academy of Music.\n\n" +
                                    "• Artistic Career: Renowned for virtuous performances blending classical music with jazz elements. Composed works for jazz trio, big band, and symphony orchestra.\n\n" +
                                    "• Major Projects: Founder of 'Angel Zaberski Big Band' and 'Angel Zaberski Trio'. Creator of acclaimed series 'Back to Classics', 'Symphony Jazz', and 'Jazz Standards'.\n\n" +
                                    "• Teaching: Teaches jazz improvisation, piano, and arranging at New Bulgarian University as an Associate Professor.\n\n" +
                                    "• Awards: Recipient of prestigious Bulgarian cultural honors including 'Crystal Lyre' and 'Golden Feather'.",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Son Photos (az_s1, az_s2, az_s3)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.az_s1),
                                contentDescription = "Angel Zaberski Jr. 1",
                                modifier = Modifier
                                    .weight(1f)
                                    .height(110.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Image(
                                painter = painterResource(id = R.drawable.az_s2),
                                contentDescription = "Angel Zaberski Jr. 2",
                                modifier = Modifier
                                    .weight(1f)
                                    .height(110.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Image(
                                painter = painterResource(id = R.drawable.az_s3),
                                contentDescription = "Angel Zaberski Jr. 3",
                                modifier = Modifier
                                    .weight(1f)
                                    .height(110.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = {
                            creditsLanguage = if (creditsLanguage == "BG") "ENG" else "BG"
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = amberColor),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(
                            text = if (creditsLanguage == "BG") "ENG" else "BG",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    TextButton(onClick = { showCreditsDialog = false }) {
                        Text(
                            text = if (creditsLanguage == "BG") "Затвори" else "Close",
                            color = amberColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            },
            containerColor = Color(0xFF2A2A2A),
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
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
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Credits Button
            ControlChip(
                text = "Credits",
                isActive = showCreditsDialog,
                activeColor = amberColor,
                onClick = { showCreditsDialog = true }
            )

            Spacer(modifier = Modifier.width(6.dp))

            // Horizontally Scrollable Controls Row
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Harmony Preset Cycle Button
                ControlChip(
                    text = "Harmony: ${harmonyMode.displayName}",
                    isActive = harmonyMode != HarmonyMode.NONE,
                    activeColor = amberColor,
                    onClick = {
                        val nextOrdinal = (harmonyMode.ordinal + 1) % HarmonyMode.values().size
                        harmonyMode = HarmonyMode.values()[nextOrdinal]
                        updateActiveNotes(directPressedNotes.toSet())
                    },
                    onLongClick = {
                        harmonyMode = HarmonyMode.NONE
                        updateActiveNotes(directPressedNotes.toSet())
                    }
                )

                // Bass Line Toggle Button
                ControlChip(
                    text = "Bass Line",
                    isActive = bassLineEnabled,
                    activeColor = amberColor,
                    onClick = {
                        bassLineEnabled = !bassLineEnabled
                        updateActiveNotes(directPressedNotes.toSet())
                    }
                )

                ControlChip(
                    text = "Black Keys Precision",
                    isActive = blackKeysEasyHit,
                    activeColor = amberColor,
                    onClick = { blackKeysEasyHit = !blackKeysEasyHit }
                )

                ControlChip(
                    text = "No Labels",
                    isActive = noLabels,
                    activeColor = amberColor,
                    onClick = { noLabels = !noLabels }
                )

                ControlChip(
                    text = "Large Keyboard",
                    isActive = isKeySizeEnlarged,
                    activeColor = amberColor,
                    onClick = { isKeySizeEnlarged = !isKeySizeEnlarged }
                )

                ControlChip(
                    text = "Dynamic",
                    isActive = dynamicEnabled,
                    activeColor = amberColor,
                    onClick = { dynamicEnabled = !dynamicEnabled }
                )

                ControlChip(
                    text = "Sustain 1/2",
                    isActive = sustainMode == SustainMode.HALF,
                    activeColor = amberColor,
                    onClick = {
                        sustainMode = if (sustainMode == SustainMode.HALF) SustainMode.NONE else SustainMode.HALF
                    }
                )

                ControlChip(
                    text = "Sustain",
                    isActive = sustainMode == SustainMode.FULL,
                    activeColor = amberColor,
                    onClick = {
                        sustainMode = if (sustainMode == SustainMode.FULL) SustainMode.NONE else SustainMode.FULL
                    }
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Break / Exit App Button (TOP RIGHT)
            ControlChip(
                text = "Break",
                isActive = false,
                activeColor = amberColor,
                onClick = {
                    (context as? Activity)?.finish()
                }
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        // --- MAIN PIANO KEYBOARD ---
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black)
        ) {
            val totalWidthPx = constraints.maxWidth.toFloat()
            val totalHeightPx = constraints.maxHeight.toFloat()

            val whiteKeyWidthPx = totalWidthPx / numVisibleWhiteKeys
            val blackKeyWidthPx = whiteKeyWidthPx * 0.6f
            val blackKeyHeightPx = totalHeightPx * 0.58f

            fun resolveMidiNoteAt(offset: Offset): Int? {
                val x = offset.x
                val y = offset.y

                if (x < 0 || x > totalWidthPx || y < 0 || y > totalHeightPx) return null

                if (y <= blackKeyHeightPx) {
                    if (blackKeysEasyHit) {
                        var closestBlackNote: Int? = null
                        var minDistance = Float.MAX_VALUE

                        for (i in 0 until numVisibleWhiteKeys) {
                            val globalIndex = firstWhiteKeyIndex + i
                            val noteInOctave = globalIndex % 7
                            if (noteInOctave in listOf(0, 1, 3, 4, 5)) {
                                val blackKeyCenterX = whiteKeyWidthPx * (i + 1)
                                val distance = abs(x - blackKeyCenterX)
                                if (distance < minDistance) {
                                    minDistance = distance
                                    closestBlackNote = getMidiForWhiteKey(globalIndex) + 1
                                }
                            }
                        }
                        return closestBlackNote
                    } else {
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
                }

                val keyRelativeIndex = (x / whiteKeyWidthPx).toInt().coerceIn(0, numVisibleWhiteKeys - 1)
                return getMidiForWhiteKey(firstWhiteKeyIndex + keyRelativeIndex)
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(firstWhiteKeyIndex, harmonyMode, bassLineEnabled, dynamicEnabled, sustainMode, numVisibleWhiteKeys, blackKeysEasyHit) {
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

                        val keyState = when {
                            directPressedNotes.contains(midiNote) -> KeyPressedState.DIRECT
                            activeMidiNotes.contains(midiNote) -> KeyPressedState.HARMONY
                            else -> KeyPressedState.NONE
                        }

                        WhiteKeyVisual(
                            name = name,
                            octave = octave,
                            showLabel = !noLabels,
                            keyState = keyState,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Render Black Keys dynamically aligned
                val density = LocalContext.current.resources.displayMetrics.density
                for (i in 0 until numVisibleWhiteKeys) {
                    val globalIndex = firstWhiteKeyIndex + i
                    val noteInOctave = globalIndex % 7
                    if (noteInOctave in listOf(0, 1, 3, 4, 5)) {
                        val blackMidiNote = getMidiForWhiteKey(globalIndex) + 1

                        val xOffsetDp = ((whiteKeyWidthPx * (i + 1)) - (blackKeyWidthPx / 2f)) / density
                        val widthDp = blackKeyWidthPx / density

                        val keyState = when {
                            directPressedNotes.contains(blackMidiNote) -> KeyPressedState.DIRECT
                            activeMidiNotes.contains(blackMidiNote) -> KeyPressedState.HARMONY
                            else -> KeyPressedState.NONE
                        }

                        BlackKeyVisual(
                            keyState = keyState,
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

        // --- BOTTOM NAVIGATION & MINI-MAP OVERVIEW ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .background(Color(0xFF151515), RoundedCornerShape(6.dp))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (navLocked) amberColor else Color(0xFF2A2A2A))
                    .clickable { navLocked = !navLocked },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (navLocked) "🔒" else "🔓",
                    fontSize = 10.sp
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF222222))
                    .pointerInput(navLocked, numVisibleWhiteKeys) {
                        if (!navLocked) {
                            detectTapGestures { offset ->
                                val totalMiniWhiteKeys = 49f
                                val keyWidth = size.width / totalMiniWhiteKeys
                                val tappedIndex = (offset.x / keyWidth).toInt()
                                firstWhiteKeyIndex = (tappedIndex - (numVisibleWhiteKeys / 2)).coerceIn(0, maxFirstIndex)
                            }
                        }
                    }
                    .pointerInput(navLocked, numVisibleWhiteKeys) {
                        if (!navLocked) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                val totalMiniWhiteKeys = 49f
                                val keyWidth = size.width / totalMiniWhiteKeys
                                val draggedIndex = (change.position.x / keyWidth).toInt()
                                firstWhiteKeyIndex = (draggedIndex - (numVisibleWhiteKeys / 2)).coerceIn(0, maxFirstIndex)
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

                    for (i in 0 until totalWhiteKeys) {
                        val isVisibleRegion = i >= startVisibleWhite && i < (startVisibleWhite + numVisibleWhiteKeys)
                        val keyColor = if (isVisibleRegion) Color(0xFFE0E0E0) else Color(0xFF555555)

                        drawRect(
                            color = keyColor,
                            topLeft = Offset(i * miniKeyWidth + 0.5f, 0f),
                            size = Size(miniKeyWidth - 1f, size.height)
                        )
                    }

                    val blackPattern = listOf(0, 1, 3, 4, 5)
                    for (oct in 0 until totalOctaves) {
                        blackPattern.forEach { indexInOctave ->
                            val whiteIndex = oct * 7 + indexInOctave
                            val isVisibleRegion = whiteIndex >= startVisibleWhite && whiteIndex < (startVisibleWhite + numVisibleWhiteKeys)
                            val blackColor = if (isVisibleRegion) Color.Black else Color(0xFF2A2A2A)

                            val xPos = ((whiteIndex + 1) * miniKeyWidth) - (miniBlackWidth / 2f)
                            drawRect(
                                color = blackColor,
                                topLeft = Offset(xPos, 0f),
                                size = Size(miniBlackWidth, miniBlackHeight)
                            )
                        }
                    }

                    val highlightLeft = startVisibleWhite * miniKeyWidth
                    val highlightWidth = numVisibleWhiteKeys * miniKeyWidth
                    drawRect(
                        color = Color(0xFFFFB300).copy(alpha = 0.35f),
                        topLeft = Offset(highlightLeft, 0f),
                        size = Size(highlightWidth, size.height)
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
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
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2A2A2A))
                        .clickable(enabled = !navLocked && firstWhiteKeyIndex < maxFirstIndex) {
                            firstWhiteKeyIndex = (firstWhiteKeyIndex + 1).coerceAtMost(maxFirstIndex)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "+",
                        color = if (firstWhiteKeyIndex < maxFirstIndex) amberColor else Color.Gray,
                        fontSize = 13.sp,
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
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (isActive) activeColor else Color(0xFF333333))
            .pointerInput(onClick, onLongClick) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongClick?.invoke() }
                )
            }
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
    showLabel: Boolean,
    keyState: KeyPressedState,
    modifier: Modifier = Modifier
) {
    val keyColor = when (keyState) {
        KeyPressedState.DIRECT -> Color(0xFF808080)   // Direct Finger Touch: Grey
        KeyPressedState.HARMONY -> Color(0xFFD0D0D0)  // Harmony/Bass Addition: Light Grey
        KeyPressedState.NONE -> Color.White
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = 1.dp)
            .clip(RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
            .background(keyColor),
        contentAlignment = Alignment.BottomCenter
    ) {
        if (showLabel) {
            Text(
                text = "$name$octave",
                color = if (keyState == KeyPressedState.DIRECT) Color.White else Color.DarkGray,
                fontSize = 11.sp,
                fontWeight = if (keyState != KeyPressedState.NONE) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }
}

@Composable
fun BlackKeyVisual(
    keyState: KeyPressedState,
    modifier: Modifier = Modifier
) {
    val keyColor = when (keyState) {
        KeyPressedState.DIRECT -> Color(0xFF555555)   // Direct Finger Touch: Dark Grey
        KeyPressedState.HARMONY -> Color(0xFFAAAAAA)  // Harmony/Bass Addition: Light Grey
        KeyPressedState.NONE -> Color(0xFF151515)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp))
            .background(keyColor)
    )
}