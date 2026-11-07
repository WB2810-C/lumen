package com.wolfram.lumen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.util.Locale

/* ---------- Voice ---------- */

private fun speechIntent(context: Context) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)

/** Listens through the remote's microphone and turns speech into text, inside Lumen. */
@Stable
class VoiceInput(
    private val context: Context,
    private val partialCallback: (String) -> Unit,
    private val finalCallback: (String) -> Unit,
    private val errorCallback: (Int) -> Unit,
) {
    val available: Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** How loud you're speaking, 0..1 — makes the orb pulse along. */
    var level by mutableFloatStateOf(0f)
        private set

    private var recognizer: SpeechRecognizer? = null

    fun start() {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) {
                level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                level = 0f
            }
            override fun onError(error: Int) {
                level = 0f
                errorCallback(error)
            }
            override fun onResults(results: Bundle?) {
                level = 0f
                finalCallback(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let(partialCallback)
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        r.startListening(speechIntent(context))
    }

    fun cancel() {
        level = 0f
        recognizer?.cancel()
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }
}

@Composable
private fun rememberVoiceInput(
    onPartial: (String) -> Unit,
    onFinal: (String) -> Unit,
    onError: (Int) -> Unit,
): VoiceInput {
    val context = LocalContext.current
    val latestPartial by rememberUpdatedState(onPartial)
    val latestFinal by rememberUpdatedState(onFinal)
    val latestError by rememberUpdatedState(onError)
    val voice = remember { VoiceInput(context, { latestPartial(it) }, { latestFinal(it) }, { latestError(it) }) }
    DisposableEffect(voice) { onDispose { voice.destroy() } }
    return voice
}

private fun hasMicPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/* ---------- The panel ---------- */

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AssistantPanel(
    controller: AssistantController,
    glow: Color,
    hasInputs: Boolean,
    onAsk: (String) -> Unit,
    onClose: () -> Unit,
) {
    val theme = LocalLumenTheme.current
    val context = LocalContext.current
    var typing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val didntCatch = stringResource(R.string.assistant_didnt_catch)

    val voice = rememberVoiceInput(
        onPartial = { controller.partial = it },
        onFinal = { text ->
            controller.partial = ""
            controller.phase = AssistantPhase.IDLE
            if (text.isBlank()) notice = didntCatch else onAsk(text)
        },
        onError = { code ->
            controller.partial = ""
            controller.phase = AssistantPhase.IDLE
            when (code) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> notice = didntCatch
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> typing = true
                else -> {
                    notice = didntCatch
                    typing = true
                }
            }
        },
    )
    // Fallback: the TV's own speech screen.
    val systemSpeech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        controller.phase = AssistantPhase.IDLE
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (text.isNullOrBlank()) notice = didntCatch else onAsk(text)
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            controller.phase = AssistantPhase.LISTENING
            voice.start()
        } else {
            typing = true
        }
    }
    val startListening: () -> Unit = {
        notice = null
        when {
            voice.available && hasMicPermission(context) -> {
                controller.phase = AssistantPhase.LISTENING
                voice.start()
            }
            voice.available -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
            else -> try {
                controller.phase = AssistantPhase.LISTENING
                systemSpeech.launch(speechIntent(context))
            } catch (e: Exception) {
                controller.phase = AssistantPhase.IDLE
                typing = true
            }
        }
    }

    BackHandler {
        when {
            controller.phase == AssistantPhase.LISTENING -> {
                voice.cancel()
                controller.cancel()
            }
            typing -> typing = false
            else -> {
                Sounds.play(Sound.BACK)
                onClose()
            }
        }
    }

    val panelShape = RoundedCornerShape(22.dp)
    val panelFill = if (theme.isLight) Color.White.copy(alpha = 0.86f) else Color(0xFF101018).copy(alpha = 0.80f)

    Box(
        Modifier
            .fillMaxSize()
            .background(theme.base.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(24.dp)
                .width(356.dp)
                .fillMaxHeight()
                .shadow(28.dp, panelShape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(panelShape)
                .background(panelFill)
                .border(1.dp, theme.line, panelShape)
                // Keep the remote's focus inside the panel while it's open.
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup()
                .padding(horizontal = 24.dp, vertical = 22.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistantOrb(size = 26.dp, glow = glow, active = false)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(stringResource(R.string.assistant_title), color = theme.content, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.assistant_subtitle), color = theme.content.copy(alpha = 0.7f), fontSize = 9.sp)
                }
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when {
                    controller.phase == AssistantPhase.LISTENING -> ListeningView(glow, voice.level, controller.partial)
                    else -> Conversation(controller, glow, notice)
                }
            }

            run {
                when {
                    controller.phase == AssistantPhase.LISTENING -> HintLine(stringResource(R.string.assistant_cancel))
                    typing -> TypingField(
                        onSend = { text ->
                            typing = false
                            onAsk(text)
                        },
                    )
                    else -> {
                        Suggestions(hasInputs, onAsk)
                        Spacer(Modifier.height(14.dp))
                        MicRow(
                            glow = glow,
                            focusWhen = controller.phase,
                            onMic = startListening,
                            onKeyboard = { typing = true },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.SetupNotice(onClose: () -> Unit) {
    val theme = LocalLumenTheme.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { focus.requestFocus() }
    }
    Column(
        Modifier
            .align(Alignment.Center)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.assistant_setup_title), color = theme.content, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.assistant_setup_body), color = theme.content.copy(alpha = 0.8f), fontSize = 12.sp, lineHeight = 17.sp)
        PillButton(stringResource(R.string.assistant_close), onClick = onClose, modifier = Modifier.focusRequester(focus))
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.ListeningView(glow: Color, level: Float, partial: String) {
    val theme = LocalLumenTheme.current
    Column(
        Modifier.align(Alignment.Center),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AssistantOrb(size = 118.dp, glow = glow, active = true, level = level)
        Spacer(Modifier.height(30.dp))
        Text(stringResource(R.string.assistant_listening), color = theme.content.copy(alpha = 0.75f), fontSize = 13.sp)
        if (partial.isNotBlank()) {
            Text(
                text = "“$partial”",
                color = theme.content,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.Conversation(controller: AssistantController, glow: Color, notice: String?) {
    val theme = LocalLumenTheme.current
    val scroll = rememberScrollState()
    val last = controller.turns.lastOrNull()
    LaunchedEffect(controller.turns.size, last?.answer, last?.actions?.size) {
        scroll.animateScrollTo(scroll.maxValue)
    }
    Column(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (controller.turns.isEmpty()) {
            Text(
                text = greetingText(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) + ".",
                color = theme.content,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(stringResource(R.string.assistant_intro), color = theme.content.copy(alpha = 0.78f), fontSize = 13.sp, lineHeight = 18.sp)
        }
        controller.turns.takeLast(4).forEachIndexed { i, turn ->
            val isLast = i == controller.turns.takeLast(4).lastIndex
            TurnView(turn, thinking = isLast && controller.phase == AssistantPhase.THINKING && turn.answer == null, glow = glow)
        }
        if (notice != null) {
            Text(notice, color = theme.content.copy(alpha = 0.7f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun TurnView(turn: AssistantTurn, thinking: Boolean, glow: Color) {
    val theme = LocalLumenTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                text = turn.question,
                color = theme.content,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(13.dp, 13.dp, 4.dp, 13.dp))
                    .background(theme.chip)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        when {
            thinking -> Row(verticalAlignment = Alignment.CenterVertically) {
                AssistantOrb(size = 16.dp, glow = glow, active = true)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.assistant_thinking), color = theme.content.copy(alpha = 0.7f), fontSize = 12.sp)
            }
            turn.answer != null -> Text(
                text = turn.answer,
                color = if (turn.isError) theme.content.copy(alpha = 0.75f) else theme.content,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        if (turn.actions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                turn.actions.forEach { action ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(glow.copy(alpha = 0.22f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CheckGlyph(Modifier.size(9.dp), theme.content)
                        Spacer(Modifier.width(5.dp))
                        Text(action, color = theme.content, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        if (turn.sources.isNotEmpty()) {
            Text(
                text = stringResource(R.string.assistant_sources, turn.sources.take(3).joinToString(", ")),
                color = theme.content.copy(alpha = 0.65f),
                fontSize = 9.sp,
            )
        }
    }
}

@Composable
private fun Suggestions(hasInputs: Boolean, onAsk: (String) -> Unit) {
    val suggestions = listOf(
        stringResource(R.string.assistant_suggest_open),
        stringResource(if (hasInputs) R.string.assistant_suggest_hdmi else R.string.assistant_suggest_theme),
        stringResource(R.string.assistant_suggest_weather),
    )
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        suggestions.forEach { s -> PillButton(s, onClick = { onAsk(s) }) }
    }
}

@Composable
private fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalLumenTheme.current
    var focused by remember { mutableStateOf(false) }
    Text(
        text = text,
        color = if (focused) theme.onContent else theme.content,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (focused) theme.content else theme.chip)
            .border(1.dp, theme.line, RoundedCornerShape(50))
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) Sounds.play(Sound.FOCUS)
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                Sounds.play(Sound.SELECT)
                onClick()
            }
            .padding(horizontal = 11.dp, vertical = 7.dp),
    )
}

@Composable
private fun MicRow(glow: Color, focusWhen: AssistantPhase, onMic: () -> Unit, onKeyboard: () -> Unit) {
    val theme = LocalLumenTheme.current
    val micFocus = remember { FocusRequester() }
    var micFocused by remember { mutableStateOf(false) }
    var keyFocused by remember { mutableStateOf(false) }
    val micScale by animateFloatAsState(if (micFocused) 1.08f else 1f, tween(150), label = "mic")
    LaunchedEffect(focusWhen) {
        if (focusWhen == AssistantPhase.IDLE) {
            delay(80)
            runCatching { micFocus.requestFocus() }
        }
    }
    val speak = stringResource(R.string.assistant_speak)
    val typeLabel = stringResource(R.string.assistant_type)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .graphicsLayer {
                    scaleX = micScale
                    scaleY = micScale
                }
                .shadow(if (micFocused) 18.dp else 6.dp, CircleShape, ambientColor = glow, spotColor = glow)
                .clip(CircleShape)
                .background(theme.content)
                .border(if (micFocused) 3.dp else 0.dp, glow.copy(alpha = 0.7f), CircleShape)
                .focusRequester(micFocus)
                .onFocusChanged {
                    micFocused = it.isFocused
                    if (it.isFocused) Sounds.play(Sound.FOCUS)
                }
                .semantics { contentDescription = speak }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    Sounds.play(Sound.SELECT)
                    onMic()
                },
            contentAlignment = Alignment.Center,
        ) {
            MicGlyph(Modifier.size(22.dp), theme.onContent)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(speak, color = theme.content, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.assistant_speak_sub), color = theme.content.copy(alpha = 0.7f), fontSize = 9.sp)
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (keyFocused) theme.content else theme.chip)
                .onFocusChanged { keyFocused = it.isFocused }
                .semantics { contentDescription = typeLabel }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onKeyboard),
            contentAlignment = Alignment.Center,
        ) {
            KeyboardGlyph(Modifier.size(16.dp), if (keyFocused) theme.onContent else theme.content)
        }
    }
}

@Composable
private fun TypingField(onSend: (String) -> Unit) {
    val theme = LocalLumenTheme.current
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    BasicTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = TextStyle(color = theme.content, fontSize = 14.sp),
        cursorBrush = SolidColor(theme.content),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) onSend(text) }),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(theme.chip)
                    .border(1.5.dp, theme.content.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (text.isEmpty()) {
                    Text(stringResource(R.string.assistant_type_hint), color = theme.content.copy(alpha = 0.5f), fontSize = 14.sp)
                }
                inner()
            }
        },
    )
}

@Composable
private fun HintLine(text: String) {
    Text(
        text = text,
        color = LocalLumenTheme.current.content.copy(alpha = 0.65f),
        fontSize = 10.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

/** The assistant's glowing orb. It breathes while listening or thinking, and grows with your voice. */
@Composable
fun AssistantOrb(size: Dp, glow: Color, active: Boolean, level: Float = 0f) {
    val theme = LocalLumenTheme.current
    val transition = rememberInfiniteTransition(label = "orb")
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    val voice by animateFloatAsState(level, tween(120), label = "voice")
    val pulse = if (active) maxOf(breath * 0.6f, voice) else 0f
    val light = if (theme.followsApp) androidx.compose.ui.graphics.lerp(glow, Color.White, 0.35f) else theme.light
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val c = center
        drawCircle(light.copy(alpha = 0.08f + 0.10f * pulse), radius = r * (0.92f + 0.08f * pulse), center = c)
        drawCircle(light.copy(alpha = 0.16f + 0.08f * pulse), radius = r * (0.78f + 0.05f * pulse), center = c)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White, light, glow, theme.third),
                center = Offset(c.x - r * 0.22f, c.y - r * 0.26f),
                radius = r * 0.95f,
            ),
            radius = r * (0.62f + 0.05f * pulse),
            center = c,
        )
    }
}
