package com.sudomastery.tvremote

import com.sudomastery.tvremote.ir.TclIr
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TclIrTest {

    /** Flipper Zero's RCA encoder: 24 data bits sent least significant bit first. */
    private fun flipperBits(address: Int, command: Int): List<Boolean> {
        var data = address and 0xF
        data = data or (command shl 4)
        data = data or ((address.inv() and 0xF) shl 12)
        data = data or ((command.inv() and 0xFF) shl 16)
        return (0 until 24).map { (data shr it) and 1 == 1 }
    }

    /** Reads the bits back out of a pattern: header pair, then (mark, space) per bit. */
    private fun decode(pattern: IntArray): List<Boolean> =
        (0 until 24).map { pattern[3 + it * 2] > 1400 } // spaces: 920 for 0, 1840 for 1

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

    /** RCA-38 function numbers from IRDB codes/TCL/TV/15,-1.csv (device 15). */
    private val irdbFunctions = mapOf(
        RemoteKey.POWER to 42, RemoteKey.BACK to 39, RemoteKey.GUIDE to 26, RemoteKey.HOME to 8,
        RemoteKey.CHANNEL_DOWN to 44, RemoteKey.CHANNEL_UP to 45, RemoteKey.VOLUME_DOWN to 46, RemoteKey.VOLUME_UP to 47,
        RemoteKey.DIGIT_0 to 48, RemoteKey.DIGIT_1 to 49, RemoteKey.DIGIT_2 to 50, RemoteKey.DIGIT_3 to 51,
        RemoteKey.DIGIT_4 to 52, RemoteKey.DIGIT_5 to 53, RemoteKey.DIGIT_6 to 54, RemoteKey.DIGIT_7 to 55,
        RemoteKey.DIGIT_8 to 56, RemoteKey.DIGIT_9 to 57, RemoteKey.INFO to 60, RemoteKey.MUTE to 63,
        RemoteKey.LEFT to 86, RemoteKey.RIGHT to 87, RemoteKey.DOWN to 88, RemoteKey.UP to 89,
        RemoteKey.INPUT to 163, RemoteKey.OK to 244,
    )

    @Test
    fun `TV keymap codes produce the same bits as Flipper captures of real TCL remotes`() {
        flipperCaptures.forEach { (key, command) ->
            assertEquals("$key", flipperBits(0xF, command), decode(TclIr.pattern(TclIr.keys.getValue(key))))
        }
    }

    @Test
    fun `TV keymap agrees with the IRDB TCL code list`() {
        irdbFunctions.forEach { (key, function) ->
            // RCA-38 sends D:4, F:8 most significant bit first; the TV keymap stores ~F.
            assertEquals("$key", function, TclIr.keys.getValue(key).inv() and 0xFF)
            val bits = decode(TclIr.pattern(TclIr.keys.getValue(key)))
            assertEquals("$key", function, bits.subList(4, 12).fold(0) { acc, b -> (acc shl 1) or if (b) 1 else 0 })
        }
    }

    @Test
    fun `timings follow the RCA-38 reference`() {
        val p = TclIr.pattern(TclIr.keys.getValue(RemoteKey.POWER))
        assertEquals(3680, p[0])
        assertEquals(3680, p[1])
        assertTrue(p.filterIndexed { i, _ -> i in 2 until 50 && i % 2 == 0 }.all { it == 460 })
        assertTrue(p.filterIndexed { i, _ -> i in 3 until 50 && i % 2 == 1 }.all { it == 920 || it == 1840 })
        assertEquals(460, p[50])
        assertEquals(7360, p[51])
    }

    @Test
    fun `a power tap is two whole frames, well short of a long press`() {
        val frame = TclIr.pattern(TclIr.keys.getValue(RemoteKey.POWER))
        val tap = TclIr.tapPattern(TclIr.keys.getValue(RemoteKey.POWER))
        assertArrayEquals(frame + frame, tap)
        assertTrue(TclIr.durationMs(tap) < 300) // the power off dialog needs a hold of over a second
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
