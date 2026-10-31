package com.pocketrocket.usage

/** How much of something the whole phone has and has used, to set the app's figure against. */
data class PhoneShare(val totalBytes: Long, val usedBytes: Long) {

    /** How far along a bar the phone's use reaches, 0 to 1, or 0 if there's no total. */
    fun usedFraction(): Float = fraction(usedBytes)

    /** How far along a bar the app's [bytes] reach. Never past the phone's use, which it's a part of. */
    fun partFraction(bytes: Long): Float = fraction(bytes.coerceAtMost(usedBytes))

    private fun fraction(bytes: Long): Float = if (totalBytes <= 0) 0f else (bytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}
