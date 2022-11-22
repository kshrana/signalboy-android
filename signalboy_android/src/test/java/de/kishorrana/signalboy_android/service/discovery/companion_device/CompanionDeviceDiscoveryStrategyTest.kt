package de.kishorrana.signalboy_android.service.discovery.companion_device

import android.app.PendingIntent
import android.bluetooth.*
import android.bluetooth.le.ScanResult
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.getSystemService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.kishorrana.signalboy_android.service.client.Client
import de.kishorrana.signalboy_android.service.client.FakeClient
import de.kishorrana.signalboy_android.service.client.FakeClient.ConnectionRequestScenario
import de.kishorrana.signalboy_android.service.client.Session
import de.kishorrana.signalboy_android.service.client.util.hasAllSignalboyGattAttributes
import de.kishorrana.signalboy_android.service.discovery.FakeActivityResultProxy
import de.kishorrana.signalboy_android.service.discovery.UserInteractionRequiredException
import de.kishorrana.signalboy_android.service.gatt.SignalboyGattAttributes
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowCompanionDeviceManager
import java.util.*
import de.kishorrana.signalboy_android.service.client.State as ClientState

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CompanionDeviceDiscoveryStrategyTest {
    private lateinit var context: Context

    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter

    private lateinit var companionDeviceManager: CompanionDeviceManager
    private lateinit var shadowCompanionDeviceManager: ShadowCompanionDeviceManager

    private lateinit var packageManager: PackageManager

    private lateinit var companionDeviceManagerFacade: CompanionDeviceManagerFacade
    private lateinit var client: FakeClient
    private lateinit var activityResultProxy: FakeActivityResultProxy

    // Class under test
    private var addressFilter: (String) -> Boolean = { true }
    private lateinit var companionDeviceDiscoveryStrategy: CompanionDeviceDiscoveryStrategy

    @Before
    fun createStrategy() {
        context = ApplicationProvider.getApplicationContext()

        bluetoothManager = context.getSystemService()!!
        bluetoothAdapter = bluetoothManager.adapter.apply {
            shadowOf(this)
                // Sticking with the deprecated `setEnabled(Boolean)` here as the recommended
                // alternative `org.robolectric.shadows.ShadowBluetoothAdapter.enable()` is protected.
                .setEnabled(true)
        }

        companionDeviceManager = context.getSystemService()!!
        shadowCompanionDeviceManager = shadowOf(companionDeviceManager)

        packageManager = context.packageManager.apply {
            shadowOf(this)
                .setSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP, true)
        }

        companionDeviceManagerFacade = CompanionDeviceManagerFacade(
            packageManager,
            bluetoothAdapter,
            makeOriginAwareCompanionDeviceManagerStub(companionDeviceManager)
        )
        client = FakeClient(context)
        activityResultProxy = FakeActivityResultProxy()

        companionDeviceDiscoveryStrategy = CompanionDeviceDiscoveryStrategy(
            bluetoothAdapter,
            companionDeviceManagerFacade,
            associationRequestDeviceFilter = null,
            addressPredicate = { address ->
                // `addressFilter` allows tests to manipulate the closure.
                addressFilter(address)
            },
            servicesPredicate = { services ->
                services.hasAllSignalboyGattAttributes()
            },
        )
    }

    @Test
    fun discover_previousAssociationAndDeviceIsReachableAndCompatible_returnsAndClientIsConnected() =
        runTest {
            val expectedAddress = DEVICE_ADDRESS_1

            // Given previous association.
            shadowCompanionDeviceManager.addAssociation(expectedAddress)

            // When awaiting `discover`…
            val deferred = async(SupervisorJob()) {
                companionDeviceDiscoveryStrategy.discover(client)
            }

            // …and when device is reachable…
            advanceTimeBy(DELAY_SHORT)
            client.handleConnectionRequest(
                ConnectionRequestScenario.DeviceOnline(
                    // …and features Signalboy GATT-signature.
                    makeServicesMocks(SignalboyGattAttributes.allServices)
                )
            )

            // Then returns…
            runCurrent()
            assertThat(deferred.getCompleted().address, `is`(expectedAddress))

            // …and client is connected.
            assertThat(client.state, instanceOf(ClientState.Connected::class.java))
        }

    @Test
    fun discover_previousAssociationWithActiveRejectRequest_throwsAndClientIsDisconnected() =
        runTest {
            val expectedAddress = DEVICE_ADDRESS_1

            // Given previous association…
            shadowCompanionDeviceManager.addAssociation(expectedAddress)
            // …for a device that registered a Reject-Request (that is still active,
            // i.e. did not expire yet).
            addressFilter = { address -> address != expectedAddress }

            // When awaiting `discover`.
            val deferred = async(SupervisorJob()) {
                companionDeviceDiscoveryStrategy.discover(client)
            }

            // Then throws UserInteractionRequiredException…
            runCurrent()
            assertThat(
                deferred.getCompletionExceptionOrNull(),
                `is`(instanceOf(UserInteractionRequiredException::class.java))
            )

            // …and client is disconnected.
            assertThat(client.state, instanceOf(ClientState.Disconnected::class.java))
        }

    @Test
    fun discover_previousAssociationAndClientIsAlreadyConnected_throwsAndClientStateIsUnchanged() =
        runTest {
            val expectedAddress = DEVICE_ADDRESS_1

            // Given previous association…
            shadowCompanionDeviceManager.addAssociation(expectedAddress)

            // …and already connected client.
            val connectedState = ClientState.Connected(
                emptyList(),
                Session(
                    device = bluetoothAdapter.getRemoteDevice(expectedAddress),
                    gattClient = mock {}
                )
            )
            val client = mock<Client> {
                on { state } doReturn connectedState
                on { latestState } doReturn MutableStateFlow(connectedState).asStateFlow()
            }

            // When awaiting `discover`…
            val deferred = async(SupervisorJob()) {
                companionDeviceDiscoveryStrategy.discover(client)
            }

            // …after short period.
            advanceTimeBy(DELAY_SHORT)

            // Then throws…
            assertThat(
                deferred.getCompletionExceptionOrNull(),
                `is`(instanceOf(IllegalArgumentException::class.java))
            )

            // …and client's state is unchanged.
            assertThat(client.state, `is`(connectedState))
        }

    @Test
    fun discover_previousAssociationAndDeviceIsReachableAndNotCompatible_throwsAndClientIsDisconnected() =
        runTest {
            val expectedAddress = DEVICE_ADDRESS_1

            // Given previous association.
            shadowCompanionDeviceManager.addAssociation(expectedAddress)

            // When awaiting `discover`…
            val deferred = async(SupervisorJob()) {
                companionDeviceDiscoveryStrategy.discover(client)
            }

            // …and when device is reachable…
            advanceTimeBy(DELAY_SHORT)
            client.handleConnectionRequest(
                ConnectionRequestScenario.DeviceOnline(
                    // …and does **not** feature Signalboy GATT-signature.
                    makeServicesMocks(emptyMap())
                )
            )

            // Then throws UserInteractionRequiredException…
            runCurrent()
            assertThat(
                deferred.getCompletionExceptionOrNull(),
                `is`(instanceOf(UserInteractionRequiredException::class.java))
            )

            // …and client is disconnected.
            assertThat(client.state, instanceOf(ClientState.Disconnected::class.java))
        }

    @Test
    fun discover_previousAssociationAndDeviceIsNotReachable_throwsAndClientIsDisconnected() =
        runTest {
            val deviceAddress = DEVICE_ADDRESS_1

            // Given previous association.
            shadowCompanionDeviceManager.addAssociation(DEVICE_ADDRESS_1)

            // When awaiting `discover`…
            val deferred = async(SupervisorJob()) {
                companionDeviceDiscoveryStrategy.discover(client)
            }

            // …and when that device is **not** reachable.
            advanceTimeBy(DELAY_SHORT)
            client.handleConnectionRequest(ConnectionRequestScenario.DeviceOffline)

            // Then throws UserInteractionRequiredException…
            runCurrent()
            assertThat(
                deferred.getCompletionExceptionOrNull(),
                `is`(instanceOf(UserInteractionRequiredException::class.java))
            )

            // …and client is disconnected.
            assertThat(client.state, instanceOf(ClientState.Disconnected::class.java))
        }

    @Test
    fun discover_noAssociations_throwsAndClientIsDisconnected() =
        runTest {
            // Given no previous associations.

            // When awaiting `discover`.
            val deferred =
                async(SupervisorJob()) { companionDeviceDiscoveryStrategy.discover(client) }

            // Then throws UserInteractionRequiredException…
            runCurrent()
            assertThat(
                deferred.getCompletionExceptionOrNull(),
                `is`(instanceOf(UserInteractionRequiredException::class.java))
            )

            // …and client is disconnected.
            assertThat(client.state, instanceOf(ClientState.Disconnected::class.java))
        }

    @Test
    fun discoverWithUI_noAssociationsAndDeviceFoundAndDialogCancelled_throwsAndClientIsDisconnected() =
        runTest {
            // Given no previous associations.

            // When awaiting `discover`…
            val deferred = async(SupervisorJob()) {
                companionDeviceDiscoveryStrategy.discover(client, activityResultProxy)
            }

            // and when CompanionDeviceManager discovered a device…
            advanceTimeBy(DELAY_SHORT)
            createDummyChooserLauncher(context).let { chooserLauncher ->
                shadowCompanionDeviceManager.lastAssociationCallback.onDeviceFound(chooserLauncher)
                assertThat(activityResultProxy.lastLaunchedIntentSender, `is`(chooserLauncher))
            }

            // …and when user cancels the device-selection activity.
            advanceTimeBy(DELAY_LONG)
            activityResultProxy.cancel()

            // Then throws UserCancellationException…
            runCurrent()
            assertThat(
                deferred.getCompletionExceptionOrNull(),
                `is`(instanceOf(UserCancellationException::class.java))
            )

            // …and client is disconnected.
            assertThat(client.state, instanceOf(ClientState.Disconnected::class.java))
        }

    @Test
    fun discoverWithUI_noAssociationsAndDeviceFoundAndDeviceSelectedAndDeviceIsReachable_returnsAndClientIsConnected() =
        runTest {
            val expectedAddress = DEVICE_ADDRESS_1

            // Given no previous associations.

            // When awaiting `discover`…
            val deferred = async(SupervisorJob()) {
                companionDeviceDiscoveryStrategy.discover(client, activityResultProxy)
            }

            // …and when CompanionDeviceManager discovered a device…
            advanceTimeBy(DELAY_SHORT)
            createDummyChooserLauncher(context).let { chooserLauncher ->
                shadowCompanionDeviceManager.lastAssociationCallback.onDeviceFound(chooserLauncher)
                assertThat(
                    "launched IntentSender",
                    activityResultProxy.lastLaunchedIntentSender,
                    `is`(chooserLauncher)
                )
            }

            // …and when user chooses that device…
            advanceTimeBy(DELAY_LONG)
            activityResultProxy.chooseDevice(createScanResult(createBluetoothDevice(expectedAddress)))

            // …and when device is reachable…
            advanceTimeBy(DELAY_SHORT)
            client.handleConnectionRequest(
                ConnectionRequestScenario.DeviceOnline(
                    // …and features Signalboy GATT-signature.
                    makeServicesMocks(SignalboyGattAttributes.allServices)
                )
            )

            // Then returns…
            runCurrent()
            assertThat(deferred.getCompleted().address, `is`(expectedAddress))

            // and client is connected.
            assertThat(client.state, instanceOf(ClientState.Connected::class.java))
        }

    @Test
    fun discoverWithUI_noAssociationsAndDeviceFoundAndDeviceSelectedAndDeviceIsNotReachable_throwsAndClientIsDisconnected() =
        runTest {
            val expectedAddress = DEVICE_ADDRESS_1

            // Given no previous associations.

            // When awaiting `discover`…
            val deferred = async(SupervisorJob()) {
                companionDeviceDiscoveryStrategy.discover(client, activityResultProxy)
            }

            // …and when CompanionDeviceManager discovered a device…
            advanceTimeBy(DELAY_SHORT)
            createDummyChooserLauncher(context).let { chooserLauncher ->
                shadowCompanionDeviceManager.lastAssociationCallback.onDeviceFound(chooserLauncher)
                assertThat(
                    "launched IntentSender",
                    activityResultProxy.lastLaunchedIntentSender,
                    `is`(chooserLauncher)
                )
            }

            // …and when user chooses that device…
            advanceTimeBy(DELAY_LONG)
            activityResultProxy.chooseDevice(createScanResult(createBluetoothDevice(expectedAddress)))

            // …and when that device is **not** reachable
            advanceTimeBy(DELAY_SHORT)
            client.handleConnectionRequest(ConnectionRequestScenario.DeviceOffline)

            // …and when another discovery attempt will **not** yield any result.
            advanceTimeBy(DELAY_LONG)

            // Then throws AssociationDiscoveryTimeoutException…
            assertThat(
                deferred.getCompletionExceptionOrNull(),
                `is`(instanceOf(AssociationDiscoveryTimeoutException::class.java))
            )

            // …and client is disconnected.
            assertThat(client.state, instanceOf(ClientState.Disconnected::class.java))
        }

    private fun makeOriginAwareCompanionDeviceManagerStub(value: CompanionDeviceManager) =
        mock<OriginAwareCompanionDeviceManager> {
            on { wrappedValue } doReturn value
            on { ensureCanAssociate() } doAnswer {} // Stub will **never** throw
        }

    private fun createDummyChooserLauncher(context: Context) =
        PendingIntent.getActivity(context, 0, Intent("ACTION_DUMMY"), 0)
            .intentSender

    private fun createBluetoothDevice(address: String) = bluetoothAdapter.getRemoteDevice(address)
    private fun createScanResult(device: BluetoothDevice): ScanResult {
        val eventType = 0xAAAA
        val primaryPhy = 0xAAAB
        val secondaryPhy = 0xAABA
        val advertisingSid = 0xAABB
        val txPower = 0xABAA
        val rssi = 0xABAB
        val periodicAdvertisingInterval = 0xABBA
        val timestampNanos: Long = 0xABBB
        return ScanResult(
            device, eventType, primaryPhy, secondaryPhy,
            advertisingSid, txPower, rssi, periodicAdvertisingInterval, null, timestampNanos
        )
    }

    private fun makeServicesMocks(servicesMap: Map<UUID, List<UUID>>): List<BluetoothGattService> =
        servicesMap
            // Convert characteristic-uuids to BluetoothGattCharacteristic-mocks
            .mapValues { (_, characteristicUuids) ->
                characteristicUuids.map { characteristicUuid ->
                    mock<BluetoothGattCharacteristic> {
                        on { uuid } doReturn characteristicUuid
                    }
                }
            }
            .flatMap { (serviceUuid, characteristics) ->
                listOf(
                    mock {
                        on { uuid } doReturn serviceUuid
                        on { this.characteristics } doReturn characteristics
                    }
                )
            }


    companion object {
        private const val DEVICE_ADDRESS_1 = "00:11:22:AA:BB:CC"
        private const val DEVICE_ADDRESS_2 = "11:22:33:BB:CC:DD"

        private const val DELAY_SHORT = 150L
        private const val DELAY_LONG = 31_000L
    }
}
