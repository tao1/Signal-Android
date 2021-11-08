package org.thoughtcrime.securesms.webrtc.audio

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.util.safeUnregisterReceiver
import java.util.concurrent.TimeUnit

/**
 * Manages the bluetooth lifecycle with a headset. This class doesn't make any
 * determination on if bluetooth should be used. It determines if a device is connected,
 * reports that to the [SignalAudioManager], and then handles connecting/disconnecting
 * to the device if requested by [SignalAudioManager].
 */
class SignalBluetoothManager(
  private val context: Context,
  private val audioManager: SignalAudioManager,
  private val handler: SignalAudioHandler
) {

  var state: State = State.UNINITIALIZED
    get() {
      handler.assertHandlerThread()
      return field
    }
    private set

  private var bluetoothAdapter: BluetoothAdapter? = null
  private var bluetoothDevice: BluetoothDevice? = null
  private var bluetoothHeadset: BluetoothHeadset? = null
  private var scoConnectionAttempts = 0

  private val androidAudioManager = ApplicationDependencies.getAndroidCallAudioManager()
  private val bluetoothListener = BluetoothServiceListener()
  private var bluetoothReceiver: BluetoothHeadsetBroadcastReceiver? = null

  private val bluetoothTimeout = { onBluetoothTimeout() }

  fun start() {
    handler.assertHandlerThread()

    Log.d(TAG, "start(): $state")

    if (state != State.UNINITIALIZED) {
      Log.w(TAG, "Invalid starting state")
      return
    }

    bluetoothHeadset = null
    bluetoothDevice = null
    scoConnectionAttempts = 0

    bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
    if (bluetoothAdapter == null) {
      Log.i(TAG, "Device does not support Bluetooth")
      return
    }

    if (!androidAudioManager.isBluetoothScoAvailableOffCall) {
      Log.w(TAG, "Bluetooth SCO audio is not available off call")
      return
    }

    if (bluetoothAdapter?.getProfileProxy(context, bluetoothListener, BluetoothProfile.HEADSET) != true) {
      Log.e(TAG, "BluetoothAdapter.getProfileProxy(HEADSET) failed")
      return
    }

    val bluetoothHeadsetFilter = IntentFilter().apply {
      addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
      addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
    }

    bluetoothReceiver = BluetoothHeadsetBroadcastReceiver()
    context.registerReceiver(bluetoothReceiver, bluetoothHeadsetFilter)

    Log.i(TAG, "Headset profile state: ${bluetoothAdapter?.getProfileConnectionState(BluetoothProfile.HEADSET)?.toStateString()}")
    Log.i(TAG, "Bluetooth proxy for headset profile has started")
    state = State.UNAVAILABLE
  }

  fun stop() {
    handler.assertHandlerThread()

    Log.d(TAG, "stop(): state: $state")

    if (bluetoothAdapter == null) {
      return
    }

    stopScoAudio()

    if (state == State.UNINITIALIZED) {
      return
    }

    context.safeUnregisterReceiver(bluetoothReceiver)
    bluetoothReceiver = null

    cancelTimer()

    if (bluetoothHeadset != null) {
      bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, bluetoothHeadset)
      bluetoothHeadset = null
    }

    bluetoothAdapter = null
    bluetoothDevice = null
    state = State.UNINITIALIZED
  }

  fun startScoAudio(): Boolean {
    handler.assertHandlerThread()

    Log.i(TAG, "startScoAudio(): $state attempts: $scoConnectionAttempts")

    if (scoConnectionAttempts >= MAX_CONNECTION_ATTEMPTS) {
      Log.w(TAG, "SCO connection attempts maxed out")
      return false
    }

    if (state != State.AVAILABLE) {
      Log.w(TAG, "SCO connection failed as no headset available")
      return false
    }

    state = State.CONNECTING
    androidAudioManager.startBluetoothSco()
    androidAudioManager.isBluetoothScoOn = true
    scoConnectionAttempts++
    startTimer()

    return true
  }

  fun stopScoAudio() {
    handler.assertHandlerThread()

    Log.i(TAG, "stopScoAudio(): $state")

    if (state != State.CONNECTING && state != State.CONNECTED) {
      return
    }

    cancelTimer()
    androidAudioManager.stopBluetoothSco()
    androidAudioManager.isBluetoothScoOn = false
    state = State.DISCONNECTING
  }

  fun updateDevice() {
    handler.assertHandlerThread()

    Log.d(TAG, "updateDevice(): state: $state")

    if (state == State.UNINITIALIZED || bluetoothHeadset == null) {
      return
    }

    try {
      val devices: List<BluetoothDevice>? = bluetoothHeadset?.connectedDevices
      if (devices == null || devices.isEmpty()) {
        bluetoothDevice = null
        state = State.UNAVAILABLE
        Log.i(TAG, "No connected bluetooth headset")
      } else {
        bluetoothDevice = devices[0]
        state = State.AVAILABLE
        Log.i(TAG, "Connected bluetooth headset. headsetState: ${bluetoothHeadset?.getConnectionState(bluetoothDevice)?.toStateString()} scoAudio: ${bluetoothHeadset?.isAudioConnected(bluetoothDevice)}")
      }
    } catch (e: SecurityException) {
      bluetoothDevice = null
      state = State.UNAVAILABLE
      Log.w(TAG, "Cannot list connected devices", e)
    }
  }

  private fun updateAudioDeviceState() {
    audioManager.updateAudioDeviceState()
  }

  private fun startTimer() {
    handler.postDelayed(bluetoothTimeout, SCO_TIMEOUT)
  }

  private fun cancelTimer() {
    handler.removeCallbacks(bluetoothTimeout)
  }

  private fun onBluetoothTimeout() {
    Log.i(TAG, "onBluetoothTimeout: state: $state bluetoothHeadset: $bluetoothHeadset")

    if (state == State.UNINITIALIZED || bluetoothHeadset == null || state != State.CONNECTING) {
      return
    }

    var scoConnected = false
    val devices: List<BluetoothDevice>? = bluetoothHeadset?.connectedDevices

    if (devices != null && devices.isNotEmpty()) {
      bluetoothDevice = devices[0]
      if (bluetoothHeadset?.isAudioConnected(bluetoothDevice) == true) {
        Log.d(TAG, "Connected with $bluetoothDevice")
        scoConnected = true
      } else {
        Log.d(TAG, "Not connected with $bluetoothDevice")
      }
    }

    if (scoConnected) {
      Log.i(TAG, "Device actually connected and not timed out")
      state = State.CONNECTED
      scoConnectionAttempts = 0
    } else {
      Log.w(TAG, "Failed to connect after timeout")
      stopScoAudio()
    }

    updateAudioDeviceState()
  }

  private fun onServiceConnected(proxy: BluetoothHeadset?) {
    bluetoothHeadset = proxy
    updateAudioDeviceState()
  }

  private fun onServiceDisconnected() {
    stopScoAudio()
    bluetoothHeadset = null
    bluetoothDevice = null
    state = State.UNAVAILABLE
    updateAudioDeviceState()
  }

  private fun onHeadsetConnectionStateChanged(connectionState: Int) {
    Log.i(TAG, "onHeadsetConnectionStateChanged: state: $state connectionState: ${connectionState.toStateString()}")

    when (connectionState) {
      BluetoothHeadset.STATE_CONNECTED -> {
        scoConnectionAttempts = 0
        updateAudioDeviceState()
      }
      BluetoothHeadset.STATE_DISCONNECTED -> {
        stopScoAudio()
        updateAudioDeviceState()
      }
    }
  }

  private fun onAudioStateChanged(audioState: Int, isInitialStateChange: Boolean) {
    Log.i(TAG, "onAudioStateChanged: state: $state audioState: ${audioState.toStateString()} initialSticky: $isInitialStateChange")

    if (audioState == BluetoothHeadset.STATE_AUDIO_CONNECTED) {
      cancelTimer()
      if (state === State.CONNECTING) {
        Log.d(TAG, "Bluetooth audio SCO is now connected")
        state = State.CONNECTED
        scoConnectionAttempts = 0
        updateAudioDeviceState()
      } else {
        Log.w(TAG, "Unexpected state ${audioState.toStateString()}")
      }
    } else if (audioState == BluetoothHeadset.STATE_AUDIO_CONNECTING) {
      Log.d(TAG, "Bluetooth audio SCO is now connecting...")
    } else if (audioState == BluetoothHeadset.STATE_AUDIO_DISCONNECTED) {
      Log.d(TAG, "Bluetooth audio SCO is now disconnected")
      if (isInitialStateChange) {
        Log.d(TAG, "Ignore ${audioState.toStateString()} initial sticky broadcast.")
        return
      }
      updateAudioDeviceState()
    }
  }

  private inner class BluetoothServiceListener : BluetoothProfile.ServiceListener {
    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
      if (profile == BluetoothProfile.HEADSET) {
        handler.post {
          if (state != State.UNINITIALIZED) {
            onServiceConnected(proxy as? BluetoothHeadset)
          }
        }
      }
    }

    override fun onServiceDisconnected(profile: Int) {
      if (profile == BluetoothProfile.HEADSET) {
        handler.post {
          if (state != State.UNINITIALIZED) {
            onServiceDisconnected()
          }
        }
      }
    }
  }

  private inner class BluetoothHeadsetBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (intent.action == BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED) {
        val connectionState: Int = intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, BluetoothHeadset.STATE_DISCONNECTED)
        handler.post {
          if (state != State.UNINITIALIZED) {
            onHeadsetConnectionStateChanged(connectionState)
          }
        }
      } else if (intent.action == BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED) {
        val connectionState: Int = intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, BluetoothHeadset.STATE_AUDIO_DISCONNECTED)
        handler.post {
          if (state != State.UNINITIALIZED) {
            onAudioStateChanged(connectionState, isInitialStickyBroadcast)
          }
        }
      }
    }
  }

  enum class State {
    UNINITIALIZED,
    UNAVAILABLE,
    AVAILABLE,
    DISCONNECTING,
    CONNECTING,
    CONNECTED,
    ERROR;

    fun shouldUpdate(): Boolean {
      return this == AVAILABLE || this == UNAVAILABLE || this == DISCONNECTING
    }

    fun hasDevice(): Boolean {
      return this == CONNECTED || this == CONNECTING || this == AVAILABLE
    }
  }

  companion object {
    private val TAG = Log.tag(SignalBluetoothManager::class.java)
    private val SCO_TIMEOUT = TimeUnit.SECONDS.toMillis(4)
    private const val MAX_CONNECTION_ATTEMPTS = 2
  }
}

private fun Int.toStateString(): String {
  return when (this) {
    BluetoothAdapter.STATE_DISCONNECTED -> "DISCONNECTED"
    BluetoothAdapter.STATE_CONNECTED -> "CONNECTED"
    BluetoothAdapter.STATE_CONNECTING -> "CONNECTING"
    BluetoothAdapter.STATE_DISCONNECTING -> "DISCONNECTING"
    BluetoothAdapter.STATE_OFF -> "OFF"
    BluetoothAdapter.STATE_ON -> "ON"
    BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
    BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
    else -> "UNKNOWN"
  }
}
