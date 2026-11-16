package com.sudomastery.tvremote.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.sudomastery.atv.ConnectionStatus
import com.sudomastery.tvremote.MainActivity
import com.sudomastery.tvremote.R
import com.sudomastery.tvremote.RemoteKey
import com.sudomastery.tvremote.controller
import com.sudomastery.tvremote.label
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Keeps a "Remote controls" notification with back, the arrows and OK, so the TV can be driven
 * from the notification shade without leaving the current app.
 *
 * It is a foreground service so the app process (and the shared connection) survives in the
 * background. The Wi-Fi link itself is only held for [WARM_MS] after the last press; a press
 * on a cold link connects first and then sends the key.
 */
class ControlsService : Service() {
    private val controller by lazy { application.controller }
    private var stateJob: Job? = null
    private var warmJob: Job? = null

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val initial = statusText(controller.current.value?.name, controller.tvState.value.status, controller.irMode.value)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            build(initial),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
        stateJob = controller.scope.launch {
            combine(controller.current, controller.tvState, controller.irMode) { tv, state, ir -> statusText(tv?.name, state.status, ir) }
                .distinctUntilChanged()
                .drop(1) // the first value is what startForeground just posted
                .collect { text -> getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(text)) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_KEY -> {
                val key = intent.getStringExtra(EXTRA_KEY)?.let { name -> RemoteKey.entries.firstOrNull { it.name == name } }
                if (key != null && key in KEYS) {
                    tick()
                    keepWarm()
                    controller.pressWhenReady(key)
                }
            }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stateJob?.cancel()
        warmJob?.cancel()
        controller.release(HOLDER, graceMs = 0)
        super.onDestroy()
    }

    /** Hold the link for a while after a press so the next presses are instant. */
    private fun keepWarm() {
        controller.hold(HOLDER)
        warmJob?.cancel()
        warmJob = controller.scope.launch {
            delay(WARM_MS)
            controller.release(HOLDER, graceMs = 0)
        }
    }

    private fun statusText(tvName: String?, status: ConnectionStatus, irMode: Boolean): String {
        val name = tvName ?: return "No TV set up, open the app"
        val state = when {
            irMode -> "Infrared"
            status == ConnectionStatus.NeedsPairing || status == ConnectionStatus.CertificateChanged -> "Open the app to pair"
            // Idle is the normal resting state here: the next tap connects.
            else -> status.label(idle = "Ready")
        }
        return "$name · $state"
    }

    private fun build(status: String): Notification {
        val collapsed = RemoteViews(packageName, R.layout.notification_collapsed).also(::wireButtons)
        val expanded = RemoteViews(packageName, R.layout.notification_expanded).also(::wireButtons)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_remote)
            .setContentTitle(getString(R.string.app_name))
            .setSubText(status)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            .setContentIntent(open)
            // Swiping the notification away (allowed on Android 14+) stops the controls until
            // the app is opened again.
            .setDeleteIntent(servicePendingIntent(ACTION_STOP, null, REQUEST_STOP))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun wireButtons(views: RemoteViews) {
        BUTTONS.forEach { (id, key) ->
            views.setOnClickPendingIntent(id, servicePendingIntent(ACTION_KEY, key, key.ordinal))
        }
    }

    private fun servicePendingIntent(action: String, key: RemoteKey?, requestCode: Int): PendingIntent {
        val intent = Intent(this, ControlsService::class.java).setAction(action)
        if (key != null) intent.putExtra(EXTRA_KEY, key.name)
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun tick() {
        if (!controller.store.haptics) return
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        } ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else {
                vibrator.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    private fun createChannel() {
        // Default importance keeps the controls in the main part of the shade rather than the
        // "Silent" group at the bottom; sound and vibration are off so it never makes a noise.
        val channel = NotificationChannel(CHANNEL_ID, "Remote controls", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "D-pad, OK and back in the notification shade"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "controls"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_KEY = "com.sudomastery.tvremote.KEY"
        private const val ACTION_STOP = "com.sudomastery.tvremote.STOP"
        private const val EXTRA_KEY = "key"
        private const val REQUEST_STOP = 10_000
        private const val HOLDER = "notification"
        private const val WARM_MS = 120_000L

        private val BUTTONS = listOf(
            R.id.n_up to RemoteKey.UP,
            R.id.n_down to RemoteKey.DOWN,
            R.id.n_left to RemoteKey.LEFT,
            R.id.n_right to RemoteKey.RIGHT,
            R.id.n_ok to RemoteKey.OK,
            R.id.n_back to RemoteKey.BACK,
        )
        private val KEYS = BUTTONS.map { it.second }.toSet()

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ControlsService::class.java))
        }

        /** False if the user has blocked the app's notifications or this channel. */
        fun canShow(context: Context): Boolean {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return false
            val channel = manager.getNotificationChannel(CHANNEL_ID) ?: return true // created on first start
            return channel.importance != NotificationManager.IMPORTANCE_NONE
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ControlsService::class.java))
        }
    }
}
