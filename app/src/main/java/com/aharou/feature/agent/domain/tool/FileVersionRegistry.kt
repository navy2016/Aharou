package com.aharou.feature.agent.domain.tool

import com.aharou.feature.agent.domain.notification.AgentNotificationCenter
import com.aharou.feature.agent.domain.notification.AgentNotificationKind
import com.aharou.feature.agent.domain.notification.NotificationOutcome
import com.aharou.feature.agent.domain.notification.PendingNotification
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 读取基线只属于实际读取或写入的会话；其它会话只收到变化提醒。 */
@Singleton
class FileVersionRegistry @Inject constructor(
    private val notifications: AgentNotificationCenter
) {
    private data class ReadVersion(val fingerprint: String, val displayPath: String)
    private data class Writer(val name: String, val fingerprint: String?, val revision: Long)

    private val lock = Any()
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes = _changes.asSharedFlow()
    private val sessions = mutableMapOf<String, MutableMap<String, ReadVersion>>()
    private val writers = object : LinkedHashMap<String, Writer>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Writer>): Boolean =
            size > MAX_WRITERS
    }
    private var revision = 0L

    fun currentRevision(): Long = synchronized(lock) { revision }

    fun record(
        sessionId: String?,
        key: String,
        fingerprint: String,
        displayPath: String = key,
        readRevision: Long? = null
    ) {
        val sid = sessionId?.trim().orEmpty()
        if (sid.isEmpty() || key.isEmpty()) return
        synchronized(lock) {
            sessions.getOrPut(sid) { newSessionMap(sid) }[key] = ReadVersion(fingerprint, displayPath)
            notifications.clearFileChange(sid, key)
            // 读到旧 inode 后才登记时，不能漏掉读取期间已提交的变化。
            val writer = writers[key]
            if (readRevision != null && writer != null && writer.revision > readRevision && writer.fingerprint != fingerprint) {
                notifyChanged(sid, key, displayPath, writer.name)
            }
        }
    }

    fun get(sessionId: String?, key: String): String? {
        val sid = sessionId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return synchronized(lock) { sessions[sid]?.get(key)?.fingerprint }
    }

    fun recordWrite(sessionId: String?, key: String, fingerprint: String, displayPath: String) {
        if (key.isEmpty()) return
        val sid = sessionId?.trim()?.takeIf { it.isNotEmpty() }
        synchronized(lock) {
            if (sid != null) {
                sessions.getOrPut(sid) { newSessionMap(sid) }[key] = ReadVersion(fingerprint, displayPath)
                notifications.clearFileChange(sid, key)
            }
            recordChange(key, fingerprint, sid ?: "未知会话", sid)
        }
    }

    fun recordExternal(key: String, fingerprint: String) {
        if (key.isEmpty()) return
        synchronized(lock) { recordChange(key, fingerprint, "用户/编辑器", null) }
    }

    private fun recordChange(key: String, fingerprint: String, writer: String, sourceSessionId: String?) {
        writers[key] = Writer(writer, fingerprint, ++revision)
        sessions.forEach { (sid, entries) ->
            if (sid != sourceSessionId) {
                entries[key]?.let { read ->
                    if (read.fingerprint != fingerprint) notifyChanged(sid, key, read.displayPath, writer)
                    else notifications.clearFileChange(sid, key)
                }
            }
        }
        _changes.tryEmit(key)
    }

    private fun notifyChanged(sessionId: String, key: String, displayPath: String, writer: String) {
        notifications.enqueue(sessionId, PendingNotification(
            kind = AgentNotificationKind.FILE_CHANGE,
            sourceId = key,
            title = displayPath,
            outcome = NotificationOutcome.COMPLETED,
            detail = "最后写入来源：$writer。你的读取基线未更新；请重新读取，并检查依赖它的调用方、实现、配置和测试。"
        ))
    }

    fun recordWriter(key: String, writer: String) {
        if (key.isEmpty() || writer.isBlank()) return
        synchronized(lock) { writers[key] = Writer(writer, null, ++revision) }
    }

    fun lastWriter(key: String): String? = synchronized(lock) { writers[key]?.name }

    fun forget(sessionId: String, key: String) {
        synchronized(lock) {
            sessions[sessionId]?.remove(key)
            notifications.clearFileChange(sessionId, key)
        }
    }

    fun clearSession(sessionId: String) {
        synchronized(lock) {
            sessions.remove(sessionId)
            notifications.clearFileChanges(sessionId)
        }
    }

    fun trackedSessionCount(): Int = synchronized(lock) { sessions.size }

    fun trackedEntryCount(sessionId: String): Int = synchronized(lock) { sessions[sessionId]?.size ?: 0 }

    private fun newSessionMap(sessionId: String): MutableMap<String, ReadVersion> =
        object : LinkedHashMap<String, ReadVersion>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ReadVersion>): Boolean {
                if (size <= MAX_ENTRIES_PER_SESSION) return false
                notifications.clearFileChange(sessionId, eldest.key)
                return true
            }
        }

    private companion object {
        const val MAX_ENTRIES_PER_SESSION = 512
        const val MAX_WRITERS = 1024
    }
}
