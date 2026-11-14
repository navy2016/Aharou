package com.aharou.feature.agent.domain.tool

import com.aharou.feature.agent.domain.notification.AgentNotificationCenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileVersionRegistryTest {
    private val notifications = AgentNotificationCenter()
    private val versions = FileVersionRegistry(notifications)
    private val path = "~/workspace/file.kt"

    @Test
    fun externalWritePreservesEverySessionsLastReadVersion() {
        versions.record("first", path, "version-1")
        versions.record("second", path, "version-2")
        versions.recordExternal(path, "version-3")
        assertEquals("version-1", versions.get("first", path))
        assertEquals("version-2", versions.get("second", path))
        assertEquals("用户/编辑器", versions.lastWriter(path))
        assertEquals(1, notifications.pendingCount("first"))
        assertEquals(1, notifications.pendingCount("second"))
    }

    @Test
    fun externalWriteDoesNotCreateAReadBaselineForOtherFiles() {
        versions.record("session", "other", "old")
        versions.recordExternal(path, "new")
        assertNull(versions.get("session", path))
        assertEquals(1, versions.trackedEntryCount("session"))
        assertEquals(0, notifications.pendingCount("session"))
    }

    @Test
    fun rereadingRefreshesOnlyTheReadingSession() {
        versions.record("first", path, "old")
        versions.record("second", path, "old")
        versions.recordExternal(path, "new")
        versions.record("first", path, "new")
        assertEquals("new", versions.get("first", path))
        assertEquals("old", versions.get("second", path))
        assertEquals(0, notifications.pendingCount("first"))
        assertEquals(1, notifications.pendingCount("second"))
    }

    @Test
    fun changedDependencyIsReportedWithoutInvalidatingAnUnchangedWriteTarget() {
        versions.record("consumer", "interface", "a0", "~/workspace/A.kt")
        versions.record("consumer", "caller", "b0", "~/workspace/B.kt")
        versions.recordWrite("producer", "interface", "a1", "~/workspace/A.kt")
        assertEquals("a0", versions.get("consumer", "interface"))
        assertEquals("b0", versions.get("consumer", "caller"))
        assertEquals("a1", versions.get("producer", "interface"))
        assertEquals("~/workspace/A.kt", notifications.peek("consumer").single().title)
        assertTrue(notifications.peekForWakeup("consumer").isEmpty())
        assertEquals(0, notifications.pendingCount("producer"))
    }

    @Test
    fun identicalDisplayPathsInDifferentResourcesDoNotNotifyEachOther() {
        versions.record("first", "resource-one", "old", path)
        versions.record("second", "resource-two", "old", path)
        versions.recordExternal("resource-one", "new")
        assertEquals(1, notifications.pendingCount("first"))
        assertEquals(0, notifications.pendingCount("second"))
    }

    @Test
    fun repeatedChangesCoalesceAndSuccessfulOwnWriteClearsTheReminder() {
        versions.record("session", path, "old")
        versions.recordExternal(path, "new")
        versions.recordExternal(path, "newer")
        assertEquals(1, notifications.pendingCount("session"))
        versions.recordWrite("session", path, "own", path)
        assertEquals("own", versions.get("session", path))
        assertEquals(0, notifications.pendingCount("session"))
    }

    @Test
    fun writeDuringReadIsNotLostWhenTheOldSnapshotRegistersLater() {
        val started = versions.currentRevision()
        versions.recordExternal(path, "new")
        versions.record("session", path, "old", path, started)
        assertEquals("old", versions.get("session", path))
        assertEquals(1, notifications.pendingCount("session"))
        versions.record("session", path, "new", path, versions.currentRevision())
        assertEquals(0, notifications.pendingCount("session"))
    }

    @Test
    fun unchangedContentAndWritesBeforeTheReadDoNotProduceReminders() {
        versions.recordExternal(path, "old")
        versions.record("session", path, "new", path, versions.currentRevision())
        assertEquals(0, notifications.pendingCount("session"))
        versions.recordExternal(path, "changed")
        assertEquals(1, notifications.pendingCount("session"))
        versions.recordExternal(path, "new")
        assertEquals(0, notifications.pendingCount("session"))
    }

    @Test
    fun forgettingAndDeletingSessionsReleaseTheirReminders() {
        versions.record("first", path, "old")
        versions.record("second", path, "old")
        versions.recordExternal(path, "new")
        versions.forget("first", path)
        versions.clearSession("second")
        assertEquals(0, notifications.pendingCount("first"))
        assertEquals(0, notifications.pendingCount("second"))
        assertEquals(1, versions.trackedSessionCount())
    }
}
