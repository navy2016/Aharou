package com.aharou.feature.agent.domain.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileVersionRegistryTest {
    private val versions = FileVersionRegistry()
    private val path = "~/workspace/file.kt"

    @Test
    fun externalWritePreservesEverySessionsLastReadVersion() {
        versions.record("first", path, "version-1")
        versions.record("second", path, "version-2")
        versions.recordExternal(path)
        assertEquals("version-1", versions.get("first", path))
        assertEquals("version-2", versions.get("second", path))
        assertEquals("用户/编辑器", versions.lastWriter(path))
    }

    @Test
    fun externalWriteDoesNotCreateAReadBaselineForOtherFiles() {
        versions.record("session", "other", "old")
        versions.recordExternal(path)
        assertNull(versions.get("session", path))
        assertEquals(1, versions.trackedEntryCount("session"))
    }

    @Test
    fun rereadingRefreshesOnlyTheReadingSession() {
        versions.record("first", path, "old")
        versions.record("second", path, "old")
        versions.recordExternal(path)
        versions.record("first", path, "new")
        assertEquals("new", versions.get("first", path))
        assertEquals("old", versions.get("second", path))
    }
}
