package de.kishorrana.signalboy_android.service.connection_supervising

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.kishorrana.signalboy_android.BlankActivity
import de.kishorrana.signalboy_android.FakeSignalboyService
import de.kishorrana.signalboy_android.service.BluetoothDisabledException
import de.kishorrana.signalboy_android.service.ISignalboyService
import de.kishorrana.signalboy_android.service.discovery.FakeActivityResultProxy
import de.kishorrana.signalboy_android.service.discovery.UserInteractionRequiredException
import de.kishorrana.signalboy_android.service.discovery.companion_device.AssociationDiscoveryTimeoutException
import de.kishorrana.signalboy_android.service.gatt.SignalboyDeviceInformation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// TODO: Add additional tests.
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionSupervisingManagerTest {
    private lateinit var activityResultProxy: FakeActivityResultProxy
    private lateinit var signalboyService: FakeSignalboyService

    @get:Rule
    val activityRule = ActivityScenarioRule(BlankActivity::class.java)

    // Class under test
    private lateinit var connectionSupervisingManager: ConnectionSupervisingManager

    @Before
    fun setup() {
        activityResultProxy = FakeActivityResultProxy()
        signalboyService = FakeSignalboyService()

        connectionSupervisingManager = ConnectionSupervisingManager(signalboyService)
    }

    @Test
    fun hasAnyOpenUserInteractionRequest_supervisingNotStarted_returnsFalse() {
        // Given.

        // When.

        // Then.
        assertThat(connectionSupervisingManager.hasAnyOpenUserInteractionRequest, `is`(false))
    }

    @Test
    fun hasAnyOpenUserInteractionRequest_supervisingStartedAndSuccessfulConnectionAttempt_returnsFalse() =
        runTest {
            // Given.

            // When.
            val connectionSupervising = launch {
                connectionSupervisingManager.superviseConnection()
            }

            advanceTimeBy(DELAY_SHORT)
            signalboyService.lastConnectRequest!!.resolve(Result.success(makeConnectedState()))

            // Then.
            runCurrent()
            assertThat(connectionSupervisingManager.hasAnyOpenUserInteractionRequest, `is`(false))

            connectionSupervising.cancel()
        }

    @Test
    fun hasAnyOpenUserInteractionRequest_supervisingStartedAndConnectionAttemptFailedDueToBluetoothDisabled_returnsFalse() {
        activityRule.scenario.onActivity { activity ->
            runTest {
                // Given.

                // When.
                val connectionSupervising = launch {
                    connectionSupervisingManager.superviseConnection()
                }

                advanceTimeBy(DELAY_SHORT)
                val connectRequest1 = signalboyService.lastConnectRequest!!
                connectRequest1.resolve(Result.failure(BluetoothDisabledException()))

                runCurrent()
                assertThat(
                    connectionSupervisingManager.hasAnyOpenUserInteractionRequest,
                    `is`(false)
                )

                advanceTimeBy(DELAY_LONG)
                val connectRequest2 = signalboyService.lastConnectRequest!!
                    .also {
                        assertThat(
                            "Started new Connect Request.",
                            connectRequest1, `is`(not(sameInstance(it)))
                        )
                    }
                connectRequest2.resolve(Result.failure(BluetoothDisabledException()))

                // Then.
                assertThat(
                    connectionSupervisingManager.hasAnyOpenUserInteractionRequest, `is`(false)
                )

                connectionSupervising.cancel()
            }
        }
    }

    @Test
    fun hasAnyOpenUserInteractionRequest_supervisingStartedAndConnectionAttemptFailedDueToUserInteractionRequired_returnsTrue() {
        activityRule.scenario.onActivity { activity ->
            runTest {
                // Given.

                // When.
                val connectionSupervising = launch {
                    connectionSupervisingManager.superviseConnection()
                }

                advanceTimeBy(DELAY_SHORT)
                signalboyService.lastConnectRequest!!.resolve(
                    Result.failure(UserInteractionRequiredException())
                )

                runCurrent()
                assertThat(
                    connectionSupervisingManager.hasAnyOpenUserInteractionRequest,
                    `is`(true)
                )
                val deferredRequestResult = async {
                    connectionSupervisingManager.resolveUserInteractionRequest(
                        activity,
                        activityResultProxy
                    )
                }

                runCurrent()
                assertThat(deferredRequestResult.isActive, `is`(true))
                assertThat(
                    connectionSupervisingManager.hasAnyOpenUserInteractionRequest,
                    `is`(false)
                )

                advanceTimeBy(DELAY_LONG)
                val expectedException = AssociationDiscoveryTimeoutException()
                signalboyService.lastConnectRequest!!.resolve(Result.failure(expectedException))

                val requestResult = deferredRequestResult.await()
                assertThat(requestResult.exceptionOrNull(), isA(expectedException.javaClass))

                // Then.
                assertThat(
                    connectionSupervisingManager.hasAnyOpenUserInteractionRequest,
                    `is`(true)
                )

                connectionSupervising.cancel()
            }
        }
    }

    // Scenario 1:
    // - procedure:
    //   - supervisingStarted
    //   - connectionAttemptFailedDueToUserInteractionRequired
    //   - userInteractionRequestReceivedContinuation
    //   - connectionAttemptFailedDueToUserInteractionRequired (still the one before the continuation)
    //   - connectionAttemptFailedDueToAssociationDiscoveryTimeout
    // - returns: `true`
    @Test
    fun hasAnyOpenUserInteractionRequest_scenario1_returnsTrue() {
        activityRule.scenario.onActivity { activity ->
            runTest {
                // Given.

                // When.
                val connectionSupervising = launch {
                    connectionSupervisingManager.superviseConnection()
                }

                advanceTimeBy(DELAY_SHORT)
                val connectRequest1 = signalboyService.lastConnectRequest!!
                connectRequest1.resolve(Result.failure(UserInteractionRequiredException()))

                // Delay until next connection attempt is started.
                advanceTimeBy(DELAY_LONG)
                val connectRequest2 = signalboyService.lastConnectRequest!!.also {
                    assertThat("New Connect-Request.", it, `is`(not(sameInstance(connectRequest1))))
                }
                assertThat(
                    "Connect-Request is open.", connectRequest2.isResolved, `is`(false)
                )
                assertThat(
                    "Connect-Request is not able to handle User-Interaction.",
                    connectRequest2.hasReceivedAllDependenciesForUserInteraction, `is`(false)
                )
                assertThat(
                    connectionSupervisingManager.hasAnyOpenUserInteractionRequest, `is`(true)
                )
                val deferredRequestResult = async {
                    // Continue User-Interaction-Request
                    connectionSupervisingManager.resolveUserInteractionRequest(
                        activity,
                        activityResultProxy
                    )
                }

                advanceTimeBy(DELAY_SHORT)
                assertThat(
                    "No open User-Interaction-Request.",
                    connectionSupervisingManager.hasAnyOpenUserInteractionRequest, `is`(false)
                )
                assertThat(
                    "Active Connect-Request is still the one, that was triggered" +
                            " **before** the User-Interaction-Request received its continuation.",
                    signalboyService.lastConnectRequest!!, `is`(sameInstance(connectRequest2))
                )
                connectRequest2.resolve(Result.failure(UserInteractionRequiredException()))

                advanceTimeBy(DELAY_LONG)
                assertThat(
                    "User-Interaction-Request's resolving is still being awaited.",
                    deferredRequestResult.isActive, `is`(true)
                )
                assertThat(
                    "No open User-Interaction-Request.",
                    connectionSupervisingManager.hasAnyOpenUserInteractionRequest, `is`(false)
                )
                assertThat(
                    "New Connect-Request.",
                    signalboyService.lastConnectRequest!!, `is`(not(sameInstance(connectRequest2)))
                )
                assertThat(
                    "Connect-Request is able to handle User-Interaction.",
                    signalboyService.lastConnectRequest!!.hasReceivedAllDependenciesForUserInteraction,
                    `is`(true)
                )

                val expectedException = AssociationDiscoveryTimeoutException()
                signalboyService.lastConnectRequest!!.resolve(Result.failure(expectedException))

                val requestResult = deferredRequestResult.await()
                assertThat(requestResult.exceptionOrNull(), isA(expectedException.javaClass))

                // Then.
                assertThat(
                    "(Re)-opened User-Interaction-Request.",
                    connectionSupervisingManager.hasAnyOpenUserInteractionRequest, `is`(true)
                )

                connectionSupervising.cancel()
            }
        }
    }

    companion object {
        private const val DELAY_SHORT = 150L
        private const val DELAY_LONG = 31_000L

        private fun makeConnectedState() = ISignalboyService.State.Connected(
            SignalboyDeviceInformation(
                "Signalboy",
                "1",
                "42"
            ),
            false
        )
    }
}
