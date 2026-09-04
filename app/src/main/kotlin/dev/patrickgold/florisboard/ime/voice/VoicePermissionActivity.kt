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

package dev.patrickgold.florisboard.ime.voice

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * A borderless, invisible activity whose only job is to ask for the microphone permission on behalf of the
 * keyboard. An input method service has no activity of its own and therefore cannot request runtime
 * permissions directly, so the keyboard starts this activity, which immediately shows the system dialog and
 * finishes again once the user has answered.
 */
class VoicePermissionActivity : ComponentActivity() {
    companion object {
        /**
         * Invoked exactly once with the user's answer, then cleared again. The keyboard sets this right
         * before starting the activity so it can begin dictating as soon as the permission is granted,
         * instead of making the user tap the microphone key a second time.
         */
        var onResult: ((Boolean) -> Unit)? = null
    }

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val callback = onResult
        onResult = null
        callback?.invoke(isGranted)
        finish()
        overridePendingTransitionCompat()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    @Suppress("DEPRECATION")
    private fun overridePendingTransitionCompat() {
        overridePendingTransition(0, 0)
    }
}
