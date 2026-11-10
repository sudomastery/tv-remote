package com.sudomastery.tvremote.ir

import com.sudomastery.tvremote.AppShortcut
import com.sudomastery.tvremote.RemoteKey

/**
 * TCL's infrared protocol (the RCA format): 4-bit address 0xF and an 8-bit command, each followed
 * by its complement, pulse-distance coded.
 *
 * Key codes are copied from the TV itself (`/vendor/tvconfig/config/ir_config.ini`, section
 * `KIR_ChinaMobile_Keymap`, protocol 0x07, header 0x000F). The TV lists each key by the byte it
 * decodes, which is the complement of the command byte sent first; [frameBits] accounts for that.
 * Timings match Flipper Zero's RCA encoder, and the bit layout is checked against Flipper-IRDB
 * captures of TCL remotes in the unit tests.
 */
object TclIr {
    /** Flipper sends RCA on its common 38 kHz carrier, which TCL receivers accept. */
    const val CARRIER_HZ = 38_000

    private const val ADDRESS = 0xF
    private const val HEADER_MARK = 4000
    private const val HEADER_SPACE = 4000
    private const val BIT_MARK = 500
    private const val ONE_SPACE = 2000
    private const val ZERO_SPACE = 1000
    private const val TRAILING_GAP = 8000

    /** Remote key to the code in the TV's keymap. */
    val keys: Map<RemoteKey, Int> = mapOf(
        RemoteKey.POWER to 0xD5,
        RemoteKey.UP to 0xA6,
        RemoteKey.DOWN to 0xA7,
        RemoteKey.RIGHT to 0xA8,
        RemoteKey.LEFT to 0xA9,
        RemoteKey.OK to 0x0B,
        RemoteKey.BACK to 0xD8,
        RemoteKey.HOME to 0xF7,
        RemoteKey.MENU to 0x13,
        RemoteKey.VOLUME_UP to 0xD0,
        RemoteKey.VOLUME_DOWN to 0xD1,
        RemoteKey.MUTE to 0xC0,
        RemoteKey.CHANNEL_UP to 0xD2,
        RemoteKey.CHANNEL_DOWN to 0xD3,
        RemoteKey.INPUT to 0x5C,
        RemoteKey.SETTINGS to 0x30,
        RemoteKey.INFO to 0xC3,
        RemoteKey.GUIDE to 0xE5,
        RemoteKey.CAPTIONS to 0x7F,
        RemoteKey.SEARCH to 0x06,
        RemoteKey.PLAY_PAUSE to 0xD9,
        RemoteKey.STOP to 0xE0,
        RemoteKey.REWIND to 0xE2,
        RemoteKey.FAST_FORWARD to 0xE3,
        RemoteKey.NEXT to 0xAC,
        RemoteKey.DIGIT_0 to 0xCF,
        RemoteKey.DIGIT_1 to 0xCE,
        RemoteKey.DIGIT_2 to 0xCD,
        RemoteKey.DIGIT_3 to 0xCC,
        RemoteKey.DIGIT_4 to 0xCB,
        RemoteKey.DIGIT_5 to 0xCA,
        RemoteKey.DIGIT_6 to 0xC9,
        RemoteKey.DIGIT_7 to 0xC8,
        RemoteKey.DIGIT_8 to 0xC7,
        RemoteKey.DIGIT_9 to 0xC6,
    )

    /**
     * Dedicated app buttons on TCL remotes. TizenTube is deliberately absent: the remote's YouTube
     * button targets stock YouTube, not TizenTube. Spotify has no button.
     */
    val apps: Map<AppShortcut, Int> = mapOf(
        AppShortcut.NETFLIX to 0x10,
        AppShortcut.PRIME to 0x3E,
    )

    /** The 24 bits on air, first bit first: address, command, then both complemented. */
    internal fun frameBits(tvKey: Int): List<Boolean> {
        val command = tvKey.inv() and 0xFF
        val bits = ArrayList<Boolean>(24)
        fun msbFirst(value: Int, width: Int) = (width - 1 downTo 0).forEach { bits += (value shr it) and 1 == 1 }
        msbFirst(ADDRESS, 4)
        msbFirst(command, 8)
        msbFirst(ADDRESS.inv() and 0xF, 4)
        msbFirst(tvKey and 0xFF, 8)
        return bits
    }

    /**
     * The key held down: the full frame repeated back to back (TCL's IR format has no repeat
     * code), as one pattern so the timing doesn't depend on the phone's IR driver.
     * [maxMicros] stays under ConsumerIrManager's 2 second limit.
     */
    fun heldPattern(tvKey: Int, maxMicros: Int = 1_900_000): IntArray {
        val frame = pattern(tvKey)
        val repeats = maxOf(1, maxMicros / frame.sum())
        return IntArray(frame.size * repeats) { frame[it % frame.size] }
    }

    /** How long a pattern takes to transmit. */
    fun durationMs(pattern: IntArray): Long = pattern.sumOf { it.toLong() } / 1000

    /** On/off durations in microseconds, starting with "on", for ConsumerIrManager.transmit. */
    fun pattern(tvKey: Int): IntArray {
        val out = ArrayList<Int>(52)
        out += HEADER_MARK
        out += HEADER_SPACE
        for (bit in frameBits(tvKey)) {
            out += BIT_MARK
            out += if (bit) ONE_SPACE else ZERO_SPACE
        }
        out += BIT_MARK // stop mark ends the last space
        out += TRAILING_GAP
        return out.toIntArray()
    }
}
