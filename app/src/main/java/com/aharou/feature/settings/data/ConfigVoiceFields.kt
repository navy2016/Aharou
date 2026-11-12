package com.aharou.feature.settings.data

import android.content.Context
import android.net.Uri
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.core.config.fields.DataStoreBoolField
import com.aharou.core.config.fields.DataStoreDoubleField
import com.aharou.core.config.fields.DataStoreIntField
import com.aharou.core.config.fields.DataStoreStrField
import com.aharou.feature.settings.data.repository.AssistantOverlaySettingsRepository
import com.aharou.feature.settings.data.repository.VoiceSttSettingsRepository
import com.aharou.feature.settings.data.repository.VoiceTtsSettingsRepository
import com.aharou.feature.settings.data.repository.VoiceWakeSettingsRepository
import com.aharou.feature.voice.assistant.PowerKeyWatcherService
import com.aharou.feature.voice.call.VoiceCallService
import com.aharou.feature.voice.data.CallBackgroundRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/**
 * 语音合成与朗读偏好 → 配置通道。
 *
 * 语音识别（STT）的 provider/model 在 [com.aharou.feature.settings.data.repository.VoiceSttSettingsRepository]，
 * 两者在仓库里共用一个写入方法（`setSttModel(providerId, model)`），所以每个字段的 setter 都要把
 * 另一个的当前值带上，避免改一个把另一个清空。
 *
 * 三个 TTS 字段在仓库里也是同一个写入方法（`setTtsModel(providerId, model, voice)`），
 * 同理每个 setter 都要把另外两个的当前值带上。
 *
 * 两处开关除了写值还要**启停对应服务**（`voice.wake_enabled` → VoiceCallService，
 * `voice.assistant_power_key` → PowerKeyWatcherService）。原来只有设置页的点击路径会启停，
 * config 通道只写开关，结果关掉后麦克风仍常驻收音、通知栏还挂着「唤醒已开启」。
 */
@Singleton
class ConfigVoiceFields @Inject constructor(
    private val ttsSettings: VoiceTtsSettingsRepository,
    private val sttSettings: VoiceSttSettingsRepository,
    private val wakeSettings: VoiceWakeSettingsRepository,
    private val overlaySettings: AssistantOverlaySettingsRepository,
    private val callBackground: CallBackgroundRepository,
    @param:ApplicationContext private val context: Context,
) {

    fun registerInto(registry: ConfigRegistry) {
        registry.register(
            DataStoreStrField(
                path = "voice.tts_provider",
                displayName = "语音合成供应商",
                description = "朗读与语音通话使用的 TTS 供应商 id（取值见 providers 集合）。留空则回退到手机自带的语音引擎。",
                flow = ttsSettings.providerIdFlow,
                setter = { id ->
                    ttsSettings.setTtsModel(id, ttsSettings.getTtsModel(), ttsSettings.getVoice())
                },
            ),
        )

        registry.register(
            DataStoreStrField(
                path = "voice.tts_model",
                displayName = "语音合成模型",
                description = "TTS 模型名。需与 voice.tts_provider 配套设置，只设其一不会生效。",
                flow = ttsSettings.modelFlow,
                setter = { model ->
                    ttsSettings.setTtsModel(ttsSettings.getTtsProviderId(), model, ttsSettings.getVoice())
                },
            ),
        )

        registry.register(
            DataStoreStrField(
                path = "voice.tts_voice",
                displayName = "语音合成音色",
                description = "AI 音色，留空用服务端默认音色；填 local:<id> 则用本机克隆的音色（id 见设置里的音色弹层）。",
                flow = ttsSettings.voiceFlow,
                setter = { ttsSettings.setVoice(it) },
            ),
        )

        registry.register(
            DataStoreStrField(
                path = "voice.tts_voice_user",
                displayName = "用户音色",
                description = "对话模式下用户自己那条语音条用的音色；留空则与 voice.tts_voice 共用，格式同它（留空用服务端默认、local:<id> 用本机克隆音色）。",
                flow = ttsSettings.userVoiceFlow,
                setter = { ttsSettings.setUserVoice(it) },
            ),
        )

        registry.register(
            DataStoreBoolField(
                path = "voice.chat_mode",
                displayName = "对话模式",
                description = "开启后聊天页双方消息都以语音条呈现，点条展开看文字；关闭则是普通文字气泡。",
                flow = ttsSettings.chatVoiceModeFlow,
                setter = { ttsSettings.setChatVoiceMode(it) },
            ),
        )

        registry.register(
            DataStoreBoolField(
                path = "voice.auto_read",
                displayName = "自动朗读",
                description = "开启后 AI 每条回复自动念出来，边生成边念。关闭时手动点气泡上的喇叭按钮仍可朗读。",
                flow = ttsSettings.autoReadAloudFlow,
                setter = { ttsSettings.setAutoReadAloud(it) },
            ),
        )

        registry.register(
            DataStoreStrField(
                path = "voice.stt_provider",
                displayName = "语音识别供应商",
                description = "云端识别供应商 id（取值见 providers 集合）。与 voice.stt_model 配齐后，长按收尾和通话整句会上传录音；留空或请求失败则使用另行下载的本机离线模型。",
                flow = sttSettings.providerIdFlow,
                setter = { id -> sttSettings.setSttModel(id, sttSettings.getSttModel()) },
            ),
        )

        registry.register(
            DataStoreStrField(
                path = "voice.stt_model",
                displayName = "语音识别模型",
                description = "OpenAI 兼容音频转写模型名。与 voice.stt_provider 配套后会上传整句录音；配置不完整、缺少密钥或请求失败时回退本机识别。离线模型需另行下载。",
                flow = sttSettings.modelFlow,
                setter = { model -> sttSettings.setSttModel(sttSettings.getSttProviderId(), model) },
            ),
        )

        registry.register(
            ClosureField(
                path = "voice.call_background_image",
                displayName = "通话背景图",
                description = "导入通话页专用背景图片的 content:// 或 file:// URI；留空清除背景。读回已保存的图片 URI，替换或清除图片不可回滚。",
                valueSchema = ConfigSchema.Str(),
                revertable = false,
                reader = {
                    val name = runBlocking { callBackground.config.first() }.imagePath
                    ConfigValue.Str(name?.let { Uri.fromFile(java.io.File(callBackground.dir(), it)).toString() }.orEmpty())
                },
                writer = { value ->
                    val uri = (value as ConfigValue.Str).value.trim()
                    runBlocking {
                        if (uri.isBlank()) {
                            callBackground.clearImage()
                        } else {
                            val source = Uri.parse(uri)
                            if (source.scheme !in setOf("content", "file")) {
                                throw ConfigError.InvalidValue("通话背景需为 content:// 或 file:// URI")
                            }
                            val name = callBackground.saveImage(source)
                                ?: throw ConfigError.InvalidValue("无法导入通话背景图片")
                            callBackground.update(callBackground.config.first().copy(imagePath = name))
                        }
                    }
                },
            ),
        )
        registry.register(
            DataStoreIntField(
                path = "voice.call_background_blur_dp",
                displayName = "通话背景模糊",
                description = "通话背景模糊半径，0~40 dp，不影响聊天背景。",
                flow = callBackground.config.map { it.blurDp },
                setter = { callBackground.update(callBackground.config.first().copy(blurDp = it)) },
                minValue = 0,
                maxValue = 40,
            ),
        )
        registry.register(
            DataStoreDoubleField(
                path = "voice.call_background_dim",
                displayName = "通话背景压暗",
                description = "通话背景压暗程度，0.0~0.9，不影响聊天背景。",
                flow = callBackground.config.map { it.dim },
                setter = { callBackground.update(callBackground.config.first().copy(dim = it)) },
                minValue = 0.0,
                maxValue = 0.9,
            ),
        )

        registry.register(
            ClosureField(
                path = "voice.wake_enabled",
                displayName = "语音唤醒",
                description = "常驻监听麦克风、用唤醒词唤起语音输入。默认关闭。",
                valueSchema = ConfigSchema.Bool,
                // 写入要等 DataStore 落盘，而 writer 是同步签名，与 DataStoreBoolField 一样用 runBlocking 桥接。
                reader = { ConfigValue.Bool(runBlocking { wakeSettings.enabledFlow.first() }) },
                writer = { v ->
                    val enabled = (v as? ConfigValue.Bool)?.value
                        ?: throw ConfigError.TypeMismatch("bool")
                    runBlocking { wakeSettings.setEnabled(enabled) }
                    if (enabled) VoiceCallService.start(context, wakeMode = true) else VoiceCallService.stop(context)
                },
            ),
        )
        registry.register(
            DataStoreBoolField(
                path = "voice.assistant_screen_text",
                displayName = "助手浮层附带屏幕文本",
                description = "系统助手浮层唤起时，把当前屏幕上的可见文字当作上下文发给模型。关掉后仍能对话，只是模型不知道你正看着什么。",
                flow = overlaySettings.screenTextFlow,
                setter = { overlaySettings.setScreenText(it) },
            ),
        )

        registry.register(
            DataStoreBoolField(
                path = "voice.assistant_screenshot",
                displayName = "助手浮层附带截图",
                description = "把当前屏幕截图当作图片附件发给模型，能理解画面内容。比文本更耗流量，且画面内容会整个离开设备，默认关闭。",
                flow = overlaySettings.screenshotFlow,
                setter = { overlaySettings.setScreenshot(it) },
            ),
        )
        registry.register(
            ClosureField(
                path = "voice.assistant_power_key",
                displayName = "长按电源键唤起助手",
                description = "靠 Shizuku 监听电源键，按住约 0.8 秒弹出助手浮层；短按不受影响。需保持 Shizuku 就绪。",
                valueSchema = ConfigSchema.Bool,
                reader = { ConfigValue.Bool(runBlocking { overlaySettings.powerKeyFlow.first() }) },
                writer = { v ->
                    val enabled = (v as? ConfigValue.Bool)?.value
                        ?: throw ConfigError.TypeMismatch("bool")
                    runBlocking { overlaySettings.setPowerKey(enabled) }
                    if (enabled) PowerKeyWatcherService.start(context) else PowerKeyWatcherService.stop(context)
                },
            ),
        )
    }
}
