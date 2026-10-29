package koharia.komga.api

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Flugel: virtual time exercises proxy disconnects without a live Komga or a 100-second wait.
@OptIn(ExperimentalCoroutinesApi::class)
class KomgaSseReconnectControllerTest {
    @Test
    fun `proxy idle disconnects always reconnect after one second`() = runTest {
        var reconnects = 0
        val controller = KomgaSseReconnectController({ this }, { testScheduler.currentTime }) { reconnects++ }

        repeat(20) {
            controller.onOpen()
            advanceTimeBy(100_000)
            assertEquals(KomgaSseRetry(1_000, wasStable = true), controller.schedule())
            assertTrue(controller.isScheduled)
            runCurrent()
            advanceTimeBy(999)
            assertEquals(it, reconnects)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(it + 1, reconnects)
            assertFalse(controller.isScheduled)
        }
    }

    @Test
    fun `outages back off exponentially with a one minute cap`() = runTest {
        var reconnects = 0
        val controller = KomgaSseReconnectController({ this }, { testScheduler.currentTime }) { reconnects++ }
        val expectedDelays = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L)

        expectedDelays.forEachIndexed { index, delay ->
            assertEquals(KomgaSseRetry(delay, wasStable = false), controller.schedule())
            runCurrent()
            advanceTimeBy(delay - 1)
            assertEquals(index, reconnects)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(index + 1, reconnects)
        }
    }

    @Test
    fun `brief successful handshakes do not cause a rapid retry loop`() = runTest {
        val controller = KomgaSseReconnectController({ this }, { testScheduler.currentTime }) {}
        listOf(1_000L, 2_000L, 4_000L, 8_000L).forEach { delay ->
            controller.onOpen()
            advanceTimeBy(10)
            assertEquals(KomgaSseRetry(delay, wasStable = false), controller.schedule())
            runCurrent()
            advanceTimeBy(delay)
            runCurrent()
        }
    }

    @Test
    fun `a stable stream recovers promptly after earlier failures`() = runTest {
        val controller = KomgaSseReconnectController({ this }, { testScheduler.currentTime }) {}
        repeat(4) {
            val retry = controller.schedule()!!
            runCurrent()
            advanceTimeBy(retry.delayMillis)
            runCurrent()
        }
        controller.onOpen()
        advanceTimeBy(30_000)
        assertEquals(KomgaSseRetry(1_000, wasStable = true), controller.schedule())
        controller.cancel()
    }

    @Test
    fun `duplicate terminal callbacks schedule just one retry`() = runTest {
        var reconnects = 0
        val controller = KomgaSseReconnectController({ this }, { testScheduler.currentTime }) { reconnects++ }
        controller.schedule()
        assertNull(controller.schedule())
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, reconnects)
        assertEquals(KomgaSseRetry(2_000, wasStable = false), controller.schedule())
        controller.cancel()
    }

    @Test
    fun `backgrounding or switching servers cancels pending retries and resets backoff`() = runTest {
        var reconnects = 0
        val controller = KomgaSseReconnectController({ this }, { testScheduler.currentTime }) { reconnects++ }
        controller.schedule()
        runCurrent()
        advanceTimeBy(999)
        controller.cancel()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(0, reconnects)
        assertFalse(controller.isScheduled)
        assertEquals(KomgaSseRetry(1_000, wasStable = false), controller.schedule())
        controller.cancel()
    }
}
