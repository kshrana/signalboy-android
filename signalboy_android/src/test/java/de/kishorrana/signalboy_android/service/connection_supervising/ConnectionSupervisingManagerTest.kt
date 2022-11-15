package de.kishorrana.signalboy_android.service.connection_supervising

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.kishorrana.signalboy_android.BlankActivity
import de.kishorrana.signalboy_android.FakeSignalboyMediator
import de.kishorrana.signalboy_android.service.SignalboyMediator
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
    private lateinit var signalboyMediator: FakeSignalboyMediator

    @get:Rule
    val activityRule = ActivityScenarioRule(BlankActivity::class.java)

    // Class under test
    private lateinit var connectionSupervisingManager: ConnectionSupervisingManager

    @Before
    fun setup() {
        activityResultProxy = FakeActivityResultProxy()
        signalboyMediator = FakeSignalboyMediator()

        connectionSupervisingManager = ConnectionSupervisingManager(signalboyMediator)
    }

    @Test
    fun getHasUserInteractionRequest_supervisingNotStarted_returnsFalse() {
        // Given.

        // When.

        // Then.
        assertThat(connectionSupervisingManager.hasUserInteractionRequest, `is`(false))
    }

    @Test
    fun getHasUserInteractionRequest_supervisingStartedAndSuccessfulConnectionAttempt_returnsFalse() =
        runTest {
            // Given.

            // When.
            val connectionSupervising = launch {
                connectionSupervisingManager.superviseConnection()
            }

            advanceTimeBy(DELAY_SHORT)
            checkNotNull(signalboyMediator.connectRequestResolver).invoke(
                Result.success(makeConnectedState())
            )

            // Then.
            runCurrent()
            assertThat(connectionSupervisingManager.hasUserInteractionRequest, `is`(false))

            connectionSupervising.cancel()
        }

    @Test
    fun getHasUserInteractionRequest_previousAssociationAndSupervisingStartedAndNoSuccessfulConnectionAttempt_returnsTrue() {
        activityRule.scenario.onActivity { activity ->
            runTest {
                // Given.

                // When.
                val connectionSupervising = launch {
                    connectionSupervisingManager.superviseConnection()
                }

                advanceTimeBy(DELAY_SHORT)
                checkNotNull(signalboyMediator.connectRequestResolver).invoke(
                    Result.failure(UserInteractionRequiredException())
                )

                runCurrent()
                assertThat(connectionSupervisingManager.hasUserInteractionRequest, `is`(true))
                val deferredRequestResult = async {
                    connectionSupervisingManager.resolveUserInteractionRequest(
                        activity,
                        activityResultProxy
                    )
                }

                runCurrent()
                assertThat(deferredRequestResult.isActive, `is`(true))
                assertThat(connectionSupervisingManager.hasUserInteractionRequest, `is`(false))

                advanceTimeBy(DELAY_LONG)
                val expectedException = AssociationDiscoveryTimeoutException()
                checkNotNull(signalboyMediator.connectRequestResolver).invoke(
                    Result.failure(expectedException)
                )

                val requestResult = deferredRequestResult.await()
                assertThat(requestResult.exceptionOrNull(), isA(expectedException.javaClass))

                // Then.
                assertThat(connectionSupervisingManager.hasUserInteractionRequest, `is`(true))

                connectionSupervising.cancel()
            }
        }
    }

    companion object {
        private const val DELAY_SHORT = 150L
        private const val DELAY_LONG = 31_000L

        private fun makeConnectedState() = SignalboyMediator.State.Connected(
            SignalboyDeviceInformation(
                "Signalboy",
                "1",
                "42"
            ),
            false
        )
    }
}
