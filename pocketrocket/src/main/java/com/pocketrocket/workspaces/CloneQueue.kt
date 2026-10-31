package com.pocketrocket.workspaces

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One clone as asked for: [url] into [parent]/[name] as GitHub account [accountId] (null: the folder's),
 * on [branch] (null: the remote's default). [label] is what people read, "owner/repo".
 */
data class CloneRequest(
    val url: String,
    val parent: String,
    val name: String,
    val accountId: String?,
    val branch: String?,
    val label: String,
)

/** A clone the queue knows about: running, or finished and not yet seen ([CloneProgress.Done] / [CloneProgress.Failed]). */
data class CloneJob(val id: Int, val request: CloneRequest, val progress: CloneProgress) {
    val running: Boolean get() = progress is CloneProgress.Running
}

/**
 * Clones in slots: at most [capacity] run at once (one for now, decision 42), and a request beyond that is
 * refused rather than queued, so the screen can say why. Finished jobs stay in [jobs] until [dismiss]ed, so
 * whoever comes back (the screen, the notification) finds the result. [run] does the clone itself; its
 * collection is cancelled by [cancel], which kills git.
 */
class CloneQueue(
    private val scope: CoroutineScope,
    private val run: (CloneRequest) -> Flow<CloneProgress>,
    val capacity: Int = 1,
) {
    private val _jobs = MutableStateFlow<List<CloneJob>>(emptyList())
    val jobs: StateFlow<List<CloneJob>> = _jobs.asStateFlow()
    private val workers = mutableMapOf<Int, Job>()
    private var nextId = 1

    fun running(): List<CloneJob> = _jobs.value.filter { it.running }

    /** True while every slot is busy: the clone screen is locked then. */
    fun full(): Boolean = running().size >= capacity

    /** Starts [request] in a free slot and returns its job, or null when every slot is busy. */
    @Synchronized
    fun start(request: CloneRequest): CloneJob? {
        if (full()) return null
        val job = CloneJob(nextId++, request, CloneProgress.Running("Starting", null))
        // A new clone of the same folder replaces the old result (a Retry, or cloning it again).
        _jobs.update { list -> list.filterNot { !it.running && it.request.parent == request.parent && it.request.name == request.name } + job }
        workers[job.id] = scope.launch {
            try {
                run(request).collect { p -> set(job.id, p) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                set(job.id, CloneProgress.Failed(e.message ?: e.javaClass.simpleName))
            } finally {
                synchronized(this@CloneQueue) { workers.remove(job.id) }
                // Ended without a result (cancelled, or the flow just stopped): nothing to show.
                _jobs.update { list -> list.filterNot { it.id == job.id && it.running } }
            }
        }
        return job
    }

    /** Stops a running clone (git is killed and cleans up its folder); the job disappears. */
    @Synchronized
    fun cancel(id: Int) {
        workers.remove(id)?.cancel()
        _jobs.update { list -> list.filterNot { it.id == id } }
    }

    /** Runs a failed clone again with the same request. Null when a slot isn't free. */
    fun retry(id: Int): CloneJob? {
        val old = _jobs.value.firstOrNull { it.id == id && it.progress is CloneProgress.Failed } ?: return null
        return start(old.request)
    }

    /** Forgets a finished job once its result has been seen. */
    fun dismiss(id: Int) = _jobs.update { list -> list.filterNot { it.id == id && !it.running } }

    private fun set(id: Int, progress: CloneProgress) =
        _jobs.update { list -> list.map { if (it.id == id && it.running) it.copy(progress = progress) else it } }
}
