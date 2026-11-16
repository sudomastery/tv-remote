package com.sudomastery.tvremote.ir

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.ConsumerIrManager
import java.util.concurrent.Executors

/** The phone's IR emitter (Xiaomi and some other phones have one on top). */
class IrBlaster(context: Context) {
    private val manager: ConsumerIrManager? =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_CONSUMER_IR)) {
            context.getSystemService(ConsumerIrManager::class.java)
        } else {
            null
        }

    /**
     * True when the phone reports an IR emitter. The carrier range is deliberately not part of
     * this check: some phones report ranges oddly, and a strict check would silently turn IR off.
     */
    val available: Boolean = runCatching { manager?.hasIrEmitter() == true }.getOrDefault(false)

    /** Human readable emitter details for the settings screen. */
    val description: String = when {
        !available -> "No IR blaster found on this phone"
        else -> {
            val ranges = runCatching { manager?.carrierFrequencies }.getOrNull().orEmpty()
            val khz = ranges.joinToString(", ") { "${it.minFrequency / 1000}-${it.maxFrequency / 1000} kHz" }
            val fits = ranges.isEmpty() || ranges.any { TclIr.CARRIER_HZ in it.minFrequency..it.maxFrequency }
            buildString {
                append("IR blaster found")
                if (khz.isNotEmpty()) append(" ($khz)")
                if (!fits) append(". TCL uses 38 kHz, outside its reported range")
            }
        }
    }

    // transmit() blocks until the signal has been sent, so keep it off the main thread. One
    // thread keeps presses in order.
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "ir").apply { isDaemon = true } }

    fun send(tvKey: Int): Boolean = sendPattern(TclIr.pattern(tvKey))

    fun sendPattern(pattern: IntArray): Boolean {
        val m = manager ?: return false
        if (!available) return false
        worker.execute { runCatching { m.transmit(TclIr.CARRIER_HZ, pattern) } }
        return true
    }

}
