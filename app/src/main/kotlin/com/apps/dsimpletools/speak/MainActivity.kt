package com.apps.dsimpletools.speak

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.EditText
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.apps.dsimpletools.speak.accessibility.DictationAccessibilityService
import com.apps.dsimpletools.speak.format.FormatController
import com.apps.dsimpletools.speak.format.FormatLevel
import com.apps.dsimpletools.speak.format.NanoFeatureState
import com.apps.dsimpletools.speak.ui.theme.SpeakTheme
import com.apps.dsimpletools.speak.util.AccessibilityUtils
import kotlinx.coroutines.launch

data class StatusUiState(
    val accessibilityEnabled: Boolean = false,
    val micGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
    val formatLevel: FormatLevel = FormatLevel.LIGHT,
    val nanoState: NanoFeatureState? = null
)

class MainActivity : ComponentActivity() {

    private var uiState by mutableStateOf(StatusUiState())

    private val formatController by lazy { FormatController.getInstance(this) }

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
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
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
            formatLevel = formatController.level
        )
        // Nano feature availability is an async on-device check; update when it resolves.
        lifecycleScope.launch {
            val state = formatController.nanoFeatureState()
            uiState = uiState.copy(nanoState = state)
        }
    }
}

@Composable
private fun MainScreen(
    state: StatusUiState,
    onRequestAccessibility: () -> Unit,
    onRequestMic: () -> Unit,
    onRequestNotifications: () -> Unit,
    onSelectFormatLevel: (FormatLevel) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
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
