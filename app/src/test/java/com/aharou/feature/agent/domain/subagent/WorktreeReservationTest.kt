package com.aharou.feature.agent.domain.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorktreeReservationTest {
    private var limit = 1
    private val bus = SubAgentEventBus(MaxRunningSource { limit }, CommunicationEnabledSource { true })

    @Test fun preparationReservesConcurrencyBeforePublishingAWorkflow() {
        val run = bus.reserveSpawn("first", "parent", "prepare")
        assertEquals(1, bus.activeCount)
        assertTrue(run.version == 0L)
        assertTrue(runCatching { bus.reserveSpawn("second", "parent", "prepare") }.isFailure)
        assertTrue(bus.releaseReserved(run))
        assertEquals(0, bus.activeCount)
    }

    @Test fun disablingDuringPreparationPreventsPublicationAndReleasesTheSlot() {
        val run = bus.reserveSpawn("first", "parent", "prepare")
        limit = 0
        assertFalse(bus.publishSpawn(run, "ready"))
        assertTrue(bus.releaseReserved(run))
        assertEquals(0, bus.activeCount)
    }

    @Test fun cancelledReservationCannotBePublishedLater() {
        val run = bus.reserveSpawn("first", "parent", "prepare")
        bus.releaseReserved(run)
        assertFalse(bus.publishSpawn(run, "late"))
        assertEquals(0, bus.activeCount)
    }
}
