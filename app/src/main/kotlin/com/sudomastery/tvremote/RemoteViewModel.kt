package com.sudomastery.tvremote

import android.annotation.SuppressLint
import android.app.Application
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sudomastery.atv.AtvRemote
import com.sudomastery.atv.ClientIdentity
import com.sudomastery.atv.ConnectionStatus
import com.sudomastery.atv.PairingSession
import com.sudomastery.atv.Volume
import com.sudomastery.atv.WrongCodeException
import com.sudomastery.atv.proto.RemoteProto.RemoteDirection
import com.sudomastery.tvremote.data.Discovery
import com.sudomastery.tvremote.data.FoundTv
import com.sudomastery.tvremote.data.SavedTv
import com.sudomastery.tvremote.data.Store
import com.sudomastery.tvremote.ir.IrBlaster
import com.sudomastery.tvremote.ir.TclIr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TvState(
    val status: ConnectionStatus = ConnectionStatus.Disconnected,
    val volume: Volume? = null,
    val isOn: Boolean? = null,
    val voiceAvailable: Boolean = false,
)

sealed interface PairingState {
    data object Idle : PairingState
    data class Connecting(val tv: FoundTv) : PairingState
    data class EnterCode(val tv: FoundTv, val error: String? = null, val verifying: Boolean = false) : PairingState
    data class Failed(val tv: FoundTv, val message: String) : PairingState
}

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val discovery = Discovery(app)
    private val ir = IrBlaster(app)

    /** Whether this phone has an IR emitter at all. */
    val irAvailable: Boolean = ir.available

    private val _irMode = MutableStateFlow(store.irMode && ir.available)
    /** Send everything over infrared instead of Wi-Fi. */
    val irMode: StateFlow<Boolean> = _irMode.asStateFlow()

    private var identity: ClientIdentity? = null
    private val identityReady = MutableStateFlow(false)

    private val _tvs = MutableStateFlow(store.tvs)
    val tvs: StateFlow<List<SavedTv>> = _tvs.asStateFlow()

    private val _current = MutableStateFlow(_tvs.value.firstOrNull { it.host == store.selectedHost } ?: _tvs.value.firstOrNull())
    val current: StateFlow<SavedTv?> = _current.asStateFlow()

    private val remote = MutableStateFlow<AtvRemote?>(null)

    val tvState: StateFlow<TvState> = remote.flatMapLatest { r ->
        // Before the identity loads there is no remote yet; show "Connecting" rather than "Not connected".
        if (r == null) flowOf(TvState(if (_current.value != null && !_irMode.value) ConnectionStatus.Connecting else ConnectionStatus.Disconnected)) else combine(r.status, r.volume, r.isOn, r.voiceAvailable) { s, v, on, voice ->
            TvState(s, v, on, voice)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TvState())

    private val _pairing = MutableStateFlow<PairingState>(PairingState.Idle)
    val pairing: StateFlow<PairingState> = _pairing.asStateFlow()
    private var pairingSession: PairingSession? = null

    private val _haptics = MutableStateFlow(store.haptics)
    val haptics: StateFlow<Boolean> = _haptics.asStateFlow()

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()
    private var voiceJob: Job? = null

    private var foreground = false
    private var backgroundJob: Job? = null

    init {
        viewModelScope.launch {
            identity = withContext(Dispatchers.Default) {
                store.loadIdentity() ?: ClientIdentity.generate("TV Remote").also(store::saveIdentity)
            }
            identityReady.value = true
            _current.value?.let { openRemote(it) }
        }
    }

    fun scan(): Flow<List<FoundTv>> = discovery.scan()

    // region lifecycle

    fun onForeground() {
        foreground = true
        backgroundJob?.cancel()
        if (!_irMode.value) remote.value?.connect()
    }

    /** Keep the link warm for a short while so quick app switches reconnect instantly. */
    fun onBackground() {
        foreground = false
        stopVoice()
        backgroundJob?.cancel()
        backgroundJob = viewModelScope.launch {
            delay(30_000)
            remote.value?.disconnect()
        }
    }

    override fun onCleared() {
        remote.value?.disconnect()
        pairingSession?.close()
    }

    // endregion

    // region keys

    /** Returns false if the command could not be delivered. */
    fun press(key: RemoteKey, direction: RemoteDirection = RemoteDirection.SHORT): Boolean {
        // A power tap in the middle of a hold would arrive as a normal press and sleep the TV.
        if (key == RemoteKey.POWER && powerHoldActive) return false
        if (_irMode.value) {
            // IR has no key-up, so a held OK is just a press.
            if (direction == RemoteDirection.END_LONG) return true
            return TclIr.keys[key]?.let(ir::send) ?: false
        }
        val r = remote.value
        // A TV in deep standby drops off Wi-Fi; IR can still switch it on.
        if (key == RemoteKey.POWER && r?.isConnected != true && ir.available) {
            r?.connect()
            return ir.send(TclIr.keys.getValue(RemoteKey.POWER))
        }
        return r?.sendKey(key.atv, direction) ?: false
    }

    private val _powerMenuOpening = MutableStateFlow(false)
    /** True while the app is holding the power key to open the TV's shut down dialog. */
    val powerMenuOpening: StateFlow<Boolean> = _powerMenuOpening.asStateFlow()
    private var powerHoldJob: Job? = null
    /** The connection holding the power key down over Wi-Fi, until it sends the key-up. */
    private var heldRemote: AtvRemote? = null
    private val powerHoldActive: Boolean get() = powerHoldJob?.isActive == true

    /**
     * Holds the power key like a physical remote, which opens the TV's "Power off" dialog
     * (full shut down) instead of the normal sleep. Returns false if it couldn't start.
     *
     * The hold has a fixed length no matter when the finger lifts: on the TCL a 1.5 s hold opens
     * the dialog, while 0.8 s counts as a normal press and puts the TV to sleep.
     */
    fun openPowerMenu(): Boolean {
        if (powerHoldActive) return true
        if (_irMode.value) {
            val held = TclIr.heldPattern(TclIr.keys.getValue(RemoteKey.POWER))
            if (!ir.sendPattern(held)) {
                showNotice("The IR blaster isn't available")
                return false
            }
            powerHoldJob = viewModelScope.launch {
                _powerMenuOpening.value = true
                try {
                    delay(TclIr.durationMs(held))
                } finally {
                    _powerMenuOpening.value = false
                }
            }
            return true
        }
        // No IR fallback here, unlike a power tap: if Wi-Fi can't reach the TV it is most likely
        // already off, and holding power over IR would switch it on instead.
        val r = remote.value
        if (r == null || !r.isConnected) {
            r?.connect()
            showNotice(if (ir.available) "Not connected. Wait a moment or switch to IR." else "Not connected to the TV yet")
            return false
        }
        if (!r.sendKey(RemoteKey.POWER.atv, RemoteDirection.START_LONG)) {
            showNotice("Couldn't reach the TV")
            return false
        }
        heldRemote = r
        powerHoldJob = viewModelScope.launch {
            _powerMenuOpening.value = true
            try {
                delay(POWER_HOLD_MS)
            } finally {
                releasePowerKey()
                _powerMenuOpening.value = false
            }
        }
        return true
    }

    /** Key-up for a Wi-Fi power hold, only on the connection that pressed it. */
    private fun releasePowerKey() {
        val r = heldRemote ?: return
        heldRemote = null
        // sendKey on a dropped connection would reconnect it, so check first.
        if (r.isConnected) r.sendKey(RemoteKey.POWER.atv, RemoteDirection.END_LONG)
    }

    /**
     * Switching mode or TV mid-hold would cut the hold short, and a short hold sleeps the TV.
     * It only lasts 2 seconds, so ask the user to wait instead.
     */
    private fun blockedByPowerHold(): Boolean {
        if (!powerHoldActive) return false
        showNotice("Opening the power off menu, one moment")
        return true
    }

    private val _notice = MutableStateFlow<String?>(null)
    /** A short message shown in the header for a few seconds. */
    val notice: StateFlow<String?> = _notice.asStateFlow()
    private var noticeJob: Job? = null

    private fun showNotice(text: String) {
        noticeJob?.cancel()
        _notice.value = text
        noticeJob = viewModelScope.launch {
            delay(3_000)
            _notice.value = null
        }
    }

    fun launch(app: AppShortcut): Boolean {
        if (_irMode.value) return TclIr.apps[app]?.let(ir::send) ?: false
        return remote.value?.launchApp(app.link) ?: false
    }

    fun setIrMode(enabled: Boolean) {
        if (enabled == _irMode.value || blockedByPowerHold()) return
        val on = enabled && ir.available
        _irMode.value = on
        store.irMode = on
        if (on) {
            stopVoice()
            remote.value?.disconnect()
        } else if (foreground) {
            remote.value?.connect()
        }
    }

    fun sendText(text: String) = remote.value?.sendText(text) ?: false

    fun setHaptics(enabled: Boolean) {
        store.haptics = enabled
        _haptics.value = enabled
    }

    // endregion

    // region devices and pairing

    fun select(tv: SavedTv) {
        if (_current.value?.host == tv.host && remote.value != null) return
        if (blockedByPowerHold()) return
        _current.value = tv
        store.selectedHost = tv.host
        openRemote(tv)
    }

    fun forget(tv: SavedTv) {
        if (_current.value?.host == tv.host && blockedByPowerHold()) return
        _tvs.value = _tvs.value.filterNot { it.host == tv.host }
        store.tvs = _tvs.value
        if (_current.value?.host == tv.host) {
            remote.value?.disconnect()
            remote.value = null
            _current.value = _tvs.value.firstOrNull()
            store.selectedHost = _current.value?.host
            _current.value?.let { openRemote(it) }
        }
    }

    /** Called when the user picks a TV. Paired ones connect directly, new ones start pairing. */
    fun choose(found: FoundTv) {
        val saved = _tvs.value.firstOrNull { it.host == found.host }
        if (saved?.pinSha256 != null) select(saved) else startPairing(found)
    }

    fun repairCurrent() {
        _current.value?.let { startPairing(FoundTv(it.name, it.host)) }
    }

    fun startPairing(tv: FoundTv) {
        pairingSession?.close()
        _pairing.value = PairingState.Connecting(tv)
        viewModelScope.launch {
            identityReady.first { it }
            val session = PairingSession(tv.host, identity!!, "${Build.MANUFACTURER} ${Build.MODEL}".trim())
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
                val tv = SavedTv(state.tv.name, state.tv.host, pin)
                _tvs.value = _tvs.value.filterNot { it.host == tv.host } + tv
                store.tvs = _tvs.value
                _pairing.value = PairingState.Idle
                powerHoldJob?.join() // let a running power hold finish on the old connection
                remote.value?.disconnect()
                remote.value = null
                select(tv)
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

    private fun openRemote(tv: SavedTv) {
        val id = identity ?: return
        remote.value?.disconnect()
        val r = AtvRemote(tv.host, id, tv.pinSha256, viewModelScope)
        remote.value = r
        if (foreground && !_irMode.value) r.connect()
    }

    // endregion

    // region voice

    /** Hold-to-talk. Streams 8 kHz mono PCM to the TV's voice search. */
    @SuppressLint("MissingPermission") // checked by the UI before calling
    fun startVoice() {
        val r = remote.value ?: return
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

private const val POWER_HOLD_MS = 2_000L

private fun Exception.friendlyMessage(): String = when (this) {
    is java.net.ConnectException, is java.net.SocketTimeoutException, is java.net.NoRouteToHostException ->
        "Can't reach the TV. Check it's on and on the same Wi-Fi."
    else -> message ?: "Pairing failed"
}
