package com.example.voicelist

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

data class ProductItem(var name: String, var bought: Boolean)

class MainActivity : AppCompatActivity() {

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tvStatus: TextView
    private lateinit var btnRecord: Button
    private lateinit var listContainer: LinearLayout
    private lateinit var langSpinner: Spinner

    private val items = mutableListOf<ProductItem>()
    private var isRecording = false
    private var isWaitingWakeWord = true
    private var selectedLang = "ru-RU"

    private val pauseHandler = Handler(Looper.getMainLooper())
    private var lastPartialText = ""
    private val pauseRunnable = Runnable {
        if (isRecording && lastPartialText.isNotEmpty()) {
            addItem(lastPartialText)
            lastPartialText = ""
            tvStatus.text = "Добавлено по паузе (3 сек). Говорите дальше..."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnRecord = findViewById(R.id.btnRecord)
        listContainer = findViewById(R.id.listContainer)
        langSpinner = findViewById(R.id.langSpinner)
        val etManual: EditText = findViewById(R.id.etManual)

        val languages = listOf("Русский (RU)" to "ru-RU", "Українська (UA)" to "uk-UA", "English (EN)" to "en-US")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languages.map { it.first })
        langSpinner.adapter = adapter
        langSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedLang = languages[position].second
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        } else {
            initSpeechRecognizer()
        }

        loadData()
        renderTable()

        btnRecord.setOnClickListener {
            if (!isRecording) startRecordingMode() else stopRecordingMode()
        }

        findViewById<Button>(R.id.btnAdd).setOnClickListener {
            val text = etManual.text.toString().trim()
            if (text.isNotEmpty()) {
                addItem(text)
                etManual.text.clear()
            }
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveData()
            Toast.makeText(this, "Список сохранён!", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnClear).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Очистка")
                .setMessage("Удалить весь список?")
                .setPositiveButton("Да") { _, _ ->
                    items.clear()
                    saveData()
                    renderTable()
                }
                .setNegativeButton("Нет", null)
                .show()
        }
    }
    private fun initSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                restartListening()
            }

            override fun onResults(results: Bundle?) {
                pauseHandler.removeCallbacks(pauseRunnable)
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val spokenText = matches.lowercase(Locale.getDefault()).trim()
                    handleSpokenText(spokenText, isFinal = true)
                }
                lastPartialText = ""
                restartListening()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val partial = matches.lowercase(Locale.getDefault()).trim()
                    tvStatus.text = "Слышу: $partial"

                    if (isWaitingWakeWord && (partial.contains("запиши") || partial.contains("запишіть") || partial.contains("record"))) {
                        startRecordingMode()
                        return
                    }

                    if (isRecording) {
                        lastPartialText = partial
                        pauseHandler.removeCallbacks(pauseRunnable)
                        pauseHandler.postDelayed(pauseRunnable, 3000)
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        startListening()
    }

    private fun handleSpokenText(text: String, isFinal: Boolean) {
        if (isWaitingWakeWord) {
            if (text.contains("запиши") || text.contains("запишіть") || text.contains("record")) {
                startRecordingMode()
            }
            return
        }

        if (isRecording && isFinal) {
            val regex = Regex("\\b(следующий|далее|дальше|наступний|далі|next)\\b", RegexOption.IGNORE_CASE)
            val parts = text.split(regex).map { it.replace(Regex("^(запиши|запишіть|record)\\s*", RegexOption.IGNORE_CASE), "").trim() }
            for (part in parts) {
                if (part.isNotEmpty()) {
                    addItem(part)
                }
            }
        }
    }

    private fun startRecordingMode() {
        isWaitingWakeWord = false
        isRecording = true
        btnRecord.text = "⏹ Стоп"
        btnRecord.setBackgroundColor(Color.parseColor("#27AE60"))
        tvStatus.text = "Идёт запись! Называйте продукты..."
        restartListening()
    }

    private fun stopRecordingMode() {
        isRecording = false
        isWaitingWakeWord = true
        pauseHandler.removeCallbacks(pauseRunnable)
        btnRecord.text = "🎙 Запись"
        btnRecord.setBackgroundColor(Color.parseColor("#E74C3C"))
        tvStatus.text = "Остановлено. Нажмите 'Запись' или скажите 'Запиши'"
        restartListening()
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, selectedLang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        try {
            speechRecognizer.startListening(intent)
        } catch (e: Exception) {}
    }

    private fun restartListening() {
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                speechRecognizer.cancel()
                startListening()
            } catch (e: Exception) {}
        }, 200)
    }

    private fun addItem(rawName: String) {
        val clean = rawName.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        items.add(ProductItem(clean, false))
        saveData()
        renderTable()
    }

    private fun renderTable() {
        listContainer.removeAllViews()

        items.forEachIndexed { index, item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8, 16, 8, 16)
            }

            val tvNum = TextView(this).apply {
                text = "${index + 1}."
                textSize = 16f
                width = 80
                setTextColor(Color.DKGRAY)
            }

            val tvName = TextView(this).apply {
                text = item.name
                textSize = 18f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                if (item.bought) {
                    setTextColor(Color.parseColor("#27AE60"))
                    setTypeface(null, Typeface.BOLD)
                } else {
                    setTextColor(Color.BLACK)
                }
                setOnClickListener { showEditDialog(index) }
            }

            val statusView = TextView(this).apply {
                gravity = Gravity.CENTER
                width = 90
                height = 90
                if (item.bought) {
                    text = "✔"
                    textSize = 22f
                    setTextColor(Color.parseColor("#27AE60"))
                    background = null
                } else {
                    text = ""
                    val circle = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#F1C40F"))
                    }
                    background = circle
                    layoutParams = LinearLayout.LayoutParams(60, 60).apply {
                        marginEnd = 16
                    }
                }
                setOnClickListener {
                    item.bought = !item.bought
                    saveData()
                    renderTable()
                }
            }

            row.addView(tvNum)
            row.addView(tvName)
            row.addView(statusView)
            listContainer.addView(row)

            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
                setBackgroundColor(Color.parseColor("#EEEEEE"))
            }
            listContainer.addView(divider)
        }
    }

    private fun showEditDialog(index: Int) {
        val input = EditText(this).apply { setText(items[index].name) }
        AlertDialog.Builder(this)
            .setTitle("Редактировать пункт")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val newText = input.text.toString().trim()
                if (newText.isNotEmpty()) {
                    items[index].name = newText
                    saveData()
                    renderTable()
                }
            }
            .setNeutralButton("Удалить") { _, _ ->
                items.removeAt(index)
                saveData()
                renderTable()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun saveData() {
        val prefs = getSharedPreferences("ShoppingListPrefs", Context.MODE_PRIVATE)
        val jsonArray = JSONArray()
        items.forEach {
            val obj = JSONObject()
            obj.put("name", it.name)
            obj.put("bought", it.bought)
            jsonArray.put(obj)
        }
        prefs.edit().putString("items", jsonArray.toString()).apply()
    }

    private fun loadData() {
        val prefs = getSharedPreferences("ShoppingListPrefs", Context.MODE_PRIVATE)
        val jsonString = prefs.getString("items", null) ?: return
        val jsonArray = JSONArray(jsonString)
        items.clear()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            items.add(ProductItem(obj.getString("name"), obj.getBoolean("bought")))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults == PackageManager.PERMISSION_GRANTED) {
            initSpeechRecognizer()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::speechRecognizer.isInitialized) {
            speechRecognizer.destroy()
        }
    }
}
