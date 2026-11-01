package com.pocketrocket.node

import org.json.JSONObject
import java.io.File

/** npm's global packages on the phone (`npm install -g`), read from their folders: Wrangler and the deploy CLIs. */
object NpmGlobal {
    /** npm's folder for [pkg] under [prefix], whoever ran the install. */
    fun dir(prefix: File, pkg: String): File = File(prefix, "lib/node_modules/$pkg")

    /** The version in an npm package's package.json ([dir]), or null. */
    fun version(dir: File): String? =
        runCatching { JSONObject(File(dir, "package.json").readText()).optString("version").takeIf { it.isNotBlank() } }.getOrNull()
}
