package ai.meridian.tcmsession

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

private const val CHANNEL_ID = "recording"
private const val NOTIFICATION_ID = 1
private const val TAG = "RecordingService"
// Longer than any realistic session; only a safety net if the service is
// somehow never stopped, so a forgotten wake lock can't drain the battery.
private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L

/**
 * Keeps the app alive while any session is recording or saving
 * (SessionRecorder alone only survives moving between screens). Without it, Android treats an app with no screen
 * showing as disposable: locking the phone or switching apps could freeze it
 * or reclaim its memory, stopping or losing the recording.
 *
 * A foreground service (the ongoing notification is the price Android charges
 * for one) of type connectedDevice, since what it keeps alive is the Muse
 * headband's Bluetooth link. It holds a partial wake lock too: a foreground
 * service alone doesn't stop the CPU sleeping with the screen off, and
 * SessionRecorder's once-a-second tick is a main-thread timer that stops
 * while it does.
 *
 * It holds no recording state of its own; SessionRecorder starts and stops it
 * through [update]. START_NOT_STICKY: if the process dies anyway, a restarted
 * service would have no recording to keep alive. SessionJournal is what
 * rescues that case.
 */
class RecordingService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A started foreground service must call startForeground, even when
        // it's already been asked to stop, or Android crashes the app. So it
        // always does, and only then honours a stop that arrived meanwhile.
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notificationFor(latestNames, latestSaving),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        } catch (e: Exception) {
            // Shouldn't happen: connectedDevice's permission prerequisite is
            // met by CHANGE_NETWORK_STATE, which is always granted. If it
            // does, Android may still end the app for never reaching
            // startForeground; there's nothing more to do here, and the
            // journal is what protects the recording then.
            Log.w(TAG, "couldn't start in the foreground: ${e.message}")
            state = State.STOPPED
            stopRequested = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (stopRequested) {
            stopRequested = false
            state = State.STOPPED
            stopSelf()
            return START_NOT_STICKY
        }
        state = State.RUNNING
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TcmSessionApp:recording")
                ?.apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Only from RUNNING: a stop already set STOPPED, and a start requested
        // since (STARTING) belongs to the next instance, whose state a late
        // onDestroy from this one mustn't overwrite.
        if (state == State.RUNNING) state = State.STOPPED
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private enum class State { STOPPED, STARTING, RUNNING }

        // Main thread only: the service callbacks and update() all run there.
        private var state = State.STOPPED
        private var stopRequested = false
        private var latestNames: List<String> = emptyList()
        private var latestSaving = false

        /** Starts, updates or stops the service to match the recorders:
         * running while any patient is [recording] or [saving], stopped when
         * none is. Called on every recorder phase change.
         *
         * Only the first start goes through startForegroundService, from a
         * tap on the recording screen while the app is in front, which is
         * when Android allows one. Later changes (a save finishing while the
         * phone is locked, say) just re-post the notification, since a
         * foreground-service start from the background can be refused. */
        fun update(context: Context, recording: List<String>, saving: List<String>) {
            if (recording.isEmpty() && saving.isEmpty()) {
                when (state) {
                    State.RUNNING -> {
                        state = State.STOPPED
                        context.stopService(Intent(context, RecordingService::class.java))
                    }
                    // Stopping before startForeground runs would crash the
                    // app; onStartCommand stops it right after instead.
                    State.STARTING -> stopRequested = true
                    State.STOPPED -> {}
                }
                return
            }
            stopRequested = false
            latestNames = recording.ifEmpty { saving }
            latestSaving = recording.isEmpty()
            when (state) {
                State.RUNNING -> try {
                    context.getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, context.notificationFor(latestNames, latestSaving))
                } catch (e: SecurityException) {
                    // No notification permission: the service runs regardless.
                }
                // onStartCommand will use the latest names.
                State.STARTING -> {}
                State.STOPPED -> try {
                    ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
                    state = State.STARTING
                } catch (e: Exception) {
                    // ForegroundServiceStartNotAllowedException if Android
                    // thinks the app is in the background. Same fallback as
                    // a failed startForeground above.
                    Log.w(TAG, "couldn't start: ${e.message}")
                }
            }
        }
    }
}

/** The ongoing notification, shared by the first start and later updates. */
private fun Context.notificationFor(names: List<String>, saving: Boolean): android.app.Notification {
    val manager = getSystemService(NotificationManager::class.java)
    if (manager.getNotificationChannel(CHANNEL_ID) == null) {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Session recording", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a session is being recorded, so it keeps running with the screen off."
            }
        )
    }
    // Brings the app's existing task to the front, on whatever screen it was
    // showing, rather than starting a fresh one. That only happens when this
    // matches the intent the home-screen icon launched the task with, so it
    // is built the same way: explicit, MAIN/LAUNCHER, and no package set
    // (getLaunchIntentForPackage sets one, which makes it not match).
    val openApp = Intent(this, LoginActivity::class.java)
        .setAction(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    val contentIntent = PendingIntent.getActivity(
        this, 0, openApp, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val title = when {
        saving && names.size > 1 -> "Saving ${names.size} sessions"
        saving -> "Saving a session"
        names.size > 1 -> "Recording ${names.size} sessions"
        else -> "Recording a session"
    }
    val generic = if (saving) "Uploading to the server. Keep the phone connected to the network."
    else "Keep the headband on; tap End session in the app to save."
    val text = when {
        names.isEmpty() -> generic
        saving && names.size == 1 -> "Uploading ${names[0]}'s session. Keep the phone connected to the network."
        saving -> "Uploading sessions for ${names.joinToString(", ")}. Keep the phone connected to the network."
        else -> "${names.joinToString(", ")}. $generic"
    }
    fun builder(body: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_recording)
        .setContentTitle(title)
        .setContentText(body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(contentIntent)
    // Patient names stay off the lock screen, which shows the public version.
    return builder(text)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(builder(generic).build())
        .build()
}
