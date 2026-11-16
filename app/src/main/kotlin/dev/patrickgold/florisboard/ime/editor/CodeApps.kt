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

package dev.patrickgold.florisboard.ime.editor

/** Code editors, IDEs and terminals, where the keyboard switches to its developer profile. */
object CodeApps {
    private val Packages = setOf(
        "com.foxdebug.acode", "com.foxdebug.acodefree", // Acode
        "io.spck", // Spck Editor
        "com.blacksquircle.ui", // Squircle CE
        "com.rhmsoft.edit", "com.rhmsoft.edit.pro", // QuickEdit
        "com.aide.ui", // AIDE
        "ru.iiec.pydroid3", "ru.iiec.cxxdroid", // Pydroid 3, Cxxdroid
        "com.paprbit.dcoder", // Dcoder
        "com.server.auditor.ssh.client", // Termius
        "org.connectbot", "com.sonelli.juicessh", // SSH clients
        "jackpal.androidterm",
    )

    fun isCodeApp(packageName: String?): Boolean {
        if (packageName == null) return false
        return packageName in Packages || packageName == "com.termux" || packageName.startsWith("com.termux.")
    }
}
