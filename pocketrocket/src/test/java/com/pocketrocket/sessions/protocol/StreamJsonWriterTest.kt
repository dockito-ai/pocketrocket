package com.pocketrocket.sessions.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamJsonWriterTest {

    @Test fun sendNowIsAnInterruptMarkedSendNow() {
        val line = JSONObject(StreamJsonWriter.sendNow("r1"))
        assertEquals("control_request", line.getString("type"))
        assertEquals("r1", line.getString("request_id"))
        val request = line.getJSONObject("request")
        assertEquals("interrupt", request.getString("subtype"))
        assertTrue(request.getBoolean("send_now"))
    }

    @Test fun stopIsNotSendNow() {
        assertFalse(JSONObject(StreamJsonWriter.interrupt("r1")).getJSONObject("request").has("send_now"))
    }
}
