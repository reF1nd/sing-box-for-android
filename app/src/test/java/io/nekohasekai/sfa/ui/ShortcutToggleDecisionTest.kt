package io.nekohasekai.sfa.ui

import io.nekohasekai.sfa.bg.BoxService
import io.nekohasekai.sfa.constant.ServiceMode
import io.nekohasekai.sfa.constant.Status
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutToggleDecisionTest {
    @Test
    fun resolveEntryActionNeverDirectlyTogglesFromExportedActivity() {
        assertEquals(
            ShortcutEntryAction.CREATE_SHORTCUT,
            ShortcutToggleDecision.resolveEntryAction(ShortcutToggleDecision.ACTION_CREATE_SHORTCUT),
        )
        assertEquals(
            ShortcutEntryAction.OPEN_MAIN_ACTIVITY,
            ShortcutToggleDecision.resolveEntryAction(ShortcutToggleDecision.ACTION_MAIN),
        )
        assertEquals(
            ShortcutEntryAction.IGNORE,
            ShortcutToggleDecision.resolveEntryAction(null),
        )
        assertEquals(
            ShortcutEntryAction.IGNORE,
            ShortcutToggleDecision.resolveEntryAction("io.attacker.STOP_VPN"),
        )
    }

    @Test
    fun isUnlockedFailsClosedWhenLockedOrUnknown() {
        assertTrue(ShortcutToggleDecision.isUnlocked(false))
        assertFalse(ShortcutToggleDecision.isUnlocked(true))
        assertFalse(ShortcutToggleDecision.isUnlocked(null))
    }

    @Test
    fun resolveToggleActionStopsStartingAndStartedWithoutLoadingMode() = runTest {
        var modeLoaded = false
        var prepareCalled = false

        for (status in listOf(Status.Starting, Status.Started)) {
            val action =
                ShortcutToggleDecision.resolveToggleAction(
                    status = status,
                    loadServiceMode = {
                        modeLoaded = true
                        ServiceMode.VPN
                    },
                    prepareVpn = {
                        prepareCalled = true
                        "intent"
                    },
                )
            assertEquals(ShortcutToggleAction.Stop, action)
        }
        assertFalse(modeLoaded)
        assertFalse(prepareCalled)
    }

    @Test
    fun resolveToggleActionIgnoresStoppingState() = runTest {
        val action =
            ShortcutToggleDecision.resolveToggleAction(
                status = Status.Stopping,
                loadServiceMode = { throw AssertionError("should not load mode") },
                prepareVpn = { throw AssertionError("should not prepare vpn") },
            )
        assertEquals(ShortcutToggleAction.Ignore, action)
    }

    @Test
    fun resolveToggleActionStartsDirectlyInNormalMode() = runTest {
        var prepareCalled = false
        val action =
            ShortcutToggleDecision.resolveToggleAction(
                status = Status.Stopped,
                loadServiceMode = { ServiceMode.NORMAL },
                prepareVpn = {
                    prepareCalled = true
                    "intent"
                },
            )
        assertEquals(ShortcutToggleAction.Start, action)
        assertFalse(prepareCalled)
    }

    @Test
    fun resolveToggleActionStartsDirectlyWhenVpnAlreadyPrepared() = runTest {
        var prepareCalled = false
        val action =
            ShortcutToggleDecision.resolveToggleAction(
                status = Status.Stopped,
                loadServiceMode = { ServiceMode.VPN },
                prepareVpn = {
                    prepareCalled = true
                    null
                },
            )
        assertEquals(ShortcutToggleAction.Start, action)
        assertTrue(prepareCalled)
    }

    @Test
    fun resolveToggleActionRequestsPermissionWhenVpnNeedsPrepare() = runTest {
        val consentToken = "vpn-consent-intent"
        val action =
            ShortcutToggleDecision.resolveToggleAction(
                status = Status.Stopped,
                loadServiceMode = { ServiceMode.VPN },
                prepareVpn = { consentToken },
            )
        assertEquals(ShortcutToggleAction.RequestVpnPermission(consentToken), action)
    }

    @Test
    fun resolveToggleActionOpensMainActivityWhenVpnPrepareThrows() = runTest {
        val action =
            ShortcutToggleDecision.resolveToggleAction(
                status = Status.Stopped,
                loadServiceMode = { ServiceMode.VPN },
                prepareVpn = { throw IllegalStateException("always-on vpn blocked") },
            )
        assertEquals(ShortcutToggleAction.OpenMainActivity, action)
    }

    @Test
    fun resolveToggleActionSkipsPrepareVpnWhenLockedDuringServiceModeLoad() = runTest {
        val modeReady = CompletableDeferred<String>()
        var unlocked = true
        var prepareCalled = false

        val deferredAction =
            async {
                ShortcutToggleDecision.resolveToggleAction(
                    status = Status.Stopped,
                    loadServiceMode = { modeReady.await() },
                    prepareVpn = {
                        prepareCalled = true
                        "vpn-consent-intent"
                    },
                    isUnlocked = { unlocked },
                )
            }
        runCurrent()
        unlocked = false
        modeReady.complete(ServiceMode.VPN)

        assertEquals(ShortcutToggleAction.Ignore, deferredAction.await())
        assertFalse(prepareCalled)
    }

    @Test
    fun runStartForegroundServiceSkipsStartWhenLockedDuringPreparation() = runTest {
        val prepareGate = CompletableDeferred<Unit>()
        var unlocked = true
        var started = false

        val deferredStarted =
            async {
                BoxService.runStartForegroundService(
                    prepareStart = { prepareGate.await() },
                    shouldStart = { unlocked },
                    startService = { started = true },
                )
            }
        runCurrent()
        unlocked = false
        prepareGate.complete(Unit)

        assertFalse(deferredStarted.await())
        assertFalse(started)

        unlocked = true
        val startedWhenUnlocked =
            BoxService.runStartForegroundService(
                prepareStart = {},
                shouldStart = { unlocked },
                startService = { started = true },
            )
        assertTrue(startedWhenUnlocked)
        assertTrue(started)
    }
}
