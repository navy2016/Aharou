package com.aharou.core.config.audit

import android.content.Context
import android.database.sqlite.SQLiteOpenHelper
import com.aharou.core.config.ConfigRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.rules.ExternalResource

class ConfigAuditIsolationRule : ExternalResource() {
    private val logInstanceField = ConfigAuditLog::class.java.getDeclaredField("INSTANCE").apply {
        isAccessible = true
    }
    private val helperField = ConfigAuditLog::class.java.getDeclaredField("helper").apply {
        isAccessible = true
    }
    private val registryInstanceField = ConfigRegistry::class.java.getDeclaredField("INSTANCE").apply {
        isAccessible = true
    }

    // Initialize in the test's @Before: ExternalResource skips after() if its own before() fails.
    override fun before() {
        resetSingletons()
    }

    override fun after() {
        resetSingletons()
    }

    fun assertDatabasePath(context: Context, log: ConfigAuditLog) {
        val helper = helperField.get(log) as SQLiteOpenHelper
        assertEquals(
            "Audit helper must use the current test context's filesDir",
            File(context.filesDir, "aharou-config-audit.db").absolutePath,
            helper.databaseName,
        )
    }

    private fun resetSingletons() {
        try {
            val log = logInstanceField.get(null) as ConfigAuditLog?
            if (log != null) {
                (helperField.get(log) as SQLiteOpenHelper).close()
            }
        } finally {
            try {
                logInstanceField.set(null, null)
            } finally {
                registryInstanceField.set(null, null)
            }
        }
    }
}
