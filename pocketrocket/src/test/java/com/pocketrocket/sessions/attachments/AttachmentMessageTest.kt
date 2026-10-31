package com.pocketrocket.sessions.attachments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttachmentMessageTest {

    private val shot = Attachment("/home/.pocketrocket/attachments/s/1-shot.png", "shot.png", "image/png", 10)
    private val log = Attachment("/home/.pocketrocket/attachments/s/2-build.log", "build.log", "application/octet-stream", 20)

    @Test fun theNoteListsEveryPathAndReadsBack() {
        val note = AttachmentMessage.note(listOf(shot, log))
        assertEquals(listOf(shot.path, log.path), AttachmentMessage.pathsIn(note))
    }

    @Test fun typedTextIsNotANote() {
        assertNull(AttachmentMessage.pathsIn("The login button is cut off"))
    }

    @Test fun mimeComesFromTheExtension() {
        assertEquals("image/jpeg", Attachment.mimeFor("IMG_1.JPG"))
        assertEquals("application/pdf", Attachment.mimeFor("spec.pdf"))
        assertEquals("application/octet-stream", Attachment.mimeFor("build.log"))
    }
}
