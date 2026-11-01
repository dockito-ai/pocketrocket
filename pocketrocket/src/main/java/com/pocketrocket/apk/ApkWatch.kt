package com.pocketrocket.apk

import com.pocketrocket.sessions.ChatItem
import com.pocketrocket.sessions.shellRunning
import org.json.JSONObject
import java.io.File

/**
 * The APK cards of one session's chat (decision 99): for each finished Bash call, the APKs it wrote ([ApkFinder]),
 * each version of an APK on the first call that made it. A call is looked at once, after it has finished, and only
 * when its shell's clock times are known (a chat read back from the transcript has none). Nothing is saved: the same
 * chat gives the same cards whenever it is shown. Disk work: call [cards] off the main thread.
 */
class ApkWatch(private val folder: File, private val home: File, private val readOutput: (String) -> String = ::tail) {
    /** What each finished call wrote, by tool id; worked out once per call. */
    private val found = HashMap<String, List<BuiltApk>>()

    @Synchronized
    fun cards(items: List<ChatItem>): Map<String, List<BuiltApk>> {
        val shown = HashSet<String>()
        val cards = LinkedHashMap<String, List<BuiltApk>>()
        for (item in items) {
            val tool = item as? ChatItem.Tool ?: continue
            if (!ready(tool)) continue
            val apks = found.getOrPut(tool.id) { look(tool) }.filter { shown.add(it.key) }
            if (apks.isNotEmpty()) cards[tool.id] = apks
        }
        return cards
    }

    private fun look(tool: ChatItem.Tool): List<BuiltApk> {
        val run = tool.shell!!
        val start = run.startedAt!!
        val end = run.endedAt!!
        val command = runCatching { JSONObject(tool.inputJson).optString("command") }.getOrDefault("")
        val output = listOfNotNull(tool.result, run.outputFile?.let(readOutput)).joinToString("\n")
        return runCatching { ApkFinder.find(folder, home, command, output, start, end) }.getOrDefault(emptyList())
    }

    companion object {
        /** A Bash call that has finished, in the foreground or the background, with its start and end times known. */
        fun ready(tool: ChatItem.Tool): Boolean =
            tool.name == "Bash" && tool.result != null && !tool.shellRunning && tool.shell?.startedAt != null && tool.shell.endedAt != null

        private const val TAIL_BYTES = 64 * 1024L

        /** The end of a background shell's output file, where a build says what it made. */
        private fun tail(path: String): String = runCatching {
            java.io.RandomAccessFile(path, "r").use { file ->
                val from = (file.length() - TAIL_BYTES).coerceAtLeast(0)
                val bytes = ByteArray((file.length() - from).toInt())
                file.seek(from)
                file.readFully(bytes)
                String(bytes)
            }
        }.getOrDefault("")
    }
}
