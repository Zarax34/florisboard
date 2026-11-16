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

package dev.patrickgold.florisboard.ime.clipboard.vault

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.apptheme.FlorisAppTheme
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.android.showLongToastSync
import org.florisboard.lib.android.showShortToastSync
import java.text.DateFormat
import java.util.Date

/**
 * Shows the secrets taken out of the clipboard, after biometric (or lock screen) authentication. Closes, and with
 * it locks the vault, as soon as it leaves the screen.
 */
class SecretVaultActivity : FragmentActivity() {
    companion object {
        fun launch(context: Context) {
            context.startActivity(
                Intent(context, SecretVaultActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private val prefs by FlorisPreferenceStore
    private var secrets by mutableStateOf<List<VaultSecret>?>(null)
    /** The lock screen credential page is a separate activity, it must not lock the vault. */
    private var isAuthenticating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots, screen recordings or recents thumbnail of the secrets
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            val theme by prefs.other.settingsTheme.collectAsState()
            FlorisAppTheme(theme = theme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    secrets?.let { VaultContent(it) }
                }
            }
        }
        if (!SecretVault.isAvailable(this)) {
            showLongToastSync(R.string.vault__unavailable)
            finish()
            return
        }
        authenticate()
    }

    override fun onStop() {
        super.onStop()
        if (!isAuthenticating && !isChangingConfigurations) finish()
    }

    private fun authenticate() {
        isAuthenticating = true
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                isAuthenticating = false
                load()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                isAuthenticating = false
                finish()
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.vault__title))
            .setSubtitle(getString(R.string.vault__auth_subtitle))
            .apply {
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                        setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                    }
                    BiometricManager.from(this@SecretVaultActivity).canAuthenticate(BIOMETRIC_STRONG) ==
                        BiometricManager.BIOMETRIC_SUCCESS -> {
                        setAllowedAuthenticators(BIOMETRIC_STRONG)
                        setNegativeButtonText(getString(R.string.vault__cancel))
                    }
                    // Before Android 11 strong biometrics can't be combined with the lock screen credential
                    else -> setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                }
            }
            .build()
        prompt.authenticate(info)
    }

    private fun load() {
        lifecycleScope.launch {
            when (val result = withContext(Dispatchers.IO) { SecretVault.open(this@SecretVaultActivity) }) {
                is VaultOpenResult.Opened -> secrets = result.secrets
                VaultOpenResult.NeedsAuth -> {
                    showLongToastSync(R.string.vault__auth_strong_required)
                    finish()
                }
                VaultOpenResult.KeyInvalidated -> {
                    showLongToastSync(R.string.vault__key_invalidated)
                    finish()
                }
            }
        }
    }

    private fun copy(secret: VaultSecret) {
        val clip = ClipData.newPlainText(getString(R.string.vault__title), secret.text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(clip)
        showShortToastSync(R.string.vault__copied)
    }

    private fun delete(secret: VaultSecret) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { SecretVault.delete(this@SecretVaultActivity, secret.id) }
            secrets = secrets?.filter { it.id != secret.id }
        }
    }

    private fun deleteAll() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { SecretVault.deleteAll(this@SecretVaultActivity) }
            secrets = emptyList()
        }
    }

    @Composable
    private fun VaultContent(items: List<VaultSecret>) {
        var showDeleteAll by remember { mutableStateOf(false) }
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = getString(R.string.vault__title),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (items.isNotEmpty()) {
                    TextButton(onClick = { showDeleteAll = true }) { Text(getString(R.string.vault__delete_all)) }
                }
            }
            if (items.isEmpty()) {
                Text(modifier = Modifier.padding(16.dp), text = getString(R.string.vault__empty))
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(items, key = { it.id }) { secret -> SecretRow(secret) }
            }
        }
        if (showDeleteAll) {
            AlertDialog(
                onDismissRequest = { showDeleteAll = false },
                title = { Text(getString(R.string.vault__delete_all_title)) },
                text = { Text(getString(R.string.vault__delete_all_message)) },
                confirmButton = {
                    TextButton(onClick = { showDeleteAll = false; deleteAll() }) { Text(getString(R.string.vault__delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteAll = false }) { Text(getString(R.string.vault__cancel)) }
                },
            )
        }
    }

    @Composable
    private fun SecretRow(secret: VaultSecret) {
        var revealed by rememberSaveable(secret.id) { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { revealed = !revealed }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (revealed) secret.text else "•".repeat(secret.text.length.coerceIn(8, 24)),
                    fontFamily = FontFamily.Monospace,
                    maxLines = if (revealed) 6 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(secret.createdAtMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { copy(secret) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = getString(R.string.vault__copy))
            }
            IconButton(onClick = { delete(secret) }) {
                Icon(Icons.Default.Delete, contentDescription = getString(R.string.vault__delete))
            }
        }
    }
}
