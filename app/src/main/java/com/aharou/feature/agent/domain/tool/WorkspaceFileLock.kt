package com.aharou.feature.agent.domain.tool

import com.aharou.core.util.FileLogger
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WorkspaceFileLock"

/**
 * 工作区路径级互斥：把「同一文件的读-改-写」在多会话之间串行化。
 *
 * ### 为什么需要它
 *
 * `editFile` 是典型的 read-modify-write：读原文 → 内存里改 → 整篇写回。两个会话同时这么做时，
 * 后写的一方会覆盖前者的结果（lost update），且**双方都以为自己成功**。`StatefulAgentWorkflow`
 * 里原有的 `pathLocks` 只在**一次 tool batch 的 coroutineScope 内**生效，跨批次、跨会话、跨子代理
 * 全部失效——而子代理各自是独立 session，恰恰是最常见的并发来源。
 *
 * ### 锁键的选取
 *
 * 键来自 [com.aharou.feature.workspace.domain.FileAccessProvider.resourceKey]：显示路径在不同
 * 工作区可能相同，而符号链接的显示路径不同却可能访问同一文件，不能用于判定资源身份。
 *
 * ### 边界（必须知道）
 *
 * 这是**进程内**的锁，无法拦截：
 * - 容器内 shell 命令写文件（`sed -i`、`cat >`）——它们不经本进程；
 * - 远端服务器上其他进程的写入；
 * - 应用外编辑器或其它进程的写入。
 *
 * 这些场景由 [FileVersionRegistry] 的**写前版本校验**事后发现（它校验的是内容而非锁，因此对
 * 「锁管不到的写入」同样有效）。两者是互补关系，不是二选一。
 *
 * ### 实现要点
 *
 * - **多键排序获取**：一次要锁多个路径时按字符串序依次获取，避免两个会话以相反顺序请求同一组文件而死锁。
 * - **等待者计数回收**：没有等待者时把该路径的 [Mutex] 从表中移除，否则长会话会为每个碰过的路径
 *   永久留一条记录（这类实现最常见的泄漏点）。
 * - 不做超时/租约：进程内锁的持有者一旦取消或异常，`withLock` 的 `finally` 一定会释放，不存在
 *   「孤儿锁」；加租约反而会引入「锁还在但被别人抢走」的新竞态。
 */
@Singleton
class WorkspaceFileLock @Inject constructor() {

    private class LockEntry(val mutex: Mutex = Mutex(), var users: Int = 0)

    private val registryLock = Any()
    private val locks = mutableMapOf<String, LockEntry>()

    /**
     * 在指定逻辑路径的互斥保护下执行 [block]。
     *
     * @param keys 资源身份（[com.aharou.feature.workspace.domain.FileAccessProvider.resourceKey] 的结果）。
     *             null/空白会被忽略，因此调用方可以直接传可能为 null 的路径而不必先判空。
     *             空集合等价于不加锁。
     */
    suspend fun <T> withLock(keys: Collection<String?>, block: suspend () -> T): T {
        val ordered = keys.asSequence()
            .mapNotNull { it?.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .toList()

        if (ordered.isEmpty()) return block()

        val acquired = ArrayList<LockEntry>(ordered.size)
        try {
            for (key in ordered) {
                val entry = acquireRef(key)
                try {
                    entry.mutex.lock()
                } catch (error: Throwable) {
                    releaseRef(key, entry)
                    throw error
                }
                acquired.add(entry)
            }
            logIfSuspicious()
            return block()
        } finally {
            for (i in acquired.indices.reversed()) {
                acquired[i].mutex.unlock()
                releaseRef(ordered[i], acquired[i])
            }
        }
    }

    /** 单键便捷重载。 */
    suspend fun <T> withLock(key: String?, block: suspend () -> T): T = withLock(listOf(key), block)

    /** 当前活跃的锁条目数，供测试与诊断使用。 */
    fun trackedKeyCount(): Int = synchronized(registryLock) { locks.size }

    private fun acquireRef(key: String): LockEntry = synchronized(registryLock) {
        locks.getOrPut(key) { LockEntry() }.also { it.users++ }
    }

    private fun releaseRef(key: String, entry: LockEntry) {
        synchronized(registryLock) {
            entry.users--
            if (entry.users == 0) locks.remove(key)
        }
    }

    /**
     * 条目数异常增长时留一条日志。正常情况下条目数应约等于"同时被操作的文件数"，若逼近
     * [SUSPICIOUS_KEY_COUNT] 说明要么确实在批量操作大量文件，要么回收逻辑出了问题——这条日志是
     * 排查后者的唯一线索，因此保留但用 `w` 级别避免刷屏。
     */
    private fun logIfSuspicious() {
        val size = trackedKeyCount()
        if (size >= SUSPICIOUS_KEY_COUNT) {
            FileLogger.w(TAG, "路径锁条目数偏高: $size（等待者计数回收可能失效，请检查 releaseRef）")
        }
    }

    private companion object {
        /** 条目数达到该阈值时打一条诊断日志。 */
        const val SUSPICIOUS_KEY_COUNT = 2048
    }
}
