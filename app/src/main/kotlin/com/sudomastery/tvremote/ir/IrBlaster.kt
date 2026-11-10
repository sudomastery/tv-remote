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

    val available: Boolean = runCatching {
        manager?.hasIrEmitter() == true && supportsCarrier(TclIr.CARRIER_HZ)
    }.getOrDefault(false)

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

    private fun supportsCarrier(hz: Int): Boolean {
        val ranges = manager?.carrierFrequencies ?: return true // unknown: try anyway
        return ranges.isEmpty() || ranges.any { hz in it.minFrequency..it.maxFrequency }
    }
}
