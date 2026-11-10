package com.sudomastery.tvremote

import com.sudomastery.tvremote.ir.TclIr
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TclIrTest {

    /** Port of Flipper Zero's RCA encoder: 24 data bits sent least significant first. */
    private fun flipperPattern(address: Int, command: Int): IntArray {
        var data = address and 0xF
        data = data or (command shl 4)
        data = data or ((address.inv() and 0xF) shl 12)
        data = data or ((command.inv() and 0xFF) shl 16)
        val out = mutableListOf(4000, 4000)
        for (i in 0 until 24) {
            out += 500
            out += if ((data shr i) and 1 == 1) 2000 else 1000
        }
        out += 500
        out += 8000
        return out.toIntArray()
    }

    /** Commands from Flipper-IRDB TVs/TCL/TCL_65C635K.ir and TCL_UnknownModel1.ir (address 0x0F). */
    private val flipperCaptures = mapOf(
        RemoteKey.POWER to 0x54,
        RemoteKey.MUTE to 0xFC,
        RemoteKey.VOLUME_UP to 0xF4,
        RemoteKey.VOLUME_DOWN to 0x74,
        RemoteKey.HOME to 0x10,
        RemoteKey.OK to 0x2F,
        RemoteKey.UP to 0x9A,
        RemoteKey.DOWN to 0x1A,
        RemoteKey.LEFT to 0x6A,
    )

    @Test
    fun `TV keymap codes produce the same signal as Flipper captures of real TCL remotes`() {
        flipperCaptures.forEach { (key, command) ->
            val tvKey = TclIr.keys.getValue(key)
            assertArrayEquals("$key", flipperPattern(0xF, command), TclIr.pattern(tvKey))
        }
    }

    @Test
    fun `frame carries the key and its complement`() {
        val bits = TclIr.frameBits(0xD5)
        assertEquals(24, bits.size)
        val value = bits.fold(0) { acc, b -> (acc shl 1) or if (b) 1 else 0 }
        assertEquals(0xF, value shr 20)
        assertEquals(0x2A, (value shr 12) and 0xFF)
        assertEquals(0x0, (value shr 8) and 0xF)
        assertEquals(0xD5, value and 0xFF)
    }

    @Test
    fun `every pattern is well formed and far under the 2 second transmit limit`() {
        (TclIr.keys.values + TclIr.apps.values).forEach { code ->
            val p = TclIr.pattern(code)
            assertEquals(52, p.size)
            assertTrue(p.sum() < 100_000)
        }
    }

    @Test
    fun `held power is repeated whole frames lasting long enough to open the power off dialog`() {
        val frame = TclIr.pattern(TclIr.keys.getValue(RemoteKey.POWER))
        val held = TclIr.heldPattern(TclIr.keys.getValue(RemoteKey.POWER))
        assertEquals(0, held.size % frame.size)
        held.toList().chunked(frame.size).forEach { assertArrayEquals(frame, it.toIntArray()) }
        val micros = held.sum()
        // On the TCL a 1.5 s hold opens the dialog; ConsumerIrManager refuses patterns of 2 s or more.
        assertTrue("held for $micros us", micros in 1_500_000 until 2_000_000)
        assertEquals(micros / 1000L, TclIr.durationMs(held))
    }

    @Test
    fun `TizenTube is never sent to the stock YouTube IR button`() {
        assertTrue(AppShortcut.TIZENTUBE !in TclIr.apps)
    }
}
