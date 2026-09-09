package com.example.yt3chardirect

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CheckService : Service() {
    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val CHANNEL_ID = "yt_checking"
        const val NOTIF_ID = 1042
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val working = AtomicBoolean(false)
    private val blockedStreak = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.edit().putBoolean("stop", true).putBoolean("paused", false).apply()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (working.compareAndSet(false, true)) {
                    prefs.edit().putBoolean("stop", false).apply()
                    startForeground(NOTIF_ID, notification("Подготовка проверки…"))
                    executor.submit {
                        try { runAll() }
                        finally {
                            working.set(false)
                            prefs.edit().putBoolean("running", false).apply()
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun runAll() {
        val db = Db(this)
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val need = prefs.getInt("confirmPasses", 2).coerceIn(2, 5)
        val retries = prefs.getInt("retryPasses", 3).coerceIn(1, 8)
        val delay = prefs.getInt("delayMs", 350).coerceIn(0, 5000)

        prefs.edit().putBoolean("running", true).putString("status", "Этап 1/3").apply()
        blockedStreak.set(0)

        val first = db.getByState(States.QUEUED)
        processList(db, first, need, delay, "Этап 1/3 • первый проход", false)

        if (shouldStop(prefs)) return

        for (pass in 2..need) {
            val arr = db.getByState(States.PRELIM, clearCount = pass - 1)
            prefs.edit().putString("status", "Этап 2/3 • подтверждение $pass/$need • ${arr.size}").apply()
            processList(db, arr, need, delay, "подтверждение $pass/$need", pass % 2 == 0)
            if (shouldStop(prefs)) return
        }

        for (retry in 1..retries) {
            if (shouldStop(prefs)) return
            val arr = db.getByState(States.UNKNOWN, scoreDesc = true)
            if (arr.isEmpty()) break

            prefs.edit().putString(
                "status",
                "Этап 3/3 • ошибки $retry/$retries • сильные → слабые • ${arr.size}"
            ).apply()

            for (c in arr) {
                waitIfPaused(prefs)
                if (shouldStop(prefs)) return
                var updated = checkOne(db, c, need, "усиленная $retry", retry % 2 == 0)
                if (updated.state == States.PRELIM) {
                    while (updated.state == States.PRELIM && updated.clearCount < need) {
                        sleep(delay.coerceAtLeast(250))
                        updated = checkOne(
                            db, updated, need, "доп. подтверждение",
                            updated.clearCount % 2 == 1
                        )
                        if (updated.state != States.PRELIM) break
                    }
                }
                sleep(delay)
                if (blockedStreak.get() >= 8) {
                    prefs.edit()
                        .putBoolean("paused", true)
                        .putString("status", "YouTube начал ограничивать запросы — автопауза")
                        .apply()
                    updateNotification("Автопауза: YouTube ограничивает запросы")
                    return
                }
            }
        }

        prefs.edit().putString("status", "Проверка завершена").apply()
        updateNotification("Проверка завершена")
    }

    private fun processList(
        db: Db,
        list: List<Candidate>,
        need: Int,
        delay: Int,
        label: String,
        about: Boolean
    ) {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().putString("status", "$label • ${list.size}").apply()

        for ((index, c) in list.withIndex()) {
            waitIfPaused(prefs)
            if (shouldStop(prefs)) return
            checkOne(db, c, need, label, about)
            if (index % 20 == 0) {
                updateNotification("$label • ${index + 1}/${list.size}")
            }
            sleep(delay)
        }
    }

    private fun checkOne(db: Db, c: Candidate, need: Int, label: String, about: Boolean): Candidate {
        val r = DirectChecker.probe(c.handle, about)
        val next = when (r.kind) {
            ProbeResult.OCCUPIED -> {
                blockedStreak.set(0)
                c.copy(
                    state = States.BUSY,
                    reason = "канал найден • ${r.evidence}",
                    checks = c.checks + 1,
                    channelId = r.channelId,
                    lastError = null
                )
            }
            ProbeResult.NOT_FOUND -> {
                blockedStreak.set(0)
                val clear = c.clearCount + 1
                c.copy(
                    state = if (clear >= need) States.STRONG else States.PRELIM,
                    reason = if (clear >= need)
                        "$clear/$need независимых «не найден»"
                    else
                        "не найден $clear/$need • нужно подтверждение",
                    clearCount = clear,
                    checks = c.checks + 1,
                    lastError = null
                )
            }
            ProbeResult.INVALID -> c.copy(
                state = States.INVALID,
                reason = r.evidence,
                checks = c.checks + 1,
                lastError = "invalid"
            )
            ProbeResult.BLOCKED -> {
                blockedStreak.incrementAndGet()
                c.copy(
                    state = States.UNKNOWN,
                    reason = "ограничение • ${r.evidence}",
                    checks = c.checks + 1,
                    lastError = r.evidence
                )
            }
            else -> {
                if (r.evidence.contains("429") || r.evidence.contains("403") || r.evidence.contains("captcha")) {
                    blockedStreak.incrementAndGet()
                } else {
                    blockedStreak.set((blockedStreak.get() - 1).coerceAtLeast(0))
                }
                c.copy(
                    state = States.UNKNOWN,
                    reason = "не подтверждено • ${r.evidence}",
                    checks = c.checks + 1,
                    lastError = r.evidence
                )
            }
        }
        db.update(next)
        return next
    }

    private fun waitIfPaused(prefs: android.content.SharedPreferences) {
        while (prefs.getBoolean("paused", false) && !prefs.getBoolean("stop", false)) {
            updateNotification("Пауза — прогресс сохранён")
            sleep(350)
        }
    }

    private fun shouldStop(prefs: android.content.SharedPreferences): Boolean =
        prefs.getBoolean("stop", false)

    private fun sleep(ms: Int) {
        if (ms <= 0) return
        try { Thread.sleep(ms.toLong()) } catch (_: InterruptedException) {}
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Проверка YouTube handles", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("YT 3-Char Direct")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
    }
}
