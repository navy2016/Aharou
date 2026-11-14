package com.aharou.feature.credentials.data

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.core.util.writeTextSafely
import com.aharou.core.watch.FileChangeHub
import com.aharou.feature.agent.domain.container.LinuxContainerEngine
import com.aharou.feature.credentials.domain.model.GitCredential
import com.aharou.feature.credentials.domain.repository.CredentialRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自定义 git credential helper（容器内 /root/.aicode/git-credential-aicode）与 app 之间的文件 IPC 桥。
 *
 * 容器内 git 缺凭据时 helper 写 `cred-req-<id>` 到 /root/.aicode（经 PRoot -b 绑定即宿主 filesDir/aicode 同一
 * inode）。本桥订阅 [FileChangeHub] 的 aicode 目录事件捕获请求 → 暴露 [request] StateFlow 供全局 Compose 弹窗 →
 * 用户回填后 [respond] 写 `cred-resp-<id>` 明文 KV，helper 轮询取走喂回 git，git 自动续跑。三端（UI / AI Bash /
 * 交互终端）git 缺凭据都走这条统一链，无需逐命令路径适配。
 *
 * 临时文件清理：请求文件由 helper 在拿到响应后自删（helper 知道自己何时结束），响应文件也由 helper 删它读到的；
 * app 只写响应、不删它没写的文件，避免与 helper 抢删。详见 helper 脚本内 rm 逻辑。
 *
 * 在途计数：收到请求 [LinuxContainerEngine.incPromptInFlight]、写回响应 [decPromptInFlight]，配合
 * [LinuxContainerEngine.launchKillWatchdog] 在 helper 在途时暂停超时杀进程——用户离开几分钟填凭据，git 命令
 * 不会被 120s watchdog 强杀。
 *
 * 目录监听交给中心服务 [FileChangeHub]（inotify 跨 PRoot -b 对 app filesDir 基本可靠，但个别机型 inotify 在
 * 绑定目录可能失效），本桥另起一个低频兜底轮询协程（[fallbackPollLoop]）扫 `cred-req-*` 未处理项；两者按
 * requestId 去重防双处理。
 *
 * 生命周期：@Singleton = App 级，借 [com.aharou.AIEditorApp] 调 [start]。弹窗在任意页面（终端页/聊天页/Git 页）
 * 都能弹，不限于 Git 页。
 */
@Singleton
class CredentialRequestBridge @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val credentialRepository: CredentialRepository,
    private val containerEngine: LinuxContainerEngine,
    private val fileChangeHub: FileChangeHub
) {
    private companion object {
        const val TAG = "CredentialRequestBridge"
        const val REQ_PREFIX = "cred-req-"
        const val RESP_PREFIX = "cred-resp-"
        /** 兜底轮询间隔：中心服务的 inotify 不灵时也能在 ~1s 内捕获请求。 */
        const val FALLBACK_POLL_MS = 1000L
        /** helper 等待约 25 分钟、命令 watchdog 上限 30 分钟；保留期间内的请求供重启后继续处理。 */
        const val STALE_FILE_AGE_MS = 30L * 60L * 1000L
    }

    /** 当前待弹凭据请求（host 来自 git credential 协议的 host 字段）。null 表示无待处理。 */
    data class CredentialRequest(
        val requestId: String,
        val host: String
    )

    private val _request = MutableStateFlow<CredentialRequest?>(null)
    val request: StateFlow<CredentialRequest?> = _request.asStateFlow()

    private val queue = CredentialRequestQueue()
    private val requestMutex = Mutex()
    private val started = AtomicBoolean()
    private lateinit var scope: CoroutineScope
    private lateinit var aharouDir: File

    /**
     * 启动监听：订阅中心服务的 aharou 目录事件捕获请求，并起兜底轮询协程。幂等可重复调。
     *
     * 目录必须是 filesDir/aharou（容器内的 /root/.aharou）：helper 写在那儿，
     * 而 PRoot 绑定的是同一个宿主目录，写旧名 filesDir/aicode 会永远收不到请求。
     */
    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        this.scope = scope
        val dir = File(context.filesDir, "aharou").apply { mkdirs() }
        aharouDir = dir
        scope.launch {
            cleanupStale(dir)
            launch {
                try {
                    fileChangeHub.watchAicode().collect { batch ->
                        for (change in batch.changes) {
                            val name = File(change.hostPath).name
                            if (!name.startsWith(REQ_PREFIX) || name.endsWith(".tmp")) continue
                            handleRequest(File(dir, name), name)
                        }
                        reclaimMissingRequests(dir)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    FileLogger.w(TAG, "凭据目录监听失败，继续轮询请求", error)
                }
            }
            FileLogger.i(TAG, "已订阅 aicode 目录事件，监听 ${dir.absolutePath}")
            fallbackPollLoop(dir)
        }
    }

    private suspend fun fallbackPollLoop(dir: File) {
        while (true) {
            runCatching {
                dir.listFiles { f -> f.name.startsWith(REQ_PREFIX) && !f.name.endsWith(".tmp") }
                    ?.forEach { f -> handleRequest(f, f.name) }
                reclaimMissingRequests(dir)
            }.onFailure { FileLogger.w(TAG, "扫描凭据请求失败", it) }
            delay(FALLBACK_POLL_MS)
        }
    }

    private fun cleanupStale(dir: File) {
        val now = System.currentTimeMillis()
        runCatching {
            dir.listFiles { f -> f.name.startsWith(REQ_PREFIX) || f.name.startsWith(RESP_PREFIX) }
                ?.filter { now - it.lastModified() > STALE_FILE_AGE_MS }
                ?.forEach { it.delete() }
        }
    }

    private suspend fun reclaimMissingRequests(dir: File) = requestMutex.withLock {
        val existing = queue.requestIds.filter { File(dir, REQ_PREFIX + it).exists() }.toSet()
        repeat(queue.retain(existing)) { containerEngine.decPromptInFlight() }
        _request.value = queue.current
    }

    private suspend fun handleRequest(file: File, name: String) {
        val requestId = name.removePrefix(REQ_PREFIX)
        if (!file.isFile || file.length() == 0L) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        val host = text.lineSequence()
            .firstOrNull { it.startsWith("host=") }
            ?.removePrefix("host=")
            ?.trim()
            ?.lowercase()
            ?: ""
        if (host.isBlank()) return
        requestMutex.withLock {
            if (!file.exists() || File(file.parentFile, RESP_PREFIX + requestId).exists()) return@withLock
            if (!queue.enqueue(CredentialRequest(requestId, host))) return@withLock
            containerEngine.incPromptInFlight()
            _request.value = queue.current
            FileLogger.i(TAG, "收到凭据请求 host=$host id=$requestId")
        }
    }

    fun respond(requestId: String, username: String, token: String) {
        scope.launch {
            val request = requestMutex.withLock { queue.claim(requestId) } ?: return@launch
            if (!writeRespAtomically(aharouDir, requestId, "username=$username\npassword=$token\n")) {
                requestMutex.withLock { queue.retry(request) }
                return@launch
            }
            runCatching {
                credentialRepository.save(
                    GitCredential(id = request.host, host = request.host, username = username.trim(), token = token)
                )
            }.onFailure { FileLogger.e(TAG, "保存回填凭据失败", it) }
            completeRequest(request)
            FileLogger.i(TAG, "已回填凭据 id=$requestId host=${request.host}")
        }
    }

    fun cancel(requestId: String) {
        scope.launch {
            val request = requestMutex.withLock { queue.claim(requestId) } ?: return@launch
            if (!writeRespAtomically(aharouDir, requestId, "cancel=1\n")) {
                requestMutex.withLock { queue.retry(request) }
                return@launch
            }
            completeRequest(request)
            FileLogger.i(TAG, "用户取消凭据弹窗 id=$requestId")
        }
    }

    private suspend fun completeRequest(request: CredentialRequest) = requestMutex.withLock {
        if (queue.complete(request)) containerEngine.decPromptInFlight()
        _request.value = queue.current
    }

    private fun writeRespAtomically(dir: File, requestId: String, content: String): Boolean {
        val target = File(dir, RESP_PREFIX + requestId)
        val tmp = File(dir, "$RESP_PREFIX${requestId}.tmp")
        return try {
            runCatching {
                check(tmp.writeTextSafely(content, TAG)) { "无法写入凭据响应临时文件" }
                check(tmp.renameTo(target)) { "无法发布凭据响应文件" }
            }.onFailure { FileLogger.e(TAG, "写凭据响应文件失败", it) }.isSuccess
        } finally {
            tmp.delete()
        }
    }
}
