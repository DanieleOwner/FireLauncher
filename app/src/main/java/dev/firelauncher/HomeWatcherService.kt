package dev.firelauncher

import android.app.AppOpsManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log

/**
 * Fire OS non permette di cambiare launcher e blocca i servizi di accessibilità di terze parti.
 * Questo servizio controlla qual è l'app in primo piano (permesso "dati di utilizzo", concesso via ADB)
 * e appena compare la home Amazon apre FireLauncher al suo posto.
 * Le altre schermate Amazon (impostazioni, Appstore, "Le tue app") restano usabili.
 */
class HomeWatcherService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var usage: UsageStatsManager
    private var lastCheck = 0L
    private var lastRedirect = 0L

    private val tick = object : Runnable {
        override fun run() {
            check()
            handler.postDelayed(this, POLL_MS)
        }
    }

    private var logcatThread: Thread? = null
    @Volatile private var logcatProcess: java.lang.Process? = null

    override fun onCreate() {
        super.onCreate()
        usage = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        lastCheck = System.currentTimeMillis()
        handler.post(tick)
        startLogcatWatcher()
        Log.i(TAG, "Avviato")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        logcatProcess?.destroy()
        logcatThread?.interrupt()
        super.onDestroy()
    }

    /**
     * Metodo veloce: con il permesso READ_LOGS (concesso via ADB) leggo il registro di sistema
     * e vedo la richiesta di aprire la home Amazon nell'istante in cui parte, prima ancora che compaia.
     * Gli eventi di utilizzo sopra restano come rete di sicurezza (arrivano con qualche secondo di ritardo).
     */
    private fun startLogcatWatcher() {
        if (checkSelfPermission(android.Manifest.permission.READ_LOGS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "READ_LOGS non concesso: uso solo gli eventi di utilizzo")
            return
        }
        logcatThread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val p = Runtime.getRuntime().exec(arrayOf("logcat", "-T", "1", "-b", "main,system", "-s", "ActivityManager:I"))
                    logcatProcess = p
                    p.inputStream.bufferedReader().forEachLine { line ->
                        if (line.contains("START u0") && line.contains("cmp=$AMAZON_LAUNCHER/") && line.contains(AMAZON_HOME)) {
                            handler.post { redirect(System.currentTimeMillis()) }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "logcat interrotto", e)
                }
                try { Thread.sleep(2000) } catch (_: InterruptedException) { return@Thread }
            }
        }.apply { isDaemon = true; name = "home-logcat"; start() }
    }

    /**
     * Dopo la pressione di Home, Android blocca per 5 secondi le activity avviate dalle app in background.
     * Se però è l'AlarmManager (cioè il sistema) a consegnare il PendingIntent, il blocco non si applica:
     * per questo l'avvio passa da un allarme "adesso" invece che da startActivity diretto.
     */
    private fun redirect(now: Long) {
        if (now - lastRedirect < 800) return
        lastRedirect = now
        HomeOverlay.show(this)
        val intent = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = android.app.PendingIntent.getActivity(
            this, 1, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val alarms = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        alarms.setExact(android.app.AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime(), pending)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun check() {
        val now = System.currentTimeMillis()
        // Gli eventi possono arrivare con qualche istante di ritardo: guardo gli ultimi 10 s
        // e considero solo l'ultimo passaggio in primo piano, se non l'ho già gestito.
        val events = usage.queryEvents(now - 10_000, now + 1_000)
        val e = UsageEvents.Event()
        var lastPkg: String? = null
        var lastCls: String? = null
        var lastTs = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType != UsageEvents.Event.MOVE_TO_FOREGROUND) continue
            lastPkg = e.packageName; lastCls = e.className; lastTs = e.timeStamp
        }
        if (lastTs <= lastCheck) return
        lastCheck = lastTs
        Log.d(TAG, "In primo piano: $lastPkg/$lastCls")
        val amazonHomeOnTop = lastPkg == AMAZON_LAUNCHER && lastCls?.contains(AMAZON_HOME) == true
        if (amazonHomeOnTop) redirect(now)
    }

    companion object {
        private const val TAG = "HomeWatcher"
        private const val POLL_MS = 200L
        private const val AMAZON_LAUNCHER = "com.amazon.tv.launcher"
        private const val AMAZON_HOME = "HomeActivity"

        fun hasPermission(context: Context): Boolean {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            @Suppress("DEPRECATION")
            val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            return mode == AppOpsManager.MODE_ALLOWED
        }

        fun start(context: Context) {
            if (hasPermission(context)) context.startService(Intent(context, HomeWatcherService::class.java))
        }
    }
}

/** Riavvia il controllo della home all'accensione e dopo un aggiornamento dell'app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        HomeWatcherService.start(context)
    }
}
