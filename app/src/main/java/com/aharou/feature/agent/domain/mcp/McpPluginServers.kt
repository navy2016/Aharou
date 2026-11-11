package com.aharou.feature.agent.domain.mcp

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.plugin.data.PluginRecordStore
import com.aharou.feature.plugin.domain.model.PluginManifest
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * 把已安装插件里声明了 `mcpEntry` 的那些派生成 MCP server，让插件里的工具直接进 Agent 的工具表。
 *
 * 安装记录决定哪些插件可用，清单提供 MCP 声明；派生项**不落盘**，无需维护第二份配置。
 * 插件记录或清单被删后，下一次重载就自然消失。
 *
 * 直接读安装记录和清单而不经 PluginManager：只为了绕开「插件管理器 ← MCP 配置」的反向依赖。
 * 目录布局与 [com.aharou.feature.plugin.domain.PluginManager] 保持一致，改那边要同步这里。
 */
@Singleton
class McpPluginServers @Inject constructor(
    private val containerInstaller: ContainerInstaller,
    private val recordStore: PluginRecordStore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun servers(): List<McpServerConfig> = withContext(Dispatchers.IO) {
        val root = File(containerInstaller.aharouDir, PLUGINS_DIR)
        recordStore.all().values.mapNotNull { record ->
            val id = record.id
            val dir = File(root, id)
            val manifest = readManifest(dir) ?: return@mapNotNull null
            if (manifest.id != id || dir.name != id) return@mapNotNull null
            val entry = manifest.mcpEntry.trim()
            // 没声明入口的插件只给用户手动用，保持原来的行为
            if (entry.isEmpty()) return@mapNotNull null
            McpServerConfig(
                name = serverName(id),
                command = if (entry.startsWith("/")) entry else "$CONTAINER_ROOT/$id/$entry",
                args = manifest.mcpArgs,
                env = manifest.environment,
                // 由 McpManager 保持 stdio 连接，跳过空闲回收；HTTP 服务巡检不管理此进程。
                keepAlive = true,
            )
        }
    }

    private fun readManifest(dir: File): PluginManifest? {
        val file = File(dir, MANIFEST_FILE)
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString<PluginManifest>(file.readText(Charsets.UTF_8))
        }.onFailure {
            FileLogger.w(TAG, "读取插件清单失败：${dir.name}", it)
        }.getOrNull()
    }

    /**
     * server 名会拼进工具名（`mcp__名称__工具名`），只接受 ASCII 字母、数字、下划线与连字符。
     * 插件 id 合规就原样用，否则净化后补 id 哈希，免得 `a.b` 与 `a-b` 撞成同一个命名空间。
     */
    private fun serverName(id: String): String {
        val lowered = id.lowercase()
        if (SAFE_ID.matches(lowered)) return "$NAME_PREFIX$lowered"
        val sanitized = lowered.replace(Regex("[^a-z0-9_-]"), "-").take(28).trim('-').ifEmpty { "p" }
        val suffix = (id.hashCode() and 0xFFFF).toString(16).padStart(4, '0')
        return "$NAME_PREFIX$sanitized-$suffix"
    }

    private companion object {
        const val TAG = "McpPluginServers"
        const val MANIFEST_FILE = "manifest.json"
        const val PLUGINS_DIR = "plugins"
        const val NAME_PREFIX = "plugin-"

        /** 宿主 `filesDir/aharou/plugins/<id>` 经 PRoot 绑定成这个容器路径。 */
        const val CONTAINER_ROOT = "/root/.aharou/plugins"
        val SAFE_ID = Regex("[a-z0-9_-]{1,32}")
    }
}
