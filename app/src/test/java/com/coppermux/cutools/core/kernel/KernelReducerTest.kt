package com.coppermux.cutools.core.kernel

import com.coppermux.cutools.core.kernel.KernelEvent.BeginReset
import com.coppermux.cutools.core.kernel.KernelEvent.CompleteReset
import com.coppermux.cutools.core.kernel.KernelEvent.ConnectRequested
import com.coppermux.cutools.core.kernel.KernelEvent.ConnectionEstablished
import com.coppermux.cutools.core.kernel.KernelEvent.ConnectionFailed
import com.coppermux.cutools.core.kernel.KernelEvent.ConnectionLost
import com.coppermux.cutools.core.kernel.KernelEvent.InvalidateSession
import com.coppermux.cutools.core.kernel.KernelEvent.SessionsCleared
import com.coppermux.cutools.core.kernel.KernelEvent.TargetAttached
import com.coppermux.cutools.core.kernel.KernelEvent.TargetDetached
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure state-machine tests for [KernelReducer]: transitions, epoch rules and
 * invariants for every (state, event) pair.
 */
class KernelReducerTest {

    private val now = 1_000L

    private fun KernelSnapshot.apply(vararg events: KernelEvent): KernelSnapshot =
        events.fold(this) { s, e -> KernelReducer.reduce(s, e, now).next.also { assertInvariants(it) } }

    private fun reduce(s: KernelSnapshot, e: KernelEvent) = KernelReducer.reduce(s, e, now)

    private val idle = KernelSnapshot.INITIAL
    private val connecting get() = idle.apply(ConnectRequested, ConnectionEstablished)
    private val attached get() = connecting.apply(TargetAttached("t1", "s1"))
    private val error get() = connecting.apply(ConnectionFailed("boom"))
    private val resetting get() = attached.apply(BeginReset(7, "test"))

    private fun allStates(): Map<KernelState, KernelSnapshot> = mapOf(
        KernelState.IDLE to idle,
        KernelState.CONNECTING to connecting,
        KernelState.ATTACHED to attached,
        KernelState.ERROR to error,
        KernelState.RESETTING to resetting,
    )

    private fun allEvents(): List<KernelEvent> = listOf(
        ConnectRequested,
        ConnectionEstablished,
        ConnectionLost("lost"),
        ConnectionFailed("fail"),
        TargetAttached("t1", "s1"),
        TargetAttached("t2", "s2"),
        TargetDetached("t1"),
        TargetDetached("other"),
        SessionsCleared("clear"),
        InvalidateSession("inv"),
        KernelEvent.SetNetworkDrop(true),
        KernelEvent.SetNetworkDrop(false),
        BeginReset(99, "r"),
        CompleteReset(7),
        CompleteReset(12345),
    )

    // ------------------------------------------------------------------
    // Invariants
    // ------------------------------------------------------------------

    @Test
    fun everyStateEventPair_preservesInvariants_andNeverDecreasesEpoch() {
        allStates().forEach { (_, s) ->
            allEvents().forEach { e ->
                val r = reduce(s, e)
                assertInvariants(r.next)
                assertTrue("epoch decreased for $e from ${s.state}", r.next.epoch >= s.epoch)
                when (r.result) {
                    is TransitionResult.Applied -> assertEquals(s.transitionSeq + 1, r.next.transitionSeq)
                    else -> assertSame("non-applied result must not change snapshot ($e in ${s.state})", s, r.next)
                }
            }
        }
    }

    @Test
    fun epochAdvancesByAtMostOne_perNonResetEvent() {
        allStates().forEach { (_, s) ->
            allEvents().forEach { e ->
                val delta = reduce(s, e).next.epoch - s.epoch
                assertTrue("epoch advanced by $delta for $e in ${s.state}", delta in 0..1)
            }
        }
    }

    // ------------------------------------------------------------------
    // Connection lifecycle
    // ------------------------------------------------------------------

    @Test
    fun connect_fromIdle_movesToConnecting_withoutEpochChange() {
        val s = idle.apply(ConnectRequested)
        assertEquals(KernelState.CONNECTING, s.state)
        assertEquals(LinkStatus.CONNECTING, s.link)
        assertEquals(0L, s.epoch)
        val up = s.apply(ConnectionEstablished)
        assertEquals(KernelState.CONNECTING, up.state)
        assertEquals(LinkStatus.UP, up.link)
        assertEquals(0L, up.epoch)
    }

    @Test
    fun attach_fromConnecting_establishesSessionAtNewEpoch() {
        val s = attached
        assertEquals(KernelState.ATTACHED, s.state)
        assertEquals(1L, s.epoch)
        assertEquals("dk-1", s.session?.sessionKey)
        assertEquals("t1", s.session?.targetId)
        assertEquals("s1", s.session?.cdpSessionId)
        assertEquals(now, s.session?.establishedAtMillis)
    }

    @Test
    fun attach_fromIdle_isRejectedAsStale() {
        val r = reduce(idle, TargetAttached("t1", "s1"))
        assertTrue(r.result is TransitionResult.Rejected)
        assertSame(idle, r.next)
    }

    @Test
    fun attach_fromError_recoversToAttached() {
        val s = error.apply(TargetAttached("t1", "s1"))
        assertEquals(KernelState.ATTACHED, s.state)
        assertEquals(LinkStatus.UP, s.link)
        assertNull(s.lastError)
        assertEquals(error.epoch + 1, s.epoch)
    }

    @Test
    fun duplicateAttach_isIgnored_noEpochChange() {
        val r = reduce(attached, TargetAttached("t1", "s1"))
        assertTrue(r.result is TransitionResult.Ignored)
        assertEquals(attached.epoch, r.next.epoch)
    }

    @Test
    fun targetReplacement_advancesEpoch_andReplacesSession() {
        val replaced = attached.apply(TargetAttached("t2", "s2"))
        assertEquals(KernelState.ATTACHED, replaced.state)
        assertEquals(attached.epoch + 1, replaced.epoch)
        assertEquals("t2", replaced.session?.targetId)
        assertNotEquals(attached.session?.sessionKey, replaced.session?.sessionKey)
    }

    @Test
    fun sameTarget_newCdpSession_isTreatedAsReplacement() {
        val replaced = attached.apply(TargetAttached("t1", "s1b"))
        assertEquals(attached.epoch + 1, replaced.epoch)
        assertEquals("s1b", replaced.session?.cdpSessionId)
    }

    @Test
    fun detach_ofActiveTarget_invalidatesSession_keepsLink() {
        val s = attached.apply(TargetDetached("t1"))
        assertEquals(KernelState.CONNECTING, s.state)
        assertEquals(LinkStatus.UP, s.link)
        assertNull(s.session)
        assertNull(s.attachment)
        assertEquals(attached.epoch + 1, s.epoch)
    }

    @Test
    fun detach_ofOtherTarget_isIgnored() {
        val r = reduce(attached, TargetDetached("other"))
        assertTrue(r.result is TransitionResult.Ignored)
        assertEquals(KernelState.ATTACHED, r.next.state)
    }

    @Test
    fun sessionsCleared_whileAttached_invalidates() {
        val s = attached.apply(SessionsCleared("rebind"))
        assertEquals(KernelState.CONNECTING, s.state)
        assertEquals(attached.epoch + 1, s.epoch)
    }

    @Test
    fun connectionLost_whileAttached_invalidates_toIdle() {
        val s = attached.apply(ConnectionLost("closed"))
        assertEquals(KernelState.IDLE, s.state)
        assertEquals(LinkStatus.DOWN, s.link)
        assertNull(s.session)
        assertEquals(attached.epoch + 1, s.epoch)
    }

    @Test
    fun connectionLost_whileIdle_isIgnored() {
        assertTrue(reduce(idle, ConnectionLost("x")).result is TransitionResult.Ignored)
    }

    @Test
    fun connectionLost_afterError_settlesToIdle_withoutNewEpoch() {
        val s = error.apply(ConnectionLost("closed"))
        assertEquals(KernelState.IDLE, s.state)
        assertEquals(error.epoch, s.epoch)
    }

    @Test
    fun connectionFailed_whileAttached_invalidates_toError() {
        val s = attached.apply(ConnectionFailed("refused"))
        assertEquals(KernelState.ERROR, s.state)
        assertEquals("refused", s.lastError)
        assertEquals(attached.epoch + 1, s.epoch)
        assertNull(s.session)
    }

    @Test
    fun connectAfterError_clearsError() {
        val s = error.apply(ConnectRequested)
        assertEquals(KernelState.CONNECTING, s.state)
        assertNull(s.lastError)
    }

    @Test
    fun connectRequested_whileAttached_isHandledExplicitly_byInvalidating() {
        val s = attached.apply(ConnectRequested)
        assertEquals(KernelState.CONNECTING, s.state)
        assertNull(s.session)
        assertEquals(attached.epoch + 1, s.epoch)
    }

    @Test
    fun lossAndRecovery_produceDistinctEpochs() {
        val first = attached
        val lost = first.apply(ConnectionLost("drop"))
        val recovered = lost.apply(ConnectRequested, ConnectionEstablished, TargetAttached("t1", "s9"))
        assertEquals(KernelState.ATTACHED, recovered.state)
        assertEquals(first.epoch + 2, recovered.epoch)
        assertNotEquals(first.session?.sessionKey, recovered.session?.sessionKey)
    }

    // ------------------------------------------------------------------
    // Explicit invalidation
    // ------------------------------------------------------------------

    @Test
    fun invalidate_whileAttached_establishesSuccessorOnSameAttachment() {
        val s = attached.apply(InvalidateSession("navigation"))
        assertEquals(KernelState.ATTACHED, s.state)
        assertEquals(attached.epoch + 1, s.epoch)
        assertEquals(attached.session?.attachment, s.session?.attachment)
        assertNotEquals(attached.session?.sessionKey, s.session?.sessionKey)
    }

    @Test
    fun invalidate_withoutSession_isIgnored() {
        listOf(idle, connecting, error, resetting).forEach {
            assertTrue(reduce(it, InvalidateSession("x")).result is TransitionResult.Ignored)
        }
    }

    // ------------------------------------------------------------------
    // Reset
    // ------------------------------------------------------------------

    @Test
    fun beginReset_advancesEpoch_dropsSession_retainsAttachmentFact() {
        val s = resetting
        assertEquals(KernelState.RESETTING, s.state)
        assertEquals(attached.epoch + 1, s.epoch)
        assertNull(s.session)
        assertEquals(CdpAttachment("t1", "s1"), s.attachment)
        assertEquals(7L, s.resetTicket)
    }

    @Test
    fun beginReset_whileResetting_isRejected() {
        assertTrue(reduce(resetting, BeginReset(8, "again")).result is TransitionResult.Rejected)
    }

    @Test
    fun completeReset_withLiveAttachment_returnsToAttached_atFreshEpoch() {
        val before = resetting
        val s = before.apply(CompleteReset(7))
        assertEquals(KernelState.ATTACHED, s.state)
        assertEquals(before.epoch + 1, s.epoch)
        assertEquals("t1", s.session?.targetId)
        assertNull(s.resetTicket)
        assertNotEquals(attached.session?.sessionKey, s.session?.sessionKey)
    }

    @Test
    fun completeReset_withStaleTicket_isRejected() {
        assertTrue(reduce(resetting, CompleteReset(6)).result is TransitionResult.Rejected)
        assertTrue(reduce(attached, CompleteReset(7)).result is TransitionResult.Rejected)
    }

    @Test
    fun completeReset_resolvesToStateImpliedByLink() {
        assertEquals(KernelState.IDLE, idle.apply(BeginReset(1, "r"), CompleteReset(1)).state)
        assertEquals(KernelState.CONNECTING, connecting.apply(BeginReset(1, "r"), CompleteReset(1)).state)
        assertEquals(KernelState.ERROR, error.apply(BeginReset(1, "r"), CompleteReset(1)).state)
    }

    @Test
    fun eventsDuringReset_updateLinkFacts_butNeverCreateSession() {
        val detached = resetting.apply(TargetDetached("t1"))
        assertEquals(KernelState.RESETTING, detached.state)
        assertNull(detached.attachment)
        assertEquals(KernelState.CONNECTING, detached.apply(CompleteReset(7)).state)

        val reattached = detached.apply(TargetAttached("t2", "s2"))
        assertEquals(KernelState.RESETTING, reattached.state)
        assertNull(reattached.session)
        val done = reattached.apply(CompleteReset(7))
        assertEquals(KernelState.ATTACHED, done.state)
        assertEquals("t2", done.session?.targetId)

        val lost = resetting.apply(ConnectionLost("drop"))
        assertEquals(resetting.epoch + 1, lost.epoch)
        assertEquals(KernelState.IDLE, lost.apply(CompleteReset(7)).state)

        val failed = resetting.apply(ConnectionFailed("x"))
        assertEquals(KernelState.ERROR, failed.apply(CompleteReset(7)).state)
    }

    // ------------------------------------------------------------------
    // Rapid sequences
    // ------------------------------------------------------------------

    @Test
    fun rapidNavigationAndReconnection_neverYieldsMoreThanOneSession_andEpochsStrictlyIncreasePerSession() {
        var s = idle
        val sessionEpochs = mutableListOf<Long>()
        val script = listOf(
            ConnectRequested, ConnectionEstablished,
            TargetAttached("a", "1"), TargetAttached("b", "2"), TargetDetached("a"),
            TargetAttached("b", "2"), InvalidateSession("nav"), ConnectionLost("drop"),
            TargetAttached("c", "3"), ConnectRequested, ConnectionEstablished,
            TargetAttached("c", "3"), SessionsCleared("rebind"), TargetAttached("d", "4"),
            BeginReset(1, "r"), TargetAttached("e", "5"), CompleteReset(1),
            ConnectionFailed("x"), TargetAttached("f", "6"),
        )
        script.forEach { e ->
            val next = reduce(s, e).next
            assertInvariants(next)
            if (next.session != null && next.session != s.session) sessionEpochs += next.session!!.epoch
            s = next
        }
        assertEquals(sessionEpochs.sorted().distinct(), sessionEpochs)
        assertEquals(KernelState.ATTACHED, s.state)
        assertEquals("f", s.session?.targetId)
    }

    @Test
    fun setNetworkDrop_transitionsAndIdempotency() {
        val s0 = idle
        assertFalse(s0.isNetworkDropped)

        // Toggling ON
        val r1 = reduce(s0, KernelEvent.SetNetworkDrop(true))
        assertTrue(r1.result is TransitionResult.Applied)
        assertTrue(r1.next.isNetworkDropped)
        assertEquals(s0.epoch, r1.next.epoch)
        assertEquals(s0.transitionSeq + 1, r1.next.transitionSeq)

        // Duplicate ON is ignored
        val r2 = reduce(r1.next, KernelEvent.SetNetworkDrop(true))
        assertTrue(r2.result is TransitionResult.Ignored)
        assertSame(r1.next, r2.next)

        // Toggling OFF
        val r3 = reduce(r1.next, KernelEvent.SetNetworkDrop(false))
        assertTrue(r3.result is TransitionResult.Applied)
        assertFalse(r3.next.isNetworkDropped)
        assertEquals(r1.next.epoch, r3.next.epoch)
        assertEquals(r1.next.transitionSeq + 1, r3.next.transitionSeq)
    }

    companion object {
        fun assertInvariants(s: KernelSnapshot) {
            assertEquals("session iff ATTACHED: $s", s.state == KernelState.ATTACHED, s.session != null)
            s.session?.let {
                assertEquals("session epoch must equal snapshot epoch", s.epoch, it.epoch)
                assertEquals("attached implies link UP", LinkStatus.UP, s.link)
                assertEquals("attachment must mirror session", it.attachment, s.attachment)
                assertEquals("dk-${it.epoch}", it.sessionKey)
            }
            assertEquals("resetTicket iff RESETTING: $s", s.state == KernelState.RESETTING, s.resetTicket != null)
            if (s.link == LinkStatus.DOWN) assertNull("no attachment without link", s.attachment)
        }
    }
}
