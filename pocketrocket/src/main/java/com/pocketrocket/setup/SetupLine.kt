package com.pocketrocket.setup

/**
 * One stdout line of the setup script, parsed by its contract:
 * `[step N/M] title`, `[progress 0-100] detail`, `[done]`, `[error] message`; anything else is log text.
 */
sealed interface SetupLine {
    data class Step(val number: Int, val total: Int, val title: String) : SetupLine
    data class Progress(val percent: Int, val detail: String) : SetupLine
    data object Done : SetupLine
    data class Error(val message: String) : SetupLine
    data class Log(val text: String) : SetupLine

    companion object {
        private val STEP = Regex("""^\[step (\d+)/(\d+)]\s*(.*)$""")
        private val PROGRESS = Regex("""^\[progress (\d{1,3})]\s*(.*)$""")
        private val ERROR = Regex("""^\[(?:error|fail)]\s*(.*)$""")

        fun parse(line: String): SetupLine {
            STEP.find(line)?.destructured?.let { (n, m, title) -> return Step(n.toInt(), m.toInt(), title.trim()) }
            PROGRESS.find(line)?.destructured?.let { (p, detail) -> return Progress(p.toInt().coerceIn(0, 100), detail.trim()) }
            if (line.trim() == "[done]" || line.startsWith("[done] ")) return Done
            ERROR.find(line)?.destructured?.let { (message) -> return Error(message.trim()) }
            return Log(line)
        }
    }
}
