package com.example.yt3chardirect

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : Activity() {
    private lateinit var db: Db
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var handles: EditText
    private lateinit var stats: TextView
    private lateinit var status: TextView
    private lateinit var results: ListView
    private lateinit var startBtn: Button
    private lateinit var pauseBtn: Button
    private var filter = "all"

    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Db(this)
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        requestNotificationPermission()
        buildUi()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun buildUi() {
        window.statusBarColor = Color.rgb(8, 13, 25)
        window.navigationBarColor = Color.rgb(8, 13, 25)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(14))
            setBackgroundColor(Color.rgb(8, 13, 25))
        }

        root.addView(TextView(this).apply {
            text = "YT 3-Char Direct"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Без API • без Cloudflare • прямые запросы с телефона"
            textSize = 12f
            setTextColor(Color.rgb(145, 163, 194))
            setPadding(0, 0, 0, dp(8))
        })

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        content.addView(section("1. Кандидаты") { box ->
            handles = EditText(this@MainActivity).apply {
                hint = "@ahj\n@o2h\n@x7t\n..."
                minLines = 5
                maxLines = 8
                gravity = Gravity.TOP
                setTextColor(Color.WHITE)
                setHintTextColor(Color.rgb(110, 128, 155))
                setBackgroundColor(Color.rgb(11, 19, 34))
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            box.addView(handles, LinearLayout.LayoutParams(-1, dp(150)))

            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(makeButton("Импорт TXT/CSV") { openImport() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
            row.addView(makeButton("Подготовить") { prepareList() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5) })
            box.addView(row)
        })

        content.addView(section("2. Строгость") { box ->
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(numberBox("Подтверждений", prefs.getInt("confirmPasses", 2), 2, 5) {
                prefs.edit().putInt("confirmPasses", it).apply()
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) })
            row.addView(numberBox("Повторов ошибок", prefs.getInt("retryPasses", 3), 1, 8) {
                prefs.edit().putInt("retryPasses", it).apply()
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(5) })
            box.addView(row)
            box.addView(numberBox("Пауза между запросами, мс", prefs.getInt("delayMs", 350), 0, 5000) {
                prefs.edit().putInt("delayMs", it).apply()
            })
            box.addView(TextView(this@MainActivity).apply {
                text = "403/429/CAPTCHA/таймаут/неоднозначный ответ = НЕ ПОДТВЕРЖДЕНО. После основных проходов ошибки идут повторно от сильных к слабым. Рейтинг никого не удаляет."
                textSize = 12f
                setTextColor(Color.rgb(190, 202, 222))
                setBackgroundColor(Color.rgb(12, 22, 40))
                setPadding(dp(10), dp(10), dp(10), dp(10))
            })
        })

        content.addView(section("3. Проверка") { box ->
            stats = TextView(this@MainActivity).apply {
                textSize = 13f
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, dp(8))
            }
            status = TextView(this@MainActivity).apply {
                textSize = 12f
                setTextColor(Color.rgb(145, 163, 194))
                setPadding(0, 0, 0, dp(8))
            }
            box.addView(stats)
            box.addView(status)

            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            startBtn = makeButton("Начать") { startChecking() }
            pauseBtn = makeButton("Пауза") { togglePause() }
            row.addView(startBtn, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) })
            row.addView(pauseBtn, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
            row.addView(makeButton("Сброс") { resetResults() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4) })
            box.addView(row)
        })

        content.addView(section("4. Результаты") { box ->
            val tabs = HorizontalScrollView(this@MainActivity)
            val tabRow = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(
                "Все" to "all", "Сильные" to "strong", "Не найден 1×" to "prelim",
                "Заняты" to "busy", "Не подтверждены" to "unknown", "Невалидные" to "invalid"
            ).forEach { (label, key) ->
                tabRow.addView(makeButton(label) { filter = key; refreshList() }, LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(6) })
            }
            tabs.addView(tabRow)
            box.addView(tabs)

            results = ListView(this@MainActivity).apply {
                dividerHeight = 1
                setBackgroundColor(Color.rgb(11, 19, 34))
            }
            box.addView(results, LinearLayout.LayoutParams(-1, dp(430)))
            box.addView(makeButton("Экспорт сильных TXT") { exportStrong() }, LinearLayout.LayoutParams(-1, dp(48)))
        })

        content.addView(TextView(this).apply {
            text = "Важно: несколько 404 означают только отсутствие публичной страницы. YouTube может всё равно не разрешить назначить handle. Финальная проверка — попытка сохранить handle в самом YouTube."
            textSize = 11f
            setTextColor(Color.rgb(120, 138, 165))
            setPadding(dp(4), dp(10), dp(4), dp(10))
        })

        setContentView(root)
    }

    private fun section(title: String, fill: (LinearLayout) -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(13), dp(13), dp(13))
            setBackgroundColor(Color.rgb(17, 24, 39))
        }
        box.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(9))
        })
        fill(box)
        return LinearLayout(this).apply {
            setPadding(0, dp(5), 0, dp(5))
            addView(box, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun makeButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(37, 54, 83))
        setOnClickListener { action() }
    }

    private fun numberBox(label: String, initial: Int, min: Int, max: Int, save: (Int) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(Color.rgb(145, 163, 194))
        })
        val edit = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(initial.toString())
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(11, 19, 34))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnFocusChangeListener { _, focused ->
                if (!focused) {
                    val value = text.toString().toIntOrNull()?.coerceIn(min, max) ?: initial
                    setText(value.toString())
                    save(value)
                }
            }
        }
        box.addView(edit, LinearLayout.LayoutParams(-1, dp(48)))
        box.setPadding(0, 0, 0, dp(8))
        return box
    }

    private fun prepareList() {
        val parsed = handles.text.toString()
            .split(Regex("[\\n\\r,;\\t ]+"))
            .map { it.trim().removePrefix("@").lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
        db.replaceAll(parsed)
        toast("Подготовлено: ${parsed.size}")
        refresh()
    }

    private fun startChecking() {
        if (db.total() == 0) prepareList()
        if (db.total() == 0) { toast("Сначала вставь список"); return }
        db.resetNetworkStates()
        prefs.edit().putBoolean("paused", false).putBoolean("stop", false).apply()
        val intent = Intent(this, CheckService::class.java).apply { action = CheckService.ACTION_START }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        toast("Проверка запущена")
    }

    private fun togglePause() {
        val paused = !prefs.getBoolean("paused", false)
        prefs.edit().putBoolean("paused", paused).apply()
        toast(if (paused) "Пауза" else "Продолжено")
        refresh()
    }

    private fun resetResults() {
        prefs.edit().putBoolean("stop", true).putBoolean("paused", false).putBoolean("running", false).apply()
        stopService(Intent(this, CheckService::class.java))
        db.resetNetworkStates()
        toast("Результаты сброшены")
        refresh()
    }

    private fun refresh() {
        val c = db.counts()
        stats.text = "Всего ${c["total"] ?: 0} • Сильные ${c["strong"] ?: 0}\n" +
            "Не найден 1× ${c["prelim"] ?: 0} • Заняты ${c["busy"] ?: 0}\n" +
            "Не подтверждены ${c["unknown"] ?: 0} • Невалидные ${c["invalid"] ?: 0}"
        val running = prefs.getBoolean("running", false)
        val paused = prefs.getBoolean("paused", false)
        status.text = prefs.getString("status", "Готов")
        pauseBtn.text = if (paused) "Продолжить" else "Пауза"
        startBtn.text = if (running) "Идёт…" else "Начать"
        startBtn.isEnabled = !running
        refreshList()
    }

    private fun refreshList() {
        val lines = db.list(filter).map {
            val badge = when (it.state) {
                States.STRONG -> "СИЛЬНЫЙ"
                States.PRELIM -> "НЕ НАЙДЕН 1×"
                States.BUSY -> "КАНАЛ ЕСТЬ"
                States.UNKNOWN -> "НЕ ПОДТВ."
                States.INVALID -> "НЕВАЛИДЕН"
                else -> "ОЖИДАЕТ"
            }
            "@${it.handle}  [$badge]  сила ${it.score}/100\n${it.reason}"
        }
        results.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, lines)
    }

    private fun openImport() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "text/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }, 500)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        if (requestCode == 500) data?.data?.let { importFile(it) }
        if (requestCode == 600) data?.data?.let { writeExport(it) }
    }

    private fun importFile(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.use { input -> BufferedReader(InputStreamReader(input)).readText() } ?: ""
            handles.setText(text)
            prepareList()
        } catch (e: Exception) { toast("Ошибка импорта: ${e.message}") }
    }

    private fun exportStrong() {
        val count = db.list("strong", 100000).size
        if (count == 0) { toast("Сильных кандидатов пока нет"); return }
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "youtube_3char_strong_${count}.txt")
        }, 600)
    }

    private fun writeExport(uri: Uri) {
        try {
            val arr = db.list("strong", 100000).sortedByDescending { it.score }
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { out ->
                arr.forEach { out.append("@${it.handle}\tсила ${it.score}/100\tне найден ${it.clearCount}x\n") }
            }
            toast("Экспортировано: ${arr.size}")
        } catch (e: Exception) { toast("Ошибка экспорта: ${e.message}") }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 901)
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
