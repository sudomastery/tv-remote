package com.sudomastery.tvremote

import android.annotation.SuppressLint
import android.app.Application
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sudomastery.atv.PairingSession
import com.sudomastery.atv.WrongCodeException
import com.sudomastery.atv.adb.CleanupResult
import com.sudomastery.atv.adb.TvAdb
import com.sudomastery.atv.adb.TvMemory
import com.sudomastery.atv.proto.RemoteProto.RemoteDirection
import com.sudomastery.tvremote.data.Discovery
import com.sudomastery.tvremote.data.FoundTv
import com.sudomastery.tvremote.data.SavedTv
import com.sudomastery.tvremote.notification.ControlsService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

sealed interface PairingState {
    data object Idle : PairingState
    data class Connecting(val tv: FoundTv) : PairingState
    data class EnterCode(val tv: FoundTv, val error: String? = null, val verifying: Boolean = false) : PairingState
    data class Failed(val tv: FoundTv, val message: String) : PairingState
}

/** Screen state on top of the app-wide [RemoteController]: pairing, voice, settings. */
class RemoteViewModel(app: Application) : AndroidViewModel(app) {
    private val controller = app.controller
    private val store = controller.store
    private val discovery = Discovery(app)

    val irAvailable: Boolean = controller.irAvailable
    val irMode: StateFlow<Boolean> = controller.irMode
    val tvs: StateFlow<List<SavedTv>> = controller.tvs
    val current: StateFlow<SavedTv?> = controller.current
    val tvState: StateFlow<TvState> = controller.tvState
    val powerMenuOpening: StateFlow<Boolean> = controller.powerMenuOpening
    val notice: StateFlow<String?> = controller.notice
    val powerUsesIr: StateFlow<Boolean> = controller.powerUsesIr
    val irDescription: String get() = controller.irDescription

    private val _pairing = MutableStateFlow<PairingState>(PairingState.Idle)
    val pairing: StateFlow<PairingState> = _pairing.asStateFlow()
    private var pairingSession: PairingSession? = null

    private val _haptics = MutableStateFlow(store.haptics)
    val haptics: StateFlow<Boolean> = _haptics.asStateFlow()

    private val _notificationControls = MutableStateFlow(store.notificationControls)
    val notificationControls: StateFlow<Boolean> = _notificationControls.asStateFlow()

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()
    private var voiceJob: Job? = null

    fun scan(): Flow<List<FoundTv>> = discovery.scan()

    // region lifecycle

    fun onForeground() {
        controller.hold(HOLDER)
        if (store.notificationControls) {
            // Notifications may have been blocked in system settings since the user turned this
            // on; a service with an invisible notification would only keep the app running.
            if (ControlsService.canShow(getApplication())) ControlsService.start(getApplication()) else setNotificationControls(false)
        }
    }

    /** Keep the link warm for a short while so quick app switches reconnect instantly. */
    fun onBackground() {
        stopVoice()
        controller.release(HOLDER, graceMs = 30_000)
    }

    override fun onCleared() {
        pairingSession?.close()
    }

    // endregion

    // region keys and settings

    fun press(key: RemoteKey, direction: RemoteDirection = RemoteDirection.SHORT) = controller.press(key, direction)
    fun openPowerMenu() = controller.openPowerMenu()
    fun launch(app: AppShortcut) = controller.launch(app)
    fun sendText(text: String) = controller.sendText(text)

    fun setIrMode(enabled: Boolean) {
        controller.setIrMode(enabled)
        // Only once the switch actually happened (it can be refused mid power hold).
        if (irMode.value) stopVoice()
    }

    fun setHaptics(enabled: Boolean) {
        store.haptics = enabled
        _haptics.value = enabled
    }

    /** Shows or hides the remote controls notification. The UI asks for permission first. */
    fun setNotificationControls(enabled: Boolean) {
        if (enabled && !ControlsService.canShow(getApplication())) {
            controller.showNotice("Notifications for TV Remote are off in system settings")
            return
        }
        store.notificationControls = enabled
        _notificationControls.value = enabled
        if (enabled) ControlsService.start(getApplication()) else ControlsService.stop(getApplication())
    }

    // endregion

    // region advanced (ADB)

    private val _advanced = MutableStateFlow(AdvancedState())
    val advanced: StateFlow<AdvancedState> = _advanced.asStateFlow()

    private fun adb(): TvAdb? = current.value?.let { TvAdb(it.host, File(getApplication<Application>().filesDir, "adb")) }

    fun refreshMemory() {
        val adb = adb() ?: return
        if (_advanced.value.busy != null) return
        viewModelScope.launch {
            runCatching { adb.memory() }.getOrNull()?.let { _advanced.value = _advanced.value.copy(memory = it) }
        }
    }

    fun runAction(action: TvAction) {
        val adb = adb() ?: return
        if (_advanced.value.busy != null) return
        _advanced.value = _advanced.value.copy(busy = action, error = false, message = "Working… if the TV asks to allow debugging, pick Always allow")
        viewModelScope.launch {
            _advanced.value = try {
                if (action == TvAction.RESTART) {
                    // The TV drops the connection as it goes down, so an error here is expected.
                    runCatching { adb.reboot() }
                    _advanced.value.copy(busy = null, message = "Restarting the TV…")
                } else {
                    val result = when (action) {
                        TvAction.CLOSE_CURRENT -> adb.closeCurrent()
                        TvAction.CLOSE_OTHERS -> adb.closeOthers()
                        else -> adb.closeAll()
                    }
                    AdvancedState(memory = result.after ?: _advanced.value.memory, message = result.summary())
                }
            } catch (e: Exception) {
                _advanced.value.copy(busy = null, error = true, message = e.adbMessage())
            }
        }
    }

    private fun CleanupResult.summary(): String {
        val names = closed.map { pkg -> AppShortcut.entries.firstOrNull { it.packageName == pkg }?.label ?: pkg.substringAfterLast('.').replaceFirstChar(Char::uppercase) }
        val freed = (freedKb / 1024).takeIf { it > 0 }?.let { ", freed $it MB" } ?: ""
        return when {
            names.isEmpty() -> "Nothing to close$freed"
            names.size <= 3 -> "Closed ${names.joinToString(", ")}$freed"
            else -> "Closed ${names.size} apps$freed"
        }
    }

    // endregion

    // region devices and pairing

    fun select(tv: SavedTv) = controller.select(tv)
    fun forget(tv: SavedTv) = controller.forget(tv)

    /** Called when the user picks a TV. Paired ones connect directly, new ones start pairing. */
    fun choose(found: FoundTv) {
        val saved = tvs.value.firstOrNull { it.host == found.host }
        if (saved?.pinSha256 != null) select(saved) else startPairing(found)
    }

    fun repairCurrent() {
        current.value?.let { startPairing(FoundTv(it.name, it.host)) }
    }

    fun startPairing(tv: FoundTv) {
        pairingSession?.close()
        _pairing.value = PairingState.Connecting(tv)
        viewModelScope.launch {
            controller.identityReady.first { it }
            val session = PairingSession(tv.host, controller.identity!!, "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            pairingSession = session
            _pairing.value = try {
                session.start()
                PairingState.EnterCode(tv)
            } catch (e: Exception) {
                session.close()
                PairingState.Failed(tv, e.friendlyMessage())
            }
        }
    }

    fun submitCode(code: String) {
        val state = _pairing.value as? PairingState.EnterCode ?: return
        val session = pairingSession ?: return
        _pairing.value = state.copy(verifying = true, error = null)
        viewModelScope.launch {
            try {
                val pin = session.finish(code)
                session.close()
                pairingSession = null
                _pairing.value = PairingState.Idle
                controller.addPaired(SavedTv(state.tv.name, state.tv.host, pin))
            } catch (e: WrongCodeException) {
                _pairing.value = state.copy(verifying = false, error = e.message)
            } catch (e: IllegalArgumentException) {
                _pairing.value = state.copy(verifying = false, error = e.message)
            } catch (e: Exception) {
                session.close()
                _pairing.value = PairingState.Failed(state.tv, e.friendlyMessage())
            }
        }
    }

    fun cancelPairing() {
        pairingSession?.close()
        pairingSession = null
        _pairing.value = PairingState.Idle
    }

    // endregion

    // region voice

    /** Hold-to-talk. Streams 8 kHz mono PCM to the TV's voice search. */
    @SuppressLint("MissingPermission") // checked by the UI before calling
    fun startVoice() {
        val r = controller.currentRemote ?: return
        if (!tvState.value.voiceAvailable) {
            r.sendKey(RemoteKey.SEARCH.atv)
            return
        }
        if (voiceJob?.isActive == true) return
        _listening.value = true
        voiceJob = viewModelScope.launch(Dispatchers.IO) {
            val recorder = openRecorder()
            val session = if (recorder != null) r.startVoice() else null
            if (recorder == null || session == null) {
                recorder?.first?.release()
                _listening.value = false
                return@launch
            }
            val (record, decimate) = recorder
            // About 0.25 s of audio per read keeps latency low; AtvRemote pads chunks to the TV's minimum.
            val buffer = ByteArray(if (decimate) 8192 else 4096)
            try {
                record.startRecording()
                while (isActive) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n <= 0) continue
                    r.sendVoice(session, if (decimate) downsample(buffer, n) else buffer.copyOf(n))
                }
            } finally {
                runCatching { record.stop() }
                record.release()
                r.endVoice(session)
                _listening.value = false
            }
        }
    }

    fun stopVoice() {
        voiceJob?.cancel()
        voiceJob = null
    }

    /** 8 kHz if the mic supports it, otherwise 16 kHz with every other sample dropped. */
    @SuppressLint("MissingPermission")
    private fun openRecorder(): Pair<AudioRecord, Boolean>? {
        for ((rate, decimate) in listOf(8_000 to false, 16_000 to true)) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val rec = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 16_384))
            }.getOrNull() ?: continue
            if (rec.state == AudioRecord.STATE_INITIALIZED) return rec to decimate
            rec.release()
        }
        return null
    }

    private fun downsample(pcm: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length / 4 * 2)
        var o = 0
        var i = 0
        while (i + 3 < length) {
            out[o++] = pcm[i]
            out[o++] = pcm[i + 1]
            i += 4
        }
        return out
    }

    // endregion
}

private const val HOLDER = "screen"

enum class TvAction { CLOSE_CURRENT, CLOSE_OTHERS, CLOSE_ALL, RESTART }

data class AdvancedState(
    val busy: TvAction? = null,
    val memory: TvMemory? = null,
    val message: String? = null,
    val error: Boolean = false,
)

private fun Exception.adbMessage(): String = when (this) {
    is java.net.ConnectException, is java.net.NoRouteToHostException ->
        "Can't reach ADB. On the TV: Settings > System > Developer options > Network debugging"
    is java.net.SocketTimeoutException -> "No answer. Accept the Allow debugging prompt on the TV and try again"
    is IllegalStateException -> message ?: "Couldn't do that"
    else -> "Couldn't do that: ${message ?: javaClass.simpleName}"
}

private fun Exception.friendlyMessage(): String = when (this) {
    is java.net.ConnectException, is java.net.SocketTimeoutException, is java.net.NoRouteToHostException ->
        "Can't reach the TV. Check it's on and on the same Wi-Fi."
    else -> message ?: "Pairing failed"
}
