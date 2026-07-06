package com.apps.dsimpletools.speak

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.apps.dsimpletools.speak.accessibility.DictationAccessibilityService
import com.apps.dsimpletools.speak.format.FormatController
import com.apps.dsimpletools.speak.format.FormatLevel
import com.apps.dsimpletools.speak.format.NanoFeatureState
import com.apps.dsimpletools.speak.speaker.EnrollmentRecorder
import com.apps.dsimpletools.speak.speaker.SpeakerVerifier
import com.apps.dsimpletools.speak.ui.theme.SpeakTheme
import com.apps.dsimpletools.speak.util.AccessibilityUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StatusUiState(
    val accessibilityEnabled: Boolean = false,
    val micGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
    val formatLevel: FormatLevel = FormatLevel.LIGHT,
    val nanoState: NanoFeatureState? = null,
    val speakerModelPresent: Boolean = false,
    val speakerEnrolled: Boolean = false,
    val speakerUtterances: Int = 0,
    val speakerGateEnabled: Boolean = false
)

enum class EnrollPhase { IDLE, RECORDING, PROCESSING }

data class EnrollmentUiState(
    val active: Boolean = false,
    val promptIndex: Int = 0,
    val phase: EnrollPhase = EnrollPhase.IDLE,
    val message: String? = null
)

/** Guided enrollment prompts — natural, ~8-12 s everyday-dictation sentences. */
private val ENROLL_PROMPTS = listOf(
    "Hey, can you send me the notes from this morning's meeting? I'd like to review " +
        "the action items before I reply to the client this afternoon.",
    "Let's plan to grab lunch around noon tomorrow, maybe at that new place downtown, " +
        "and afterwards we could walk over to the park if the weather holds up.",
    "I've been thinking about the project timeline, and I really believe we can finish " +
        "the first version by the end of next week if everyone stays focused."
)

class MainActivity : ComponentActivity() {

    private var uiState by mutableStateOf(StatusUiState())
    private var enrollState by mutableStateOf(EnrollmentUiState())

    private val formatController by lazy { FormatController.getInstance(this) }
    private val speakerVerifier by lazy { SpeakerVerifier.getInstance(this) }

    // Live only during an enrollment flow; per-utterance embeddings accumulate here.
    private var enrollRecorder: EnrollmentRecorder? = null
    private val enrollEmbeddings = mutableListOf<FloatArray>()

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshState() }

    private val notificationsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SpeakTheme {
                Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                    MainScreen(
                        state = uiState,
                        onRequestAccessibility = { openAccessibilitySettings() },
                        onRequestMic = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        onRequestNotifications = { promptForNotifications() },
                        onSelectFormatLevel = { level ->
                            formatController.level = level
                            uiState = uiState.copy(formatLevel = level)
                        },
                        onToggleGate = { enabled -> onToggleGate(enabled) },
                        onEnroll = { startEnrollment() },
                        onClearEnrollment = { clearEnrollment() }
                    )
                    if (enrollState.active) {
                        EnrollmentDialog(
                            state = enrollState,
                            promptText = ENROLL_PROMPTS.getOrElse(enrollState.promptIndex) { "" },
                            promptCount = ENROLL_PROMPTS.size,
                            onRecord = { onEnrollRecord() },
                            onStop = { onEnrollStop() },
                            onCancel = { cancelEnrollment() }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    override fun onDestroy() {
        // If the activity is torn down mid-enrollment, don't leak the mic.
        enrollRecorder?.cancel()
        enrollRecorder = null
        super.onDestroy()
    }

    // ---- Speaker enrollment flow ----

    private fun startEnrollment() {
        if (!speakerVerifier.isModelFilePresent) {
            toast("Voice model not installed")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            toast("Microphone permission needed")
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        enrollEmbeddings.clear()
        enrollRecorder = EnrollmentRecorder(this)
        enrollState = EnrollmentUiState(active = true, promptIndex = 0, phase = EnrollPhase.IDLE)
        // Warm the extractor so the first "Stop" isn't stalled by a 28 MB model load.
        lifecycleScope.launch { withContext(Dispatchers.Default) { speakerVerifier.ensureLoaded() } }
    }

    private fun onEnrollRecord() {
        val recorder = enrollRecorder ?: return
        if (recorder.start()) {
            enrollState = enrollState.copy(phase = EnrollPhase.RECORDING, message = null)
        } else {
            toast("Could not start recording")
        }
    }

    private fun onEnrollStop() {
        val recorder = enrollRecorder ?: return
        enrollState = enrollState.copy(phase = EnrollPhase.PROCESSING, message = null)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) { recorder.stopAndTrim() }
            if (result.netSpeechSec < MIN_ENROLL_SPEECH_SEC) {
                enrollState = enrollState.copy(
                    phase = EnrollPhase.IDLE,
                    message = "Only ${"%.1f".format(result.netSpeechSec)}s of speech — " +
                        "please read the whole sentence again."
                )
                return@launch
            }
            val embedding = withContext(Dispatchers.Default) {
                speakerVerifier.computeEmbedding(result.samples)
            }
            if (embedding == null) {
                enrollState = enrollState.copy(
                    phase = EnrollPhase.IDLE,
                    message = "Couldn't process that recording — please try again."
                )
                return@launch
            }
            enrollEmbeddings.add(embedding)
            val next = enrollState.promptIndex + 1
            if (next >= ENROLL_PROMPTS.size) {
                finishEnrollment()
            } else {
                enrollState = enrollState.copy(promptIndex = next, phase = EnrollPhase.IDLE, message = null)
            }
        }
    }

    private fun finishEnrollment() {
        // Persist is quick file IO + in-memory maths (a few KB); safe to run inline.
        val ok = speakerVerifier.enrollFromEmbeddings(enrollEmbeddings.toList())
        enrollRecorder?.cancel()
        enrollRecorder = null
        enrollEmbeddings.clear()
        enrollState = EnrollmentUiState(active = false)
        toast(
            if (ok) "Voice enrolled — only your voice will be transcribed"
            else "Enrollment failed, please try again"
        )
        refreshState()
    }

    private fun cancelEnrollment() {
        enrollRecorder?.cancel()
        enrollRecorder = null
        enrollEmbeddings.clear()
        enrollState = EnrollmentUiState(active = false)
    }

    private fun onToggleGate(enabled: Boolean) {
        speakerVerifier.gateEnabled = enabled
        uiState = uiState.copy(speakerGateEnabled = enabled)
    }

    private fun clearEnrollment() {
        speakerVerifier.clear()
        toast("Voice enrollment cleared")
        refreshState()
    }

    private fun promptForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Pre-33 has no runtime prompt; deep-link to the per-app notification screen instead.
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            startActivity(intent)
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun refreshState() {
        uiState = uiState.copy(
            accessibilityEnabled = AccessibilityUtils.isAccessibilityServiceEnabled(
                this,
                DictationAccessibilityService::class.java
            ),
            micGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED,
            notificationsGranted = NotificationManagerCompat.from(this).areNotificationsEnabled(),
            formatLevel = formatController.level,
            speakerModelPresent = speakerVerifier.isModelFilePresent,
            speakerEnrolled = speakerVerifier.isEnrolled,
            speakerUtterances = speakerVerifier.utteranceCount,
            speakerGateEnabled = speakerVerifier.gateEnabled
        )
        // Nano feature availability is an async on-device check; update when it resolves.
        lifecycleScope.launch {
            val state = formatController.nanoFeatureState()
            uiState = uiState.copy(nanoState = state)
        }
    }

    companion object {
        /** Minimum net (VAD-trimmed) speech required per enrollment utterance. */
        private const val MIN_ENROLL_SPEECH_SEC = 4.0f
    }
}

@Composable
private fun MainScreen(
    state: StatusUiState,
    onRequestAccessibility: () -> Unit,
    onRequestMic: () -> Unit,
    onRequestNotifications: () -> Unit,
    onSelectFormatLevel: (FormatLevel) -> Unit,
    onToggleGate: (Boolean) -> Unit,
    onEnroll: () -> Unit,
    onClearEnrollment: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(text = "Speak", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        StatusRow(
            label = "Accessibility service",
            enabled = state.accessibilityEnabled,
            actionLabel = "Open settings",
            onClick = onRequestAccessibility
        )
        StatusRow(
            label = "Microphone permission",
            enabled = state.micGranted,
            actionLabel = "Grant",
            onClick = onRequestMic
        )
        StatusRow(
            label = "Notifications",
            enabled = state.notificationsGranted,
            actionLabel = "Grant",
            onClick = onRequestNotifications
        )

        Spacer(modifier = Modifier.height(24.dp))
        VoiceSection(
            state = state,
            onToggleGate = onToggleGate,
            onEnroll = onEnroll,
            onClearEnrollment = onClearEnrollment
        )

        Spacer(modifier = Modifier.height(24.dp))
        FormattingSection(
            level = state.formatLevel,
            nanoState = state.nanoState,
            onSelectFormatLevel = onSelectFormatLevel
        )

        Spacer(modifier = Modifier.height(24.dp))
        Text(text = "Test fields", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        Text(text = "Classic EditText (View system):")
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { context ->
                EditText(context).apply {
                    hint = "Tap here to focus the classic EditText"
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(text = "Compose OutlinedTextField:")
        var composeFieldValue by remember { mutableStateOf("") }
        OutlinedTextField(
            value = composeFieldValue,
            onValueChange = { composeFieldValue = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Compose test field") }
        )
    }
}

@Composable
private fun VoiceSection(
    state: StatusUiState,
    onToggleGate: (Boolean) -> Unit,
    onEnroll: () -> Unit,
    onClearEnrollment: () -> Unit
) {
    Text(text = "Voice", style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(8.dp))

    val statusLine = when {
        !state.speakerModelPresent -> "Voice model not installed"
        state.speakerEnrolled -> "Enrolled (${state.speakerUtterances} utterances)"
        else -> "Not enrolled"
    }
    Text(text = statusLine, style = MaterialTheme.typography.bodyLarge)
    Spacer(modifier = Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(text = "Only my voice", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (state.speakerEnrolled) {
                    "Drop other voices before transcription"
                } else {
                    "Enroll your voice to enable"
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
        Switch(
            checked = state.speakerGateEnabled && state.speakerEnrolled,
            onCheckedChange = onToggleGate,
            enabled = state.speakerEnrolled
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onEnroll,
            enabled = state.speakerModelPresent,
            modifier = Modifier.weight(1f)
        ) {
            Text(if (state.speakerEnrolled) "Re-enroll" else "Enroll")
        }
        OutlinedButton(
            onClick = onClearEnrollment,
            enabled = state.speakerEnrolled,
            modifier = Modifier.weight(1f)
        ) {
            Text("Clear")
        }
    }
}

@Composable
private fun EnrollmentDialog(
    state: EnrollmentUiState,
    promptText: String,
    promptCount: Int,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (state.phase != EnrollPhase.PROCESSING) onCancel() },
        title = { Text("Enroll your voice (${state.promptIndex + 1} of $promptCount)") },
        text = {
            Column {
                Text("Read this aloud naturally, then tap Stop:")
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = promptText, fontStyle = FontStyle.Italic)
                Spacer(modifier = Modifier.height(12.dp))
                val status = when (state.phase) {
                    EnrollPhase.IDLE -> state.message ?: "Tap Start recording when you're ready."
                    EnrollPhase.RECORDING -> "Recording… speak now."
                    EnrollPhase.PROCESSING -> "Processing…"
                }
                Text(text = status, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            when (state.phase) {
                EnrollPhase.IDLE -> TextButton(onClick = onRecord) { Text("Start recording") }
                EnrollPhase.RECORDING -> TextButton(onClick = onStop) { Text("Stop") }
                EnrollPhase.PROCESSING -> TextButton(onClick = {}, enabled = false) { Text("Please wait") }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                enabled = state.phase != EnrollPhase.PROCESSING
            ) { Text("Cancel") }
        }
    )
}

@Composable
private fun FormattingSection(
    level: FormatLevel,
    nanoState: NanoFeatureState?,
    onSelectFormatLevel: (FormatLevel) -> Unit
) {
    Text(text = "Transcript formatting", style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FormatLevel.entries.forEach { option ->
            val label = when (option) {
                FormatLevel.OFF -> "Off"
                FormatLevel.LIGHT -> "Light"
                FormatLevel.FULL -> "Full"
            }
            if (option == level) {
                Button(onClick = { onSelectFormatLevel(option) }, modifier = Modifier.weight(1f)) {
                    Text(label)
                }
            } else {
                OutlinedButton(onClick = { onSelectFormatLevel(option) }, modifier = Modifier.weight(1f)) {
                    Text(label)
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    val nanoLabel = when (nanoState) {
        NanoFeatureState.AVAILABLE -> "Available"
        NanoFeatureState.DOWNLOADABLE -> "Downloadable"
        NanoFeatureState.DOWNLOADING -> "Downloading"
        NanoFeatureState.UNAVAILABLE -> "Unavailable"
        null -> "Checking…"
    }
    Text(
        text = "Gemini Nano (Full mode): $nanoLabel",
        style = MaterialTheme.typography.bodySmall
    )
    Text(
        text = "Off = raw · Light = rules only · Full = rules + on-device LLM",
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun StatusRow(
    label: String,
    enabled: Boolean,
    actionLabel: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(text = label, style = MaterialTheme.typography.bodyLarge)
                Text(text = if (enabled) "Enabled" else "Disabled")
            }
            if (!enabled) {
                Button(onClick = onClick) { Text(actionLabel) }
            }
        }
    }
}
