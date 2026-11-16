package com.sudomastery.tvremote

import android.app.Application

class TvRemoteApp : Application() {
    /** One connection to the TV for the whole app: the remote screen and the notification share it. */
    val controller: RemoteController by lazy { RemoteController(this) }
}

val Application.controller: RemoteController get() = (this as TvRemoteApp).controller
