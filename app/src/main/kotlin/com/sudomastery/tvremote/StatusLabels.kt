package com.sudomastery.tvremote

import com.sudomastery.atv.ConnectionStatus

/**
 * User-facing text for a connection status, shared by the remote screen and the notification.
 * [idle] is what "not connected" reads as: an error on the screen, but the normal resting state
 * for the notification, which connects on the next tap.
 */
fun ConnectionStatus.label(idle: String = "Not connected"): String = when (this) {
    ConnectionStatus.Connected -> "Connected"
    ConnectionStatus.Connecting -> "Connecting…"
    ConnectionStatus.Disconnected -> idle
    ConnectionStatus.NeedsPairing -> "Needs pairing"
    ConnectionStatus.CertificateChanged -> "Pair again"
    is ConnectionStatus.Unreachable -> "Can't reach the TV"
}
