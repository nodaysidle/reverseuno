package com.nodaysidle.reverseuno.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.nodaysidle.reverseuno.core.LANGUAGE_OPTIONS
import com.nodaysidle.reverseuno.core.Store
import com.nodaysidle.reverseuno.core.StoreData
import com.nodaysidle.reverseuno.core.statsFromHistory
import com.nodaysidle.reverseuno.dictation.HubDictation
import com.nodaysidle.reverseuno.dictation.HubPhase
import com.nodaysidle.reverseuno.security.KeyVault
import com.nodaysidle.reverseuno.security.Secret
import com.nodaysidle.reverseuno.service.VoiceAccessibilityService
import com.nodaysidle.reverseuno.stt.ProviderApi
import com.nodaysidle.reverseuno.stt.SttModel
import kotlinx.coroutines.launch
import java.text.NumberFormat

private sealed interface KeyTest {
    data object Idle : KeyTest
    data object Running : KeyTest
    data class Ok(val ms: Long) : KeyTest
    data class Rejected(val ms: Long) : KeyTest
    data class Failed(val message: String) : KeyTest
}

private fun lastFour(key: String) = "•••• " + key.takeLast(4)

private fun toast(context: Context, message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

@Composable
fun SettingsScreen(data: StoreData) {
    val context = LocalContext.current
    val settings = data.settings
    val scope = rememberCoroutineScope()

    // Bumped on resume so system-setting statuses re-read after the user comes back from Settings.
    var resume by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resume++ }
    val accessibilityOn = remember(resume) { SystemSetup.isAccessibilityOn(context) }
    val batteryOk = remember(resume) { SystemSetup.isBatteryUnrestricted(context) }
    var micGranted by remember(resume) { mutableStateOf(SystemSetup.hasMic(context)) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        if (!granted) SystemSetup.openAppDetails(context)
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)!!.use { it.write(Store.exportJson().toByteArray()) }
        }.isSuccess
        toast(context, if (ok) "Exported" else "Could not export")
    }

    var sttModels by remember { mutableStateOf<List<SttModel>>(emptyList()) }
    LaunchedEffect(Unit) { sttModels = ProviderApi.openRouterSttModels(context) }

    val hub by HubDictation.state.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "dev"
    }

    LazyColumn(
        contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            PageHeader(
                eyebrow = "Everything local, except audio in flight",
                title = "Settings",
                sub = "The bubble is the whole interface. Everything else is here when you want to tune it.",
                aside = {
                    Chip(
                        "${NumberFormat.getIntegerInstance().format(statsFromHistory(data.history).totalWords)} words",
                        bg = Tokens.greenSoft, fg = Tokens.green, dot = Tokens.green,
                    )
                },
            )
        }

        item {
            Section("Dictation", "The bubble, the microphone, and the language you speak.") {
                SettingRow(
                    "Floating bubble",
                    "Appears above the keyboard in any text field. Tap to start and stop, hold to talk.",
                    divider = false,
                ) {
                    Toggle(settings.bubble.enabled, {
                        Store.updateSettings { s -> s.copy(bubble = s.bubble.copy(enabled = it)) }
                        VoiceAccessibilityService.updateOverlayVisibility(it)
                    }, "Floating bubble")
                }
                SettingRow("Accessibility service", "Lets the bubble see text fields and insert what you say.") {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusChip(accessibilityOn, "On", "Off")
                        Btn("Open settings", { SystemSetup.openAccessibility(context) }, size = BtnSize.SM)
                    }
                }
                SettingRow("Microphone", "Audio is recorded only while the bubble is listening.") {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusChip(micGranted, "Granted", "Not granted")
                        if (!micGranted) Btn("Grant", { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }, size = BtnSize.SM)
                    }
                }
                SettingRow(
                    "Language",
                    "Auto-detect works across languages. Pick one only to force it.",
                ) {
                    Select(
                        settings.stt.language,
                        LANGUAGE_OPTIONS.map { it.value to it.label },
                        { v -> Store.updateSettings { it.copy(stt = it.stt.copy(language = v)) } },
                    )
                }
                SettingRow(
                    "Test the pipeline",
                    hub.message.ifBlank { "Records, transcribes and saves to history. Nothing is inserted." },
                ) {
                    Btn(
                        when (hub.phase) {
                            HubPhase.LISTENING -> "Stop"
                            HubPhase.WORKING -> "Working…"
                            else -> "Test"
                        },
                        { HubDictation.toggle(context) },
                        variant = if (hub.phase == HubPhase.LISTENING) BtnVariant.DANGER else BtnVariant.PRIMARY,
                        size = BtnSize.SM,
                        enabled = hub.phase != HubPhase.WORKING,
                    )
                }
            }
        }

        item {
            Section("Providers", "Keys stay in this phone's keystore. Audio goes to OpenRouter. Cleanup goes to Claude Haiku, with DeepSeek as a fallback.") {
                SettingRow("Speech-to-text", "OpenRouter only, multilingual models.", divider = false, stacked = true) {
                    KeyRow(Secret.OPENROUTER, "sk-or-…", resume)
                }
                SettingRow("Transcription model", "Loaded live from OpenRouter.") {
                    val current = settings.stt.model
                    val options = sttModels.map { it.id to it.label }.let { list ->
                        if (list.any { it.first == current }) list else listOf(current to current) + list
                    }
                    Select(current, options, { v -> Store.updateSettings { it.copy(stt = it.stt.copy(model = v)) } })
                }
                SettingRow("Anthropic", "Cleanup with ${settings.format.anthropicModel}. Tidies every transcript after speech-to-text.", stacked = true) {
                    KeyRow(Secret.ANTHROPIC, "sk-ant-…", resume)
                }
                SettingRow("DeepSeek fallback", "Used only when Anthropic fails or times out: ${settings.format.deepseekModel} (V4.1 Flash).", stacked = true) {
                    KeyRow(Secret.DEEPSEEK, "sk-…", resume)
                }
            }
        }

        item {
            Section("Phone setup", "Some phones stop background services unless you allow them.") {
                var first = true
                if (SystemSetup.isXiaomi) {
                    SettingRow("Autostart", "Lets reverseuno start after a reboot.", divider = false) {
                        Btn("Open", { SystemSetup.openAutostart(context) }, size = BtnSize.SM)
                    }
                    first = false
                }
                SettingRow("Battery: no restrictions", "Keeps the bubble alive in the background.", divider = !first) {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusChip(batteryOk, "Unrestricted", "Restricted")
                        if (!batteryOk) Btn("Open", { SystemSetup.openBattery(context) }, size = BtnSize.SM)
                    }
                }
            }
        }

        item {
            Section("Insertion") {
                SettingRow("Append a space after each dictation", "So the next thing you say or type does not run on.", divider = false) {
                    Toggle(settings.insert.appendSpace, { v ->
                        Store.updateSettings { it.copy(insert = it.insert.copy(appendSpace = v)) }
                    }, "Append a space")
                }
            }
        }

        item {
            Section("Appearance") {
                SettingRow("Theme", divider = false) {
                    Segmented(
                        settings.appearance.theme,
                        listOf("night" to "Night", "paper" to "Paper"),
                        { v -> Store.updateSettings { it.copy(appearance = it.appearance.copy(theme = v)) } },
                    )
                }
            }
        }

        item {
            Section("History", "Stored on this phone only.") {
                SettingRow("Keep last", "Older dictations are dropped past this count.", divider = false) {
                    Select(
                        settings.history.maxEntries,
                        listOf(100, 250, 500, 1000, 2500).let { l ->
                            (if (settings.history.maxEntries in l) l else (l + settings.history.maxEntries).sorted())
                        }.map { it to "$it dictations" },
                        { v -> Store.updateSettings { it.copy(history = it.history.copy(maxEntries = v)) } },
                    )
                }
                SettingRow("Auto-learn from edits", "When you edit a dictation, suggest the changed word as a dictionary correction.") {
                    Toggle(settings.history.autoLearn, { v ->
                        Store.updateSettings { it.copy(history = it.history.copy(autoLearn = v)) }
                    }, "Auto-learn from edits")
                }
                SettingRow("Export", "Everything as one JSON file.") {
                    Btn("Export", { exportLauncher.launch("reverseuno-export.json") }, size = BtnSize.SM)
                }
                SettingRow("Clear history", if (confirmClear) "Delete ${data.history.size} dictations and their suggestions? This cannot be undone." else "Removes every dictation.") {
                    if (confirmClear) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Btn("Cancel", { confirmClear = false }, size = BtnSize.SM)
                            Btn("Delete", { Store.clearHistory(); confirmClear = false }, variant = BtnVariant.DANGER, size = BtnSize.SM)
                        }
                    } else {
                        Btn("Clear", { confirmClear = true }, variant = BtnVariant.DANGER, size = BtnSize.SM, enabled = data.history.isNotEmpty())
                    }
                }
            }
        }

        item {
            Section("About") {
                SettingRow("reverseuno", "Local history · cloud audio", divider = false) {
                    Chip("v$version", mono = true)
                }
            }
        }
    }
}

@Composable
private fun StatusChip(ok: Boolean, on: String, off: String) =
    if (ok) Chip(on, bg = Tokens.greenSoft, fg = Tokens.green, dot = Tokens.green)
    else Chip(off, bg = Tokens.redSoft, fg = Tokens.red, dot = Tokens.red)

/** Masked key field with Save, Test and Clear. The key is read from and written to KeyVault only. */
@Composable
private fun KeyRow(secret: Secret, placeholder: String, resume: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stored by remember(resume) { mutableStateOf(KeyVault.get(context, secret)) }
    var input by remember { mutableStateOf("") }
    var test by remember { mutableStateOf<KeyTest>(KeyTest.Idle) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (stored.isNotEmpty()) {
                StatusChip(true, "saved", "")
                Text(lastFour(stored), style = Type.mono, color = Tokens.muted)
            } else {
                StatusChip(false, "", "not set")
            }
            when (val t = test) {
                is KeyTest.Ok -> Chip("ok · ${t.ms} ms", bg = Tokens.greenSoft, fg = Tokens.green)
                is KeyTest.Rejected -> Chip("rejected · ${t.ms} ms", bg = Tokens.redSoft, fg = Tokens.red)
                is KeyTest.Failed -> Chip("error · ${t.message}", bg = Tokens.redSoft, fg = Tokens.red)
                KeyTest.Running -> Chip("testing…")
                KeyTest.Idle -> Unit
            }
        }
        Input(
            input, { input = it },
            if (stored.isNotEmpty()) "Replace key ($placeholder)" else placeholder,
            Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Save", {
                KeyVault.set(context, secret, input)
                stored = KeyVault.get(context, secret)
                input = ""
                test = KeyTest.Idle
                toast(context, "${secret.label} key saved")
            }, variant = BtnVariant.PRIMARY, size = BtnSize.SM, enabled = input.isNotBlank())
            Btn("Test", {
                test = KeyTest.Running
                val key = stored
                scope.launch {
                    val start = System.currentTimeMillis()
                    test = try {
                        val ok = ProviderApi.checkKey(secret, key)
                        val ms = System.currentTimeMillis() - start
                        if (ok) KeyTest.Ok(ms) else KeyTest.Rejected(ms)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        KeyTest.Failed(e.message?.take(40) ?: "network")
                    }
                }
            }, size = BtnSize.SM, enabled = stored.isNotEmpty() && test != KeyTest.Running)
            Btn("Clear", {
                KeyVault.set(context, secret, "")
                stored = ""
                test = KeyTest.Idle
                toast(context, "${secret.label} key cleared")
            }, variant = BtnVariant.DANGER, size = BtnSize.SM, enabled = stored.isNotEmpty())
        }
    }
}
