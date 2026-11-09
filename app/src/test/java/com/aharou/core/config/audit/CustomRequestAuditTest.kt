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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class CustomRequestAuditTest {
    @get:Rule
    val auditIsolation = ConfigAuditIsolationRule()

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
        auditIsolation.assertDatabasePath(context, log)
        log.clearAll()
    }

    @After
    fun tearDown() {
        unmockkObject(KeystoreCipher)
    }

    private fun entry(path: String) = ConfigAuditEntry(
        id = path,
        at = System.currentTimeMillis(),
        actor = ConfigAuditActor.AGENT,
        sessionId = null,
        scope = path.substringBefore('.'),
        key = path,
        oldValueJSON = "{\"X-Test\":\"before\"}",
        newValueJSON = "{\"X-Test\":\"after\"}",
        confirmedAt = System.currentTimeMillis(),
        status = ConfigAuditStatus.APPLIED,
        revertOf = null,
        caption = null,
    )

    @Test
    fun allRequestScopesEncryptValuesOnDiskAndReadThemBack() {
        val entries = listOf(
            entry("custom_request.global.headers"),
            entry("providers.p.custom_request.body"),
            entry("providers.p.models.model-2.5:latest.custom_request.headers"),
        )
        entries.forEach { log.append(it) }
        SQLiteDatabase.openDatabase(
            File(context.filesDir, "aharou-config-audit.db").absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            entries.forEach { entry ->
                db.rawQuery("SELECT old_value, new_value FROM config_audit WHERE id = ?", arrayOf(entry.id)).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertTrue(cursor.getString(0).startsWith(prefix))
                    assertTrue(cursor.getString(1).startsWith(prefix))
                    assertNotEquals(entry.oldValueJSON, cursor.getString(0))
                    assertNotEquals(entry.newValueJSON, cursor.getString(1))
                }
                assertEquals(entry, log.get(entry.id))
            }
        }
        assertEquals(entries.toSet(), log.recent().toSet())
    }

    @Test
    fun encryptedAuditCanRevertTheOriginalValue() {
        val entry = entry("custom_request.global.body")
        var value = ConfigValue.decode(entry.newValueJSON)!!
        ConfigRegistry.init(context).register(ClosureField(
            path = entry.key,
            displayName = "Request body",
            description = "Request body",
            valueSchema = ConfigSchema.Json,
            risk = ConfigRisk.SENSITIVE,
            reader = { value },
            writer = { value = it },
        ))
        log.append(entry)

        assertNull(ConfigRevert.revert(entry.id, ConfigAuditActor.USER_REVERT.raw, null))
        assertEquals(ConfigValue.decode(entry.oldValueJSON), value)
        assertEquals(ConfigAuditStatus.REVERTED, log.get(entry.id)?.status)
        val revert = log.recent().single { it.revertOf == entry.id }
        assertEquals(entry.newValueJSON, revert.oldValueJSON)
        assertEquals(entry.oldValueJSON, revert.newValueJSON)
    }

    @Test
    fun deletedModelAndProviderRequestPathsRemainMasked() {
        val paths = listOf(
            "providers.deleted.custom_request.headers",
            "providers.deleted.models.model-2.5:latest.custom_request.body",
        )
        paths.forEach { path ->
            assertNull(ConfigRegistry.init(context).resolveField(path))
            assertTrue(isSensitivePath(path))
            assertEquals(ConfigValue.Str(CONFIG_MASK_TEXT), ConfigValue.Str("value").maskedForDisplay(isSensitivePath(path)))
        }
        assertFalse(isSensitivePath("providers.deleted.name"))
    }
}
