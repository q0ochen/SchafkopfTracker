package io.github.q0ochen.schafkopf.app.ui.ai

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.q0ochen.schafkopf.R
import io.github.q0ochen.schafkopf.app.ai.TextGeneratorFactory
import io.github.q0ochen.schafkopf.app.ai.messageRes
import io.github.q0ochen.schafkopf.app.ui.common.ChoiceRow
import io.github.q0ochen.schafkopf.app.ui.common.SectionHeading
import io.github.q0ochen.schafkopf.app.ui.common.SubScreenTopBar
import io.github.q0ochen.schafkopf.app.ui.common.SwitchRow
import io.github.q0ochen.schafkopf.app.ui.navigation.Navigator
import io.github.q0ochen.schafkopf.domain.model.AiProvider
import org.koin.androidx.compose.koinViewModel

private const val AI_STUDIO_URL = "https://aistudio.google.com/apikey"

/** Einstellungen des Abend-Kommentators: Sprachmodell an oder aus, Anbieter, Schlüssel, Modell und Namen. */
@Composable
fun AiSettingsScreen(navigator: Navigator) {
    val viewModel: AiSettingsViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val ai = state.ai

    val stored = stringResource(R.string.ai_key_stored)
    val deleted = stringResource(R.string.ai_key_deleted)
    val failed = stringResource(R.string.ai_key_failed)
    LaunchedEffect(state.keyMessage) {
        val message = state.keyMessage ?: return@LaunchedEffect
        try {
            snackbar.showSnackbar(
                when (message) {
                    KeyMessage.STORED -> stored
                    KeyMessage.DELETED -> deleted
                    KeyMessage.FAILED -> failed
                },
            )
        } finally {
            viewModel.keyMessageShown()
        }
    }

    Scaffold(
        topBar = { SubScreenTopBar(title = stringResource(R.string.ai_title), onBack = { navigator.back() }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Hint(stringResource(R.string.ai_intro))
            SwitchRow(
                title = stringResource(R.string.ai_enabled),
                subtitle = stringResource(R.string.ai_enabled_hint),
                checked = ai.enabled,
                onCheckedChange = viewModel::setEnabled,
            )
            if (!ai.enabled) return@Column

            SectionHeading(stringResource(R.string.ai_provider))
            ChoiceRow(
                options = AiProvider.entries,
                selected = ai.provider,
                label = {
                    stringResource(
                        when (it) {
                            AiProvider.GEMINI -> R.string.ai_provider_gemini
                            AiProvider.OPENAI_COMPATIBLE -> R.string.ai_provider_openai
                        },
                    )
                },
                onSelect = viewModel::setProvider,
            )
            if (ai.provider == AiProvider.OPENAI_COMPATIBLE) {
                Hint(stringResource(R.string.ai_provider_openai_hint))
                var url by rememberSaveable(ai.provider) { mutableStateOf(ai.baseUrl) }
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        viewModel.setBaseUrl(it)
                    },
                    label = { Text(stringResource(R.string.ai_base_url)) },
                    supportingText = {
                        Text(stringResource(if (state.hostAllowed) R.string.ai_base_url_hint else R.string.ai_base_url_invalid))
                    },
                    isError = !state.hostAllowed,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionHeading(stringResource(R.string.ai_key))
            Text(
                stringResource(if (state.hasKey) R.string.ai_key_saved else R.string.ai_key_missing),
                style = MaterialTheme.typography.bodyLarge,
            )
            var key by remember { mutableStateOf("") }
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text(stringResource(R.string.ai_key_input)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    enabled = key.isNotBlank(),
                    onClick = {
                        viewModel.saveKey(key)
                        key = ""
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.ai_key_save)) }
                if (state.hasKey) {
                    OutlinedButton(
                        onClick = viewModel::deleteKey,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.ai_key_delete)) }
                }
            }
            if (ai.provider == AiProvider.GEMINI) {
                Hint(stringResource(R.string.ai_key_how))
                TextButton(
                    onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AI_STUDIO_URL)))
                        } catch (_: ActivityNotFoundException) {
                            // Ohne Browser bleibt der Hinweis oben mit der Adresse.
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.ai_key_open_studio)) }
            }

            SectionHeading(stringResource(R.string.ai_model))
            var model by rememberSaveable(ai.provider) { mutableStateOf(ai.model) }
            OutlinedTextField(
                value = model,
                onValueChange = {
                    model = it
                    viewModel.setModel(it)
                },
                label = { Text(stringResource(R.string.ai_model)) },
                placeholder = { if (ai.provider == AiProvider.GEMINI) Text(TextGeneratorFactory.DEFAULT_GEMINI_MODEL) },
                supportingText = {
                    Text(
                        stringResource(
                            if (ai.provider == AiProvider.GEMINI) R.string.ai_model_hint_gemini else R.string.ai_model_hint_openai,
                        ),
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                enabled = !state.busy,
                onClick = viewModel::loadModels,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.ai_models_load)) }
            state.modelsError?.let { error ->
                Text(stringResource(error.messageRes()), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            state.models?.let { models ->
                Hint(pluralStringResource(R.plurals.ai_models_loaded, models.size, models.size))
                Column {
                    for (name in models) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable(role = Role.Button) {
                                    model = name
                                    viewModel.setModel(name)
                                }
                                .padding(vertical = 12.dp),
                        )
                        HorizontalDivider()
                    }
                }
            }

            SectionHeading(stringResource(R.string.ai_real_names))
            SwitchRow(
                title = stringResource(R.string.ai_real_names),
                subtitle = stringResource(R.string.ai_real_names_hint),
                checked = ai.sendRealNames,
                onCheckedChange = viewModel::setSendRealNames,
            )
            Hint(stringResource(R.string.ai_privacy))
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
