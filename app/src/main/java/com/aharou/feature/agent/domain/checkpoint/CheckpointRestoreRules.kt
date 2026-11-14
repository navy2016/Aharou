package com.aharou.feature.agent.domain.checkpoint

import com.aharou.feature.workspace.domain.sha256Fingerprint

internal fun checkpointCanRestore(changeType: String, current: ByteArray?, original: ByteArray?, writtenHash: String?): Boolean {
    if (changeType == "MODIFY" && current != null && original != null && current.contentEquals(original)) return true
    if (changeType == "CREATE" && current == null) return true
    if (current == null || writtenHash == null) return false
    return sha256Fingerprint(current).removePrefix("sha256:") == writtenHash
}
