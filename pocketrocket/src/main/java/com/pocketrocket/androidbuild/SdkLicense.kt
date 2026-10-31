package com.pocketrocket.androidbuild

import java.net.HttpURLConnection
import java.net.URL

/**
 * The Android SDK License Agreement, read from Google's SDK manifest at the moment it is shown, so the user reads
 * Google's current text. Nothing is written until they accept; see the Android build page.
 */
object SdkLicense {
    private const val MANIFEST_URL = "https://dl.google.com/android/repository/repository2-3.xml"
    private const val ID = "android-sdk-license"

    /**
     * What goes in `licenses/android-sdk-license`, the file `sdkmanager --licenses` writes. It is the hash Google's tools
     * expect for the 16 January 2019 text; the emulator's Gradle plugin accepted it against today's manifest (spike).
     * If Google publishes new terms, packages already installed keep working and the plugin refuses only new downloads.
     */
    const val ACCEPTED_HASH = "24333f8a63b6825ea9c5514f83c2829b004d1fee"

    /** Passed to the setup script, which refuses to download the SDK without it. */
    const val HASH_ENV = "POCKETROCKET_SDK_LICENSE_HASH"

    /** The license text in a manifest, or null if it has none. */
    fun parse(manifest: String): String? {
        val open = Regex("""<license\s[^>]*id="$ID"[^>]*>""").find(manifest) ?: return null
        val end = manifest.indexOf("</license>", open.range.last)
        if (end < 0) return null
        val text = manifest.substring(open.range.last + 1, end).trim()
        return unescape(text).takeIf { it.isNotBlank() }
    }

    private fun unescape(text: String): String = text
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** Blocking. Throws when the manifest cannot be read or has no license: nothing can be accepted then. */
    fun fetch(): String {
        val connection = URL(MANIFEST_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            val manifest = connection.inputStream.bufferedReader().use { it.readText() }
            return parse(manifest) ?: error("Google's manifest has no SDK license")
        } finally {
            connection.disconnect()
        }
    }
}
