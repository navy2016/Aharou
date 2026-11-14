package com.aharou.feature.credentials.data

import com.aharou.core.util.ProcessIdentity
import com.aharou.feature.credentials.data.CredentialRequestBridge.CredentialRequest
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CredentialRequestLifetimeTest {
    @get:Rule val temporary = TemporaryFolder()
    private val lifetime = CredentialRequestLifetime()
    private val request = CredentialRequest("request", "example.invalid", 321, 456)

    @Before
    fun setUp() {
        mockkObject(ProcessIdentity)
        every { ProcessIdentity.readBootId() } returns "current-boot"
    }

    @After
    fun tearDown() { unmockkObject(ProcessIdentity) }

    @Test
    fun missingReusedAndZombieProcessesAreInactiveEvenWhenTheFileRemains() {
        val file = temporary.newFile()
        for (state in listOf(ProcessIdentity.State.MISSING, ProcessIdentity.State.REUSED, ProcessIdentity.State.MATCHED_ZOMBIE)) {
            every { ProcessIdentity.state(321, 456) } returns state
            assertFalse(lifetime.isActive(file, request, file.lastModified()))
            assertTrue(file.exists())
        }
        verify(exactly = 3) { ProcessIdentity.state(321, 456) }
    }

    @Test
    fun unreadableProcessIsNotMistakenForExitButStillExpires() {
        val file = temporary.newFile()
        every { ProcessIdentity.state(321, 456) } returns ProcessIdentity.State.UNREADABLE
        assertTrue(lifetime.isActive(file, request, file.lastModified()))
        assertFalse(lifetime.isActive(file, request, file.lastModified() + CredentialRequestLifetime.MAX_AGE_MS + 1))
        verify(exactly = 1) { ProcessIdentity.state(321, 456) }
    }

    @Test
    fun activeProcessDoesNotKeepADeletedRequestAlive() {
        val file = temporary.newFile()
        every { ProcessIdentity.state(321, 456) } returns ProcessIdentity.State.MATCHED_ACTIVE
        assertTrue(lifetime.isActive(file, request, file.lastModified()))
        assertTrue(file.delete())
        assertFalse(lifetime.isActive(file, request))
    }

    @Test
    fun requestFromAPreviousBootIsInactiveEvenWhenPidAndStartTicksMatch() {
        val file = temporary.newFile()
        every { ProcessIdentity.state(321, 456) } returns ProcessIdentity.State.MATCHED_ACTIVE
        assertTrue(lifetime.isActive(file, request.copy(bootId = "current-boot")))
        assertFalse(lifetime.isActive(file, request.copy(bootId = "previous-boot")))
    }

    @Test
    fun requestWithoutReadableProcessMetadataHasABoundedLifetime() {
        val file = temporary.newFile()
        val unknown = CredentialRequest("request", "example.invalid")
        assertTrue(lifetime.isActive(file, unknown, file.lastModified()))
        assertFalse(lifetime.isActive(file, unknown, file.lastModified() + CredentialRequestLifetime.MAX_AGE_MS + 1))
    }
}
