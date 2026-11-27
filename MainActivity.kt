package com.sawttarjama.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import org.vosk.Model
import org.vosk.Recognizer
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val requestCapture = 41
    private lateinit var status: TextView
    private lateinit var original: TextView
    private lateinit var translated: TextView
    private lateinit var startButton: Button
    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private val running = AtomicBoolean(false)
    private var model: Model? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        status.text = "جاري تجهيز محرك التعرف على الكلام..."
        thread {
            try {
                model = loadModel()
                runOnUiThread { status.text = "جاهز • اضغط بدء الترجمة" }
            } catch (e: Exception) {
                runOnUiThread { status.text = "تعذر تجهيز المحرك: ${e.message}" }
            }
        }
    }

    private fun buildUi() {
        val bg = 0xFF09131F.toInt()
        val panel = 0xFF142335.toInt()
        val mint = 0xFF49D6B0.toInt()
        val white = 0xFFF3F7FB.toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 24)
            setBackgroundColor(bg)
        }
        val brand = TextView(this).apply {
            text = "◉  صوت وترجمة"
            textSize = 27f; setTextColor(mint); gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "ترجمة الصوت الداخلي من الإنجليزية إلى العربية"
            textSize = 14f; setTextColor(0xFF9FB0C2.toInt()); gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            textSize = 14f; setTextColor(mint); gravity = Gravity.CENTER
            setPadding(8, 22, 8, 18)
        }
        startButton = Button(this).apply {
            text = "بدء الترجمة"
            isAllCaps = false
            setTextColor(bg); textSize = 17f
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(mint))
            setOnClickListener { if (running.get()) stopCapture() else requestStart() }
        }
        val originalTitle = sectionTitle("النص الإنجليزي", white)
        original = contentText("سيظهر الكلام الإنجليزي هنا", 0xFFB7C6D5.toInt())
        val translatedTitle = sectionTitle("الترجمة العربية", mint)
        translated = contentText("ستظهر الترجمة العربية هنا", white).apply { textSize = 23f }
        layout.addView(brand)
        layout.addView(subtitle)
        layout.addView(status)
        layout.addView(startButton, LinearLayout.LayoutParams(-1, 58).apply { bottomMargin = 24 })
        addPanel(layout, panel, originalTitle, original)
        addPanel(layout, panel, translatedTitle, translated)
        val note = TextView(this).apply {
            text = "ملاحظة: يعمل مع التطبيقات التي تسمح بالتقاط الصوت الداخلي (Android 10+). بعض التطبيقات تمنع مشاركة الصوت."
            textSize = 12f; setTextColor(0xFF8294A8.toInt()); gravity = Gravity.CENTER
            setPadding(4, 18, 4, 0)
        }
        layout.addView(note)
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun sectionTitle(s: String, color: Int) = TextView(this).apply {
        text = s; textSize = 15f; setTextColor(color); setPadding(0, 0, 0, 10)
    }
    private fun contentText(s: String, color: Int) = TextView(this).apply {
        text = s; textSize = 17f; setTextColor(color); setTextIsSelectable(true)
    }
    private fun addPanel(parent: LinearLayout, color: Int, title: TextView, body: TextView) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(18, 18, 18, 20)
            setBackgroundColor(color)
        }
        box.addView(title); box.addView(body)
        val lp = LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = 16
        parent.addView(box, lp)
    }

    private fun requestStart() {
        if (model == null) { status.text = "انتظر حتى يكتمل تجهيز المحرك"; return }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), requestCapture)
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestCapture && resultCode == RESULT_OK && data != null) {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            startCapture()
        } else status.text = "لم يتم السماح بالتقاط الصوت"
    }

    private fun startCapture() {
        try {
            val p = projection ?: return
            val loadedModel = model ?: return
            val config = android.media.AudioPlaybackCaptureConfiguration.Builder(p)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(16000)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            recorder = AudioRecord.Builder().setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(min, 8192))
                .setAudioPlaybackCaptureConfig(config).build()
            recorder?.startRecording()
            running.set(true)
            startButton.text = "إيقاف الترجمة"
            status.text = "يتم الاستماع إلى الصوت الداخلي..."
            worker = thread(name = "AudioTranslation") {
                val rec = Recognizer(loadedModel, 16000.0f)
                val buffer = ShortArray(4096)
                try {
                    while (running.get()) {
                        val count = recorder?.read(buffer, 0, buffer.size) ?: 0
                        if (count > 0 && rec.acceptWaveForm(buffer, count)) {
                            val json = JSONObject(rec.result)
                            val words = json.optString("text", "").trim()
                            if (words.isNotEmpty()) processText(words)
                        } else if (count > 0) {
                            val partial = JSONObject(rec.partialResult).optString("partial", "")
                            if (partial.isNotBlank()) runOnUiThread { original.text = partial }
                        }
                    }
                } catch (_: Exception) {
                } finally { rec.close() }
            }
        } catch (e: Exception) {
            status.text = "تعذر بدء الالتقاط: ${e.message}"
            stopCapture()
        }
    }

    private fun processText(words: String) {
        runOnUiThread { original.text = words }
        thread {
            val result = translate(words)
            if (result.isNotBlank()) runOnUiThread { translated.text = result }
        }
    }

    private fun translate(text: String): String {
        return try {
            val encoded = java.net.URLEncoder.encode(text, "UTF-8")
            val url = URL("https://api.mymemory.translated.net/get?q=$encoded&langpair=en|ar")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000; conn.readTimeout = 10000
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            JSONObject(body).getJSONObject("responseData").optString("translatedText", "")
        } catch (_: Exception) { "" }
    }

    private fun stopCapture() {
        running.set(false)
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release(); recorder = null
        projection?.stop(); projection = null
        startButton.text = "بدء الترجمة"
        status.text = "تم إيقاف الترجمة"
    }

    private fun loadModel(): Model {
        val dir = File(filesDir, "vosk-model-small-en-us-0.15")
        if (!File(dir, "am").exists()) {
            val zipFile = File(cacheDir, "vosk-model.zip")
            URL("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip").openStream().use { input ->
                FileOutputStream(zipFile).use { output -> input.copyTo(output) }
            }
            ZipInputStream(FileInputStream(zipFile)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val out = File(filesDir, entry.name)
                    if (entry.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { zis.copyTo(it) }
                    }
                    zis.closeEntry(); entry = zis.nextEntry
                }
            }
            zipFile.delete()
        }
        return Model(dir.absolutePath)
    }

    override fun onDestroy() {
        stopCapture()
        model?.close()
        super.onDestroy()
    }
}
