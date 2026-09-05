/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.app.settings.theme

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.theme.AiThemeImporter
import dev.patrickgold.florisboard.ime.theme.AiThemePrompt
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.compose.FlorisInfoCard
import org.florisboard.lib.compose.FlorisOutlinedBox
import org.florisboard.lib.compose.defaultFlorisOutlinedBox
import org.florisboard.lib.compose.stringRes

@Composable
fun AiThemeScreen() = FlorisScreen {
    title = stringRes(R.string.settings__theme__ai__title)
    previewFieldVisible = false

    content {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        // The scope's `prefs` has a @Composable getter, so grab what we need here rather than inside the
        // coroutine that installs the theme.
        val themePrefs = prefs.theme

        var description by rememberSaveable { mutableStateOf("") }
        var stylesheetJson by rememberSaveable { mutableStateOf("") }
        var themeLabel by rememberSaveable { mutableStateOf("") }
        var isNightTheme by rememberSaveable { mutableStateOf(true) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var isInstalling by remember { mutableStateOf(false) }

        FlorisInfoCard(
            modifier = Modifier.padding(8.dp),
            text = stringRes(R.string.settings__theme__ai__info),
        )

        FlorisOutlinedBox(
            modifier = Modifier.defaultFlorisOutlinedBox(),
            title = stringRes(R.string.settings__theme__ai__step_1_title),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringRes(R.string.settings__theme__ai__step_1_description),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringRes(R.string.settings__theme__ai__design_brief_label)) },
                    placeholder = { Text(stringRes(R.string.settings__theme__ai__design_brief_placeholder)) },
                    minLines = 3,
                )
                Button(
                    modifier = Modifier.padding(top = 12.dp),
                    onClick = {
                        context.copyToClipboard(AiThemePrompt.build(description))
                        Toast.makeText(
                            context,
                            R.string.settings__theme__ai__prompt_copied,
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                ) {
                    Text(stringRes(R.string.settings__theme__ai__copy_prompt))
                }
            }
        }

        FlorisOutlinedBox(
            modifier = Modifier.defaultFlorisOutlinedBox(),
            title = stringRes(R.string.settings__theme__ai__step_2_title),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringRes(R.string.settings__theme__ai__step_2_description),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 320.dp)
                        .padding(top = 12.dp),
                    value = stylesheetJson,
                    onValueChange = {
                        stylesheetJson = it
                        errorMessage = null
                    },
                    label = { Text(stringRes(R.string.settings__theme__ai__stylesheet_label)) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    value = themeLabel,
                    onValueChange = { themeLabel = it },
                    label = { Text(stringRes(R.string.settings__theme__ai__theme_name_label)) },
                    singleLine = true,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = stringRes(R.string.settings__theme__ai__is_night_theme),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(checked = isNightTheme, onCheckedChange = { isNightTheme = it })
                }
                errorMessage?.let { message ->
                    Text(
                        modifier = Modifier.padding(top = 12.dp),
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            AiThemeImporter.validate(stylesheetJson)
                                .onSuccess {
                                    errorMessage = null
                                    Toast.makeText(
                                        context,
                                        R.string.settings__theme__ai__stylesheet_valid,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                                .onFailure { errorMessage = it.localizedMessage ?: it.toString() }
                        },
                    ) {
                        Text(stringRes(R.string.settings__theme__ai__check))
                    }
                    Button(
                        enabled = stylesheetJson.isNotBlank() && !isInstalling,
                        onClick = {
                            isInstalling = true
                            scope.launch {
                                // Installing writes the stylesheet out and zips it into a .flex file, so
                                // keep it off the main thread.
                                val result = withContext(Dispatchers.IO) {
                                    AiThemeImporter.install(context, themeLabel, stylesheetJson, isNightTheme)
                                }
                                result
                                    .onSuccess { componentName ->
                                        errorMessage = null
                                        if (isNightTheme) {
                                            themePrefs.nightThemeId.set(componentName)
                                        } else {
                                            themePrefs.dayThemeId.set(componentName)
                                        }
                                        Toast.makeText(
                                            context,
                                            R.string.settings__theme__ai__installed,
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                    .onFailure { errorMessage = it.localizedMessage ?: it.toString() }
                                isInstalling = false
                            }
                        },
                    ) {
                        Text(stringRes(R.string.settings__theme__ai__apply))
                    }
                }
            }
        }
    }
}

private fun Context.copyToClipboard(text: String) {
    val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboardManager.setPrimaryClip(ClipData.newPlainText("FlorisBoard AI theme prompt", text))
}
