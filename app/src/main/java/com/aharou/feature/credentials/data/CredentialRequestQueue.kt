package com.aharou.feature.credentials.data

import com.aharou.feature.credentials.data.CredentialRequestBridge.CredentialRequest

/** 队列变更与在途计数必须由调用方在同一互斥区提交。 */
internal class CredentialRequestQueue {
    private val pending = linkedMapOf<String, CredentialRequest>()
    private val completed = linkedSetOf<String>()
    private var answering: CredentialRequest? = null

    val current: CredentialRequest? get() = pending.values.firstOrNull()
    val requestIds: Set<String> get() = pending.keys.toSet()
    val requests: List<CredentialRequest> get() = pending.values.toList()

    fun enqueue(request: CredentialRequest): Boolean {
        if (request.requestId in pending || request.requestId in completed) return false
        pending[request.requestId] = request
        return true
    }

    fun claim(requestId: String): CredentialRequest? {
        val request = current ?: return null
        if (request.requestId != requestId || answering != null) return null
        answering = request
        return request
    }

    fun retry(request: CredentialRequest) {
        if (answering === request) answering = null
    }

    fun complete(request: CredentialRequest): Boolean {
        if (answering !== request) return false
        answering = null
        pending.remove(request.requestId)
        rememberCompleted(request.requestId)
        return true
    }

    fun retain(requestIds: Set<String>): Int {
        val missing = pending.keys.filter { it !in requestIds && it != answering?.requestId }
        missing.forEach { id ->
            pending.remove(id)
            rememberCompleted(id)
        }
        return missing.size
    }

    private fun rememberCompleted(requestId: String) {
        completed.add(requestId)
        if (completed.size > COMPLETED_CAP) completed.remove(completed.first())
    }

    private companion object {
        const val COMPLETED_CAP = 64
    }
}
