package com.aharou.core.config.audit

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aharou.core.config.CONFIG_MASK_TEXT
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigRisk
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.core.config.isSensitivePath
import com.aharou.core.config.maskedForDisplay
import com.aharou.core.security.KeystoreCipher
import com.aharou.feature.agent.domain.model.ReasoningEffort
import com.aharou.feature.settings.data.ReasoningConfigCodec
import com.aharou.feature.settings.data.reasoningConfigField
import com.aharou.feature.settings.domain.model.ReasoningConfig
import com.google.gson.JsonParser
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ReasoningConfigAuditTest {
    private lateinit var context: Context
    private lateinit var log: ConfigAuditLog
    private val prefix = "AICODEENC1:"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        mockkObject(KeystoreCipher)
        every { KeystoreCipher.encryptString(any()) } answers {
            prefix + Base64.getEncoder().encodeToString(firstArg<String>().toByteArray())
        }
        every { KeystoreCipher.decryptString(any()) } answers {
            val value = firstArg<String>()
            if (value.startsWith(prefix)) String(Base64.getDecoder().decode(value.removePrefix(prefix))) else value
        }
        log = ConfigAuditLog.init(context)
        log.clearAll()
    }

    @After
    fun tearDown() {
        log.clearAll()
        unmockkObject(KeystoreCipher)
    }

    private fun entry(path: String) = ConfigAuditEntry(
        id = path,
        at = System.currentTimeMillis(),
        actor = ConfigAuditActor.AGENT,
        sessionId = null,
        scope = "providers",
        key = path,
        oldValueJSON = ConfigValue.Str("""{"patches":{"high":{"token":"before"}}}""").jsonString(),
        newValueJSON = ConfigValue.Str("""{"patches":{"high":{"token":"after"}}}""").jsonString(),
        confirmedAt = System.currentTimeMillis(),
        status = ConfigAuditStatus.APPLIED,
        revertOf = null,
        caption = null,
    )

    @Test
    fun reasoningConfigAuditEncryptsOnDiskAndDecryptsOnRead() {
        val entry = entry("providers.audit-provider.models.vendor/model-2.5:latest.reasoning_config")
        log.append(entry)

        SQLiteDatabase.openDatabase(
            File(context.filesDir, "aharou-config-audit.db").absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            db.rawQuery("SELECT old_value, new_value FROM config_audit WHERE id = ?", arrayOf(entry.id)).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.getString(0).startsWith(prefix))
                assertTrue(cursor.getString(1).startsWith(prefix))
                assertNotEquals(entry.oldValueJSON, cursor.getString(0))
                assertNotEquals(entry.newValueJSON, cursor.getString(1))
            }
        }
        assertEquals(entry, log.get(entry.id))
        assertEquals(listOf(entry), log.recent())
    }

    @Test
    fun existingSensitiveFieldsRetainTheirAuditStorageSemantics() {
        val path = "providers.audit-legacy.api_key"
        ConfigRegistry.init(context).register(ClosureField(
            path = path,
            displayName = "API key",
            description = "API key",
            valueSchema = ConfigSchema.Str(),
            risk = ConfigRisk.SENSITIVE,
            reader = { ConfigValue.Str("") },
            writer = {},
        ))
        val entry = entry(path)
        log.append(entry)

        SQLiteDatabase.openDatabase(
            File(context.filesDir, "aharou-config-audit.db").absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            db.rawQuery("SELECT old_value, new_value FROM config_audit WHERE id = ?", arrayOf(entry.id)).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(entry.oldValueJSON, cursor.getString(0))
                assertEquals(entry.newValueJSON, cursor.getString(1))
            }
        }
        assertTrue(isSensitivePath(path))
        assertEquals(entry, log.get(entry.id))
    }

    @Test
    fun deletedProviderAndModelPathsStayMaskedWithoutBroadeningOtherPaths() {
        val registry = ConfigRegistry.init(context)
        val path = "providers.deleted-reasoning.models.vendor/model-2.5:latest.reasoning_config"
        assertNull(registry.resolveField(path))
        assertTrue(isSensitivePath(path))
        assertEquals(ConfigValue.Str(CONFIG_MASK_TEXT), ConfigValue.Str("value").maskedForDisplay(isSensitivePath(path)))
        listOf(
            "providers.deleted-reasoning.name",
            "providers.deleted-reasoning.reasoning_config",
            "providers.deleted-reasoning.models.reasoning_config",
            "providers.deleted-reasoning.models.model.reasoning_effort",
            "$path.extra",
            "reasoning_config.global",
        ).forEach { otherPath -> assertFalse(otherPath, isSensitivePath(otherPath)) }
    }

    @Test
    fun encryptedAuditRevertsOriginalConfigThroughTheSharedCodec() {
        val before = ReasoningConfig(
            patches = mapOf(ReasoningEffort.NONE to JsonParser.parseString("""{"thinking":null}""").asJsonObject),
        )
        var value = ReasoningConfig(effortValues = mapOf(ReasoningEffort.HIGH to "high"))
        val path = "providers.audit-revert.models.model.reasoning_config"
        val entry = entry(path).copy(
            oldValueJSON = ConfigValue.Str(ReasoningConfigCodec.encode(before).toString()).jsonString(),
            newValueJSON = ConfigValue.Str(ReasoningConfigCodec.encode(value).toString()).jsonString(),
        )
        ConfigRegistry.init(context).register(reasoningConfigField(path, { value }, { value = it }))
        log.append(entry)

        assertNull(ConfigRevert.revert(entry.id, ConfigAuditActor.USER_REVERT.raw, null))

        assertEquals(before, value)
        assertEquals(ConfigAuditStatus.REVERTED, log.get(entry.id)?.status)
        val revert = log.recent().single { it.revertOf == entry.id }
        assertEquals(entry.newValueJSON, revert.oldValueJSON)
        assertEquals(entry.oldValueJSON, revert.newValueJSON)
    }
}
