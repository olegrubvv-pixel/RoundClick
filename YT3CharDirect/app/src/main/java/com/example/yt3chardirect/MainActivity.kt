package com.example.yt3chardirect

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.*
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
    private lateinit var list: ListView
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
        super.onPause()
        handler.removeCallbacks(refresher)
    }

    private fun buildUi() {
        window.statusBarColor = Color.rgb(8, 13, 25)
        window.navigationBarColor = Color.rgb(8, 13, 25)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8,13,25))
            setPadding(dp(14), dp(10), dp(14), dp(14))
        }

        val title = TextView(this).apply {
            text = "YT 3-Char Direct"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }
        val sub = TextView(this).apply {
            text = "Без API • без Cloudflare • прямые запросы с телефона"
            textSize = 12f
            setTextColor(Color.rgb(145,163,194))
            setPadding(0,0,0,dp(8))
        }
        root.addView(title)
        root.addView(sub)

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))

        content.addView(card("1. Кандидаты") {
            handles = EditText(this).apply {
                hint = "@ahj\n@o2h\n@x7t\n..."
                minLines = 5
                maxLines = 8
                gravity = Gravity.TOP
                setTextColor(Color.WHITE)
                setHintTextColor(Color.rgb(110,128,155))
                setBackgroundColor(Color.rgb(11,19,34))
                setPadding(dp(12),dp(10),dp(12),dp(10))
            }
            addView(handles, LinearLayout.LayoutParams(-1, dp(150)))

            val r = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            val importBtn = button("Импорт TXT/CSV") { openImport() }
            val prepareBtn = button("Подготовить") { prepareList() }
            r.addView(importBtn, LinearLayout.LayoutParams(0,dp(48),1f).apply { marginEnd=dp(5) })
            r.addView(prepareBtn, LinearLayout.LayoutParams(0,dp(48),1f).apply { marginStart=dp(5) })
            addView(r)
        })

        content.addView(card("2. Строгость") {
            val row1 = LinearLayout(this@MainActivity).apply { orientation=LinearLayout.HORIZONTAL }
            val passBox = numberBox("Подтверждений", prefs.getInt("confirmPasses",2), 2,5) {
                prefs.edit().putInt("confirmPasses", it).apply()
            }
            val retryBox = numberBox("Повторов ошибок", prefs.getInt("retryPasses",3), 1,8) {
                prefs.edit().putInt("retryPasses", it).apply()
            }
            row1.addView(passBox, LinearLayout.LayoutParams(0,-2,1f).apply{marginEnd=dp(5)})
            row1.addView(retryBox, LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=dp(5)})
            addView(row1)

            val delayBox = numberBox("Пауза между запросами, мс", prefs.getInt("delayMs",350), 0,5000) {
                prefs.edit().putInt("delayMs", it).apply()
            }
            addView(delayBox)

            val warning = TextView(this@MainActivity).apply {
                text = "Любой 403/429/CAPTCHA/таймаут/неоднозначный ответ = НЕ ПОДТВЕРЖДЕНО. После основных проходов ошибки проверяются от сильных кандидатов к слабым. Рейтинг сам никого не удаляет."
                textSize = 12f
                setTextColor(Color.rgb(190,202,222))
                setBackgroundColor(Color.rgb(12,22,40))
                setPadding(dp(10),dp(10),dp(10),dp(10))
            }
            addView(warning)
        })

        content.addView(card("3. Проверка") {
            stats = TextView(this@MainActivity).apply {
                textSize = 13f
                setTextColor(Color.WHITE)
                setPadding(0,0,0,dp(8))
            }
            status = TextView(this@MainActivity).apply {
                textSize = 12f
                setTextColor(Color.rgb(145,163,194))
                setPadding(0,0,0,dp(8))
            }
            addView(stats)
            addView(status)

            val r = LinearLayout(this@MainActivity).apply { orientation=LinearLayout.HORIZONTAL }
            startBtn = button("Начать") { startChecking() }
            pauseBtn = button("Пауза") { togglePause() }
            val reset = button("Сброс") { resetResults() }
            r.addView(startBtn, LinearLayout.LayoutParams(0,dp(48),1f).apply{marginEnd=dp(4)})
            r.addView(pauseBtn, LinearLayout.LayoutParams(0,dp(48),1f).apply{setMargins(dp(4),0,dp(4),0)})
            r.addView(reset, LinearLayout.LayoutParams(0,dp(48),1f).apply{marginStart=dp(4)})
            addView(r)
        })

        content.addView(card("4. Результаты") {
            val tabs = HorizontalScrollView(this@MainActivity)
            val row = LinearLayout(this@MainActivity).apply { orientation=LinearLayout.HORIZONTAL }
            listOf(
                "Все" to "all",
                "Сильные" to "strong",
                "Не найден 1×" to "prelim",
                "Заняты" to "busy",
                "Не подтверждены" to "unknown",
                "Невалидные" to "invalid"
            ).forEach { (label,key) ->
                row.addView(button(label) { filter=key; refreshList() }, LinearLayout.LayoutParams(-2,dp(44)).apply{marginEnd=dp(6)})
            }
            tabs.addView(row)
            addView(tabs)

            list = ListView(this@MainActivity).apply {
                dividerHeight = 1
                setBackgroundColor(Color.rgb(11,19,34))
            }
            addView(list, LinearLayout.LayoutParams(-1, dp(430)))

            addView(button("Экспорт сильных TXT") { exportStrong() }, LinearLayout.LayoutParams(-1,dp(48)))
        })

        val finalWarn = TextView(this).apply {
            text = "Важно: несколько 404/«не найдено» означают только отсутствие публичной страницы. YouTube всё равно может не разрешить назначить этот handle. Финальный ответ даёт только попытка сохранить handle в самом YouTube."
            textSize = 11f
            setTextColor(Color.rgb(120,138,165))
            setPadding(dp(4),dp(10),dp(4),dp(10))
        }
        content.addView(finalWarn)

        setContentView(root)
    }

    private fun card(titleText: String, builder: LinearLayout.() -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13),dp(13),dp(13),dp(13))
            setBackgroundColor(Color.rgb(17,24,39))
        }
        val t = TextView(this).apply {
            text = titleText
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0,0,0,dp(9))
        }
        box.addView(t)
        box.builder()
        return LinearLayout(this).apply {
            setPadding(0,dp(5),0,dp(5))
            addView(box, LinearLayout.LayoutParams(-1,-2))
        }
    }

    private fun button(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(37,54,83))
            setOnClickListener { action() }
        }

    private fun numberBox(label: String, initial: Int, min: Int, max: Int, onChange: (Int)->Unit): View {
        val box = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(0,0,0,dp(8))
        }
        val l = TextView(this).apply {
            text=label
            textSize=11f
            setTextColor(Color.rgb(145,163,194))
        }
        val e = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(initial.toString())
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(11,19,34))
            setPadding(dp(10),dp(8),dp(10),dp(8))
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val v = text.toString().toIntOrNull()?.coerceIn(min,max) ?: initial
                    setText(v.toString())
                    onChange(v)
                }
            }
        }
        box.addView(l)
        box.addView(e, LinearLayout.LayoutParams(-1,dp(48)))
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
        if (db.total() == 0) {
            toast("Сначала вставь список")
            return
        }
        if (!prefs.getBoolean("running", false)) db.resetNetworkStates()
        prefs.edit().putBoolean("paused", false).putBoolean("stop", false).apply()
        val i = Intent(this, CheckService::class.java).apply { action = CheckService.ACTION_START }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        toast("Проверка запущена")
        refresh()
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
        stats.text =
            "Всего ${c["total"] ?: 0}   •   Сильные ${c["strong"] ?: 0}\n" +
            "Не найден 1× ${c["prelim"] ?: 0}   •   Заняты ${c["busy"] ?: 0}\n" +
            "Не подтверждены ${c["unknown"] ?: 0}   •   Невалидные ${c["invalid"] ?: 0}"

        val running = prefs.getBoolean("running", false)
        val paused = prefs.getBoolean("paused", false)
        status.text = prefs.getString("status", "Готов")
        pauseBtn.text = if (paused) "Продолжить" else "Пауза"
        startBtn.text = if (running) "Идёт…" else "Начать"
        startBtn.isEnabled = !running
        refreshList()
    }

    private fun refreshList() {
        val arr = db.list(filter)
        val lines = arr.map {
            val badge = when(it.state) {
                States.STRONG -> "СИЛЬНЫЙ"
                States.PRELIM -> "НЕ НАЙДЕН 1×"
                States.BUSY -> "КАНАЛ ЕСТЬ"
                States.UNKNOWN -> "НЕ ПОДТВ."
                States.INVALID -> "НЕВАЛИДЕН"
                else -> "ОЖИДАЕТ"
            }
            "@${it.handle}   [$badge]   сила ${it.score}/100\n${it.reason}"
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, lines)
    }

    private fun openImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "text/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, 500)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 500 && resultCode == RESULT_OK) {
            data?.data?.let { importFile(it) }
        } else if (requestCode == 600 && resultCode == RESULT_OK) {
            data?.data?.let { writeExport(it) }
        }
    }

    private fun importFile(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input)).readText()
            } ?: ""
            handles.setText(text)
            prepareList()
        } catch (e: Exception) {
            toast("Ошибка импорта: ${e.message}")
        }
    }

    private fun exportStrong() {
        val arr = db.list("strong", 100000)
        if (arr.isEmpty()) {
            toast("Сильных кандидатов пока нет")
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "youtube_3char_strong_${arr.size}.txt")
        }
        startActivityForResult(intent, 600)
    }

    private fun writeExport(uri: Uri) {
        try {
            val arr = db.list("strong", 100000)
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { w ->
                arr.sortedByDescending { it.score }.forEach {
                    w.append("@${it.handle}\tсила ${it.score}/100\tне найден ${it.clearCount}x\n")
                }
            }
            toast("Экспортировано: ${arr.size}")
        } catch (e: Exception) {
            toast("Ошибка экспорта: ${e.message}")
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 901)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
