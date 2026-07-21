package app.nekolink.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.nekolink.android.MainActivity
import app.nekolink.android.R
import app.nekolink.android.collector.AndroidCollector
import app.nekolink.android.domain.AgentCore
import app.nekolink.android.domain.AgentPhase
import app.nekolink.android.domain.ClientConfig
import app.nekolink.android.domain.SampleDiff
import app.nekolink.android.domain.isUnauthorized
import app.nekolink.android.net.ApiClient
import app.nekolink.android.protocol.CollectedSample
import app.nekolink.android.store.PrefsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground service for paired upload loop (Android equivalent of Windows tray residency).
 */
class UploadForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private lateinit var store: PrefsStore
    private val mutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        store = PrefsStore(this)
        createChannel()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopLoop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                store.setPaused(true)
                RuntimeStatus.paused.set(true)
                RuntimeStatus.phase.set(AgentPhase.PAUSED)
                updateNotification("已暂停上报")
            }
            ACTION_RESUME -> {
                store.setPaused(false)
                RuntimeStatus.paused.set(false)
                RuntimeStatus.phase.set(AgentPhase.RUNNING)
                updateNotification("上报中")
                ensureLoop()
            }
            else -> {
                startAsForeground()
                ensureLoop()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopLoop()
        scope.cancel()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notification = buildNotification("上报中")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureLoop() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch { runLoop() }
    }

    private fun stopLoop() {
        loopJob?.cancel()
        loopJob = null
        RuntimeStatus.phase.set(AgentPhase.STOPPED)
    }

    private suspend fun runLoop() {
        val config = store.loadConfig()
        val creds = store.loadCredentials()
        if (creds == null) {
            RuntimeStatus.phase.set(AgentPhase.SETUP)
            stopSelf()
            return
        }
        val core = AgentCore(
            config = config,
            client = ApiClient(config.serverBase),
            credentials = creds,
        )
        val collector = AndroidCollector(
            context = applicationContext,
            showSystemBackground = config.showSystemBackground,
            backgroundCap = config.backgroundAppCap,
        )

        RuntimeStatus.phase.set(if (store.isPaused()) AgentPhase.PAUSED else AgentPhase.RUNNING)
        RuntimeStatus.paused.set(store.isPaused())
        RuntimeStatus.deviceId.set(creds.deviceId)
        RuntimeStatus.serverBase.set(config.serverBase)
        RuntimeStatus.displayName.set(config.displayName)

        val token = creds.deviceToken
        val pollMs = (config.pollIntervalSecs.coerceAtLeast(1)) * 1000
        val hbEveryMs = (config.heartbeatIntervalSecs.coerceAtLeast(5)) * 1000
        val bgEveryMs = (config.backgroundIntervalSecs.coerceAtLeast(10)) * 1000

        var lastSent: CollectedSample? = null
        var lastHb = 0L
        var lastBg = 0L

        // Initial full snapshot when not paused
        if (!store.isPaused()) {
            try {
                val sample = collector.sample()
                RuntimeStatus.lastSample.set(sample)
                val (_, sent) = core.pushSample(token, sample, null, forceBackground = true, forceMeaningful = true)
                lastSent = sent ?: sample
                core.heartbeatOnce(token)
                lastHb = System.currentTimeMillis()
                lastBg = System.currentTimeMillis()
                onSyncOk()
            } catch (e: Exception) {
                handleError(e)
            }
        }

        while (scope.isActive) {
            delay(pollMs)
            if (store.isPaused()) {
                RuntimeStatus.phase.set(AgentPhase.PAUSED)
                RuntimeStatus.paused.set(true)
                continue
            }
            RuntimeStatus.paused.set(false)
            RuntimeStatus.phase.set(AgentPhase.RUNNING)

            // Reload config mid-loop for display name / filters
            val liveConfig = store.loadConfig()
            if (liveConfig.serverBase != core.config.serverBase) {
                // URL changed externally — stop; UI will re-pair
                break
            }
            core.config = liveConfig

            val liveCreds = store.loadCredentials()
            if (liveCreds == null) break

            val sample = try {
                AndroidCollector(
                    context = applicationContext,
                    showSystemBackground = liveConfig.showSystemBackground,
                    backgroundCap = liveConfig.backgroundAppCap,
                ).sample()
            } catch (e: Exception) {
                RuntimeStatus.lastError.set(e.message)
                continue
            }
            RuntimeStatus.lastSample.set(sample)

            val forceBg = System.currentTimeMillis() - lastBg >= bgEveryMs
            try {
                mutex.withLock {
                    val (diff, sent) = core.pushSample(
                        token = liveCreds.deviceToken,
                        sample = sample,
                        prev = lastSent,
                        forceBackground = forceBg,
                        forceMeaningful = false,
                    )
                    if (sent != null) {
                        lastSent = sent
                        if (forceBg) lastBg = System.currentTimeMillis()
                        if (diff != SampleDiff.NONE) onSyncOk()
                    }
                }
            } catch (e: Exception) {
                if (handleError(e)) break
            }

            if (System.currentTimeMillis() - lastHb >= hbEveryMs) {
                try {
                    core.heartbeatOnce(liveCreds.deviceToken)
                    lastHb = System.currentTimeMillis()
                    onSyncOk()
                } catch (e: Exception) {
                    if (handleError(e)) break
                }
            }
        }
    }

    private fun onSyncOk() {
        RuntimeStatus.lastSyncAt.set(Instant.now().toString())
        RuntimeStatus.lastError.set(null)
        updateNotification("上报中 · 已同步")
    }

    /** @return true if loop should stop */
    private fun handleError(e: Exception): Boolean {
        RuntimeStatus.lastError.set(e.message ?: e.toString())
        RuntimeStatus.phase.set(AgentPhase.ERROR)
        updateNotification("错误: ${e.message ?: "上报失败"}")
        if (isUnauthorized(e)) {
            store.clearCredentials()
            RuntimeStatus.phase.set(AgentPhase.SETUP)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return true
        }
        return false
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "NekoLink 上报",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "设备状态上报保活通知"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(content: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pause = PendingIntent.getService(
            this,
            1,
            Intent(this, UploadForegroundService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val resume = PendingIntent.getService(
            this,
            2,
            Intent(this, UploadForegroundService::class.java).setAction(ACTION_RESUME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            3,
            Intent(this, UploadForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val paused = store.isPaused()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("NekoLink")
            .setContentText(content)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                0,
                if (paused) "继续" else "暂停",
                if (paused) resume else pause,
            )
            .addAction(0, "停止", stop)
            .build()
    }

    private fun updateNotification(content: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(content))
    }

    companion object {
        const val CHANNEL_ID = "nekolink_upload"
        const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "app.nekolink.android.STOP"
        const val ACTION_PAUSE = "app.nekolink.android.PAUSE"
        const val ACTION_RESUME = "app.nekolink.android.RESUME"

        @Volatile
        private var instance: UploadForegroundService? = null

        fun start(context: Context) {
            val intent = Intent(context, UploadForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, UploadForegroundService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}

/** Process-local runtime mirror for UI. */
object RuntimeStatus {
    val phase = AtomicReference(AgentPhase.STOPPED)
    val paused = AtomicBoolean(false)
    val lastSyncAt = AtomicReference<String?>(null)
    val lastError = AtomicReference<String?>(null)
    val lastSample = AtomicReference<CollectedSample?>(null)
    val deviceId = AtomicReference<String?>(null)
    val serverBase = AtomicReference<String?>(null)
    val displayName = AtomicReference<String?>(null)
    val privacyShield = AtomicBoolean(false)
}
