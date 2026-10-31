package com.pocketrocket.sessions

/** Where a session stands, as shown on its card and in the switcher (decision 7, BRD R11). */
enum class SessionStatus {
    /** The process is being launched. */
    Starting,

    /** Claude is thinking or running tools. */
    Working,

    /** Ready for the first message. */
    WaitingForInput,

    /** Claude asked to use a tool and waits for Allow or Deny. */
    WaitingForPermission,

    /** The last turn finished; Claude waits for the next message. */
    Done,

    /** The turn failed or the process died. */
    Error,

    /** Restored after an app restart: no process yet. Opening it resumes the conversation (`--resume`). */
    Paused,
}
