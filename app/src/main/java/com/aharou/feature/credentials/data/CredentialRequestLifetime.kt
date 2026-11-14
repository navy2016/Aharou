package com.aharou.feature.credentials.data

import com.aharou.core.util.ProcessIdentity
import com.aharou.feature.credentials.data.CredentialRequestBridge.CredentialRequest
import java.io.File
import javax.inject.Inject

class CredentialRequestLifetime @Inject constructor() {
    private val bootId: String? by lazy { ProcessIdentity.readBootId() }

    fun isActive(file: File, request: CredentialRequest, now: Long = System.currentTimeMillis()): Boolean {
        if (!file.isFile || now - file.lastModified() > MAX_AGE_MS) return false
        if (request.bootId != null && bootId != null && request.bootId != bootId) return false
        val pid = request.pid ?: return true
        return when (ProcessIdentity.state(pid, request.startTimeTicks)) {
            ProcessIdentity.State.MISSING, ProcessIdentity.State.REUSED, ProcessIdentity.State.MATCHED_ZOMBIE -> false
            ProcessIdentity.State.MATCHED_ACTIVE, ProcessIdentity.State.UNREADABLE -> true
        }
    }

    companion object {
        const val MAX_AGE_MS = 30L * 60L * 1000L
    }
}
