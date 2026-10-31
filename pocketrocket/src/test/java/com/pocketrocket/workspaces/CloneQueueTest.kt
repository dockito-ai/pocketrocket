package com.pocketrocket.workspaces

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.transformWhile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CloneQueueTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val feeds = mutableMapOf<String, MutableSharedFlow<CloneProgress>>()
    // Each request reads a feed the test pushes progress into; its flow ends after a result.
    private val queue = CloneQueue(scope, { r ->
        feeds.getOrPut(r.name) { MutableSharedFlow(extraBufferCapacity = 16) }.transformWhile { emit(it); it is CloneProgress.Running }
    })

    private fun request(name: String) = CloneRequest("https://github.com/o/$name.git", "/p", name, null, null, "o/$name")

    @After fun tearDown() = scope.cancel()

    @Test fun oneSlotLocksASecondClone() {
        val first = queue.start(request("a"))
        assertNotNull(first)
        assertTrue(queue.full())
        assertNull(queue.start(request("b")))
        assertEquals(listOf("a"), queue.jobs.value.map { it.request.name })
    }

    @Test fun progressThenResultStaysUntilDismissed() {
        val job = queue.start(request("a"))!!
        feeds["a"]!!.tryEmit(CloneProgress.Running("Receiving objects", 40))
        assertEquals(CloneProgress.Running("Receiving objects", 40), queue.jobs.value.single().progress)
        feeds["a"]!!.tryEmit(CloneProgress.Done(File("/p/a"), "/p/a"))
        assertTrue(queue.jobs.value.single().progress is CloneProgress.Done)
        // The slot is free again while the result waits to be seen.
        assertTrue(!queue.full())
        queue.dismiss(job.id)
        assertTrue(queue.jobs.value.isEmpty())
    }

    @Test fun cancelFreesTheSlotAndLeavesNothing() {
        val job = queue.start(request("a"))!!
        queue.cancel(job.id)
        assertTrue(queue.jobs.value.isEmpty())
        assertNotNull(queue.start(request("b")))
    }

    @Test fun retryReplacesTheFailure() {
        val job = queue.start(request("a"))!!
        feeds["a"]!!.tryEmit(CloneProgress.Failed("could not resolve host"))
        assertTrue(queue.jobs.value.single().progress is CloneProgress.Failed)
        val again = queue.retry(job.id)
        assertNotNull(again)
        assertEquals(listOf(again!!.id), queue.jobs.value.map { it.id })
        assertTrue(queue.jobs.value.single().running)
    }

    @Test fun anErrorInTheRunIsAFailure() {
        val failing = CloneQueue(scope, { _ -> kotlinx.coroutines.flow.flow { error("git missing") } })
        failing.start(request("a"))
        assertEquals(CloneProgress.Failed("git missing"), failing.jobs.value.single().progress)
    }
}
