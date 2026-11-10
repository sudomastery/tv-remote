package com.sudomastery.tvremote

import com.sudomastery.atv.proto.RemoteProto.RemoteKeyCode

/** Every button the UI can press, independent of how it gets to the TV. */
enum class RemoteKey(val atv: RemoteKeyCode) {
    POWER(RemoteKeyCode.KEYCODE_POWER),
    UP(RemoteKeyCode.KEYCODE_DPAD_UP),
    DOWN(RemoteKeyCode.KEYCODE_DPAD_DOWN),
    LEFT(RemoteKeyCode.KEYCODE_DPAD_LEFT),
    RIGHT(RemoteKeyCode.KEYCODE_DPAD_RIGHT),
    OK(RemoteKeyCode.KEYCODE_DPAD_CENTER),
    BACK(RemoteKeyCode.KEYCODE_BACK),
    HOME(RemoteKeyCode.KEYCODE_HOME),
    MENU(RemoteKeyCode.KEYCODE_MENU),
    VOLUME_UP(RemoteKeyCode.KEYCODE_VOLUME_UP),
    VOLUME_DOWN(RemoteKeyCode.KEYCODE_VOLUME_DOWN),
    MUTE(RemoteKeyCode.KEYCODE_VOLUME_MUTE),
    CHANNEL_UP(RemoteKeyCode.KEYCODE_CHANNEL_UP),
    CHANNEL_DOWN(RemoteKeyCode.KEYCODE_CHANNEL_DOWN),
    INPUT(RemoteKeyCode.KEYCODE_TV_INPUT),
    SETTINGS(RemoteKeyCode.KEYCODE_SETTINGS),
    INFO(RemoteKeyCode.KEYCODE_INFO),
    GUIDE(RemoteKeyCode.KEYCODE_GUIDE),
    CAPTIONS(RemoteKeyCode.KEYCODE_CAPTIONS),
    SEARCH(RemoteKeyCode.KEYCODE_SEARCH),
    PLAY_PAUSE(RemoteKeyCode.KEYCODE_MEDIA_PLAY_PAUSE),
    STOP(RemoteKeyCode.KEYCODE_MEDIA_STOP),
    REWIND(RemoteKeyCode.KEYCODE_MEDIA_REWIND),
    FAST_FORWARD(RemoteKeyCode.KEYCODE_MEDIA_FAST_FORWARD),
    PREVIOUS(RemoteKeyCode.KEYCODE_MEDIA_PREVIOUS),
    NEXT(RemoteKeyCode.KEYCODE_MEDIA_NEXT),
    DELETE(RemoteKeyCode.KEYCODE_DEL),
    ENTER(RemoteKeyCode.KEYCODE_ENTER),
    DIGIT_0(RemoteKeyCode.KEYCODE_0),
    DIGIT_1(RemoteKeyCode.KEYCODE_1),
    DIGIT_2(RemoteKeyCode.KEYCODE_2),
    DIGIT_3(RemoteKeyCode.KEYCODE_3),
    DIGIT_4(RemoteKeyCode.KEYCODE_4),
    DIGIT_5(RemoteKeyCode.KEYCODE_5),
    DIGIT_6(RemoteKeyCode.KEYCODE_6),
    DIGIT_7(RemoteKeyCode.KEYCODE_7),
    DIGIT_8(RemoteKeyCode.KEYCODE_8),
    DIGIT_9(RemoteKeyCode.KEYCODE_9);

    companion object {
        val digits = listOf(DIGIT_1, DIGIT_2, DIGIT_3, DIGIT_4, DIGIT_5, DIGIT_6, DIGIT_7, DIGIT_8, DIGIT_9, DIGIT_0)
    }
}

/**
 * An app shortcut. [link] is a deep link the app itself registers, so launching does not depend
 * on the Play Store being able to resolve `market://launch` links (it can't on a debloated TV).
 */
enum class AppShortcut(val label: String, val packageName: String, val link: String) {
    NETFLIX("Netflix", "com.netflix.ninja", "https://www.netflix.com/browse"),
    // TizenTube, not stock YouTube. TizenTube is the only app on the TV that handles youtube.com
    // links because stock YouTube (com.google.android.youtube.tv) is removed by tcl-debloat.
    // There is no package-targeted launch to use instead: market://launch fails on this TV and
    // makes the remote service drop the connection.
    TIZENTUBE("TizenTube", "io.gh.reisxd.tizentube.cobalt", "https://www.youtube.com"),
    PRIME("Prime", "com.amazon.amazonvideo.livingroom", "https://app.primevideo.com"),
    SPOTIFY("Spotify", "com.spotify.tv.android", "spotify://"),
}
