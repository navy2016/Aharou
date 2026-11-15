package com.aharou.feature.agent.presentation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin

internal class SessionTurnHandoff(
    private val jobs: MutableMap<String, Job>,
    private val stopping: MutableMap<String, CompletableDeferred<Unit>>,
) {
    private val restarting = mutableMapOf<String, CompletableDeferred<Unit>>()

    suspend fun <T> withRestart(sessionId: String, block: suspend (Deferred<Unit>) -> T): T? {
        if (sessionId in restarting) return null
        val ticket = CompletableDeferred<Unit>()
        restarting[sessionId] = ticket
        return try {
            block(ticket)
        } finally {
            if (restarting[sessionId] === ticket) restarting.remove(sessionId)
            ticket.complete(Unit)
        }
    }

    suspend fun awaitIdle(sessionId: String, currentJob: Job, ticket: Deferred<Unit>? = null) {
        while (true) {
            restarting[sessionId]?.takeUnless { it === ticket }?.await()
            stopping[sessionId]?.await()
            if (restarting[sessionId]?.let { it !== ticket && !it.isCompleted } == true || stopping[sessionId]?.isCompleted == false) continue
            val previous = jobs[sessionId]
            if (previous == null || previous === currentJob || previous.isCompleted) return
            previous.join()
        }
    }

    suspend fun settleForRestart(sessionId: String): Boolean {
        val previous = jobs[sessionId]
        var stopped = stopping[sessionId]
        if (stopped != null) {
            while (stopped != null) {
                stopped.await()
                val next = stopping[sessionId]
                if (next === stopped) break
                stopped = next
            }
            previous?.join()
        } else if (previous != null && !previous.isCompleted) {
            if (previous.isActive) previous.cancelAndJoin() else previous.join()
        }
        return jobs[sessionId]?.let { it === previous } != false
    }
}
