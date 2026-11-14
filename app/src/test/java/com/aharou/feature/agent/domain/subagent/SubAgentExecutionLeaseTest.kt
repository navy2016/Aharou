package com.aharou.feature.agent.domain.subagent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubAgentExecutionLeaseTest {
    private val leases = SubAgentWriteLease()

    @Test fun sameRelativePathInDifferentWorktreesDoesNotConflict() = runTest {
        val active = setOf("a", "b")
        assertNull(leases.claim("a", setOf("src"), setOf("local\u0000/a/src"), active))
        assertNull(leases.claim("b", setOf("src"), setOf("local\u0000/b/src"), active))
        assertNull(leases.conflictFor("b", setOf("src"), active))
    }

    @Test fun aliasesOfTheSameActualFileConflict() = runTest {
        val active = setOf("a", "b")
        leases.claim("a", setOf("alias.kt"), setOf("local\u0000/project/target.kt"), active)
        assertEquals("a", leases.claim("b", setOf("target.kt"), setOf("local\u0000/project/target.kt"), active))
    }

    @Test fun sharedConfigurationStillConflictsAcrossWorktrees() = runTest {
        val active = setOf("a", "b")
        leases.claim("a", setOf("~/.aharou/config"), setOf("local\u0000/config/global"), active)
        assertEquals("a", leases.claim("b", setOf("~/.aharou/config"), setOf("local\u0000/config/global"), active))
    }

    @Test fun aRejectedGrantDoesNotPersistOrExpandTheLease() = runTest {
        val active = setOf("a", "b")
        leases.claim("a", setOf("a.kt"), setOf("local\u0000/project/a.kt"), active)
        leases.claim("b", setOf("b.kt"), setOf("local\u0000/project/b.kt"), active)
        var writes = 0
        assertEquals("a", leases.grantIfAvailable("b", setOf("a.kt"), setOf("local\u0000/project/a.kt"), active) { writes++ })
        assertEquals(0, writes)
        assertEquals(setOf("b.kt"), leases.pathsFor("b"))
    }
}
