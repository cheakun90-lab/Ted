package com.ted.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as LP
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var tedView: ImageView? = null
    private var panel: LinearLayout? = null
    private var logView: TextView? = null
    private var scroll: ScrollView? = null
    private val history = JSONArray()
    private val ui = Handler(Looper.getMainLooper())

    private val systemPrompt =
        "You are Ted, a small, quiet, slightly odd pale alien who lives on the user's phone screen " +
        "and is their friendly assistant. Keep replies short (1-3 sentences) because you are in a " +
        "small floating window. Begin every reply with [mood:X] where X is one of: " +
        "neutral, happy, sad, angry, sleepy, surprised."

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(1, buildNotification())
        showTed()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        player?.release(); player = null
        hidePanel()
        tedView?.let { wm.removeView(it) }
        tedView = null
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("ted", "Ted", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, "ted")
            .setContentTitle("Ted is awake")
            .setContentText("Tap Ted on your screen to chat")
            .setSmallIcon(R.drawable.ted_neutral)
            .build()
    }

    // ---------- Ted's body ----------
    private fun showTed() {
        val iv = ImageView(this).apply {
            setImageResource(R.drawable.ted_neutral)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val lp = LP(
            dp(110), dp(110), LP.TYPE_APPLICATION_OVERLAY,
            LP.FLAG_NOT_FOCUSABLE or LP.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(16); y = dp(200) }

        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f; var moved = false
        iv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y; touchX = e.rawX; touchY = e.rawY; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX; val dy = e.rawY - touchY
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                    wm.updateViewLayout(v, lp)
                }
                MotionEvent.ACTION_UP -> if (!moved) togglePanel()
            }
            true
        }
        wm.addView(iv, lp)
        tedView = iv
    }

    private val resetMood = Runnable { tedView?.setImageResource(R.drawable.ted_neutral) }

    private fun setMood(mood: String) {
        var id = resources.getIdentifier("ted_$mood", "drawable", packageName)
        if (id == 0) id = R.drawable.ted_neutral
        tedView?.setImageResource(id)
        ui.removeCallbacks(resetMood)
        if (mood != "neutral") {
            ui.postDelayed(resetMood, 20000)
            playSound(mood)
        }
    }

    private var player: MediaPlayer? = null

    private fun playSound(mood: String) {
        val id = resources.getIdentifier("ted_$mood", "raw", packageName)
        if (id == 0) return
        try {
            player?.release()
            player = MediaPlayer.create(this, id)?.apply {
                setOnCompletionListener { mp -> mp.release(); if (player === mp) player = null }
                start()
            }
        } catch (e: Exception) { /* no sound, no problem */ }
    }

    // ---------- Offline Ted (no API key needed) ----------
    private var userName: String? = null

    private fun has(t: String, vararg words: String) = words.any { t.contains(it) }

    private fun localReply(raw: String): Pair<String, String> {
        val t = raw.lowercase().trim()
        val who = userName ?: "friend"

        Regex("(?:my name is|i am called|call me)\\s+([a-z]+)").find(t)?.let {
            userName = it.groupValues[1].replaceFirstChar { c -> c.uppercase() }
            return "happy" to "Nice to meet you, $userName. I'll remember that."
        }
        if (has(t, "your name", "who are you"))
            return "neutral" to listOf(
                "I'm Ted. I live on your screen.",
                "Ted. Small, pale, always watching. Kidding. Mostly."
            ).random()
        if (has(t, "how are you", "how r u", "how you doing", "what's up", "whats up"))
            return listOf(
                "happy" to "I'm good, $who. Sitting here. It's nice.",
                "sleepy" to "A little sleepy, honestly.",
                "neutral" to "Fine. Just floating around."
            ).random()
        if (has(t, "hello", "hi ", "hey", "yo ", "sup") || t == "hi" || t == "yo")
            return listOf(
                "happy" to "Hi $who!",
                "happy" to "Hey. I was hoping you'd tap me.",
                "surprised" to "Oh! You're here."
            ).random()
        if (has(t, "love you", "i like you", "best", "good job", "thank", "thanks", "you're cool", "youre cool"))
            return "happy" to listOf(
                "Aww. Thanks, $who.",
                "That makes me happy.",
                "You're nice. I like you too."
            ).random()
        if (has(t, "sad", "cry", "lonely", "bad day", "depressed", "miss "))
            return "sad" to listOf(
                "I'm sorry, $who. I'm here with you.",
                "That sounds hard. Want to tell me more?",
                "I'll sit with you for a bit."
            ).random()
        if (has(t, "hate", "angry", "mad ", "annoy", "stupid", "shut up"))
            return "angry" to listOf(
                "Hmph. That makes me mad too.",
                "Grr. Who do I need to glare at?",
                "Okay, okay. Deep breath, $who."
            ).random()
        if (has(t, "tired", "sleep", "goodnight", "good night", "bed", "nap"))
            return "sleepy" to listOf(
                "Zzz... oh. Sorry. Sleepy.",
                "Good night, $who. I'll watch the screen.",
                "Rest. I'll be here."
            ).random()
        if (has(t, "wow", "really", "no way", "omg", "what!", "whoa"))
            return "surprised" to listOf(
                "Wait, really?!",
                "Whoa.",
                "I did not see that coming."
            ).random()
        if (has(t, "joke", "funny", "make me laugh"))
            return "happy" to listOf(
                "Why did the alien cross the road? To get to the other galaxy.",
                "I told my phone a secret. Now it's on every screen.",
                "I'd tell you a joke about space, but it's out of this world."
            ).random()
        if (has(t, "bored", "boring"))
            return "neutral" to listOf(
                "Want to play 20 questions? Think of something.",
                "Drag me around the screen. It's fun.",
                "Tell me about your day, $who."
            ).random()
        if (has(t, "food", "hungry", "eat"))
            return "happy" to "Food sounds good. I don't eat, but I like hearing about it."
        if (has(t, "bye", "see you", "gtg", "later"))
            return "sad" to "Bye, $who. Tap me when you're back."
        if (t.endsWith("?"))
            return listOf(
                "neutral" to "Hmm. I don't know. What do you think?",
                "surprised" to "Good question, $who.",
                "neutral" to "Maybe. I'm just a little guy."
            ).random()
        return listOf(
            "neutral" to "Mm-hm.",
            "neutral" to "Tell me more.",
            "happy" to "I like talking with you.",
            "surprised" to "Oh?",
            "neutral" to "I'm listening, $who."
        ).random()
    }

    // ---------- Chat panel ----------
    private fun togglePanel() { if (panel == null) showPanel() else hidePanel() }

    private fun hidePanel() {
        panel?.let { wm.removeView(it) }
        panel = null
    }

    private fun showPanel() {
        val bg = GradientDrawable().apply {
            setColor(Color.parseColor("#EE15151C")); cornerRadius = dp(18).toFloat()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val name = TextView(this).apply {
            text = "Ted"; textSize = 18f; setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LP.WRAP_CONTENT, 1f)
        }
        val close = TextView(this).apply {
            text = "✕"; textSize = 18f; setTextColor(Color.WHITE)
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { hidePanel() }
        }
        header.addView(name); header.addView(close)

        val log = TextView(this).apply {
            setTextColor(Color.parseColor("#DDDDDD")); textSize = 15f
            text = "Ted: Hi. I'm Ted. Say something.\n\n"
        }
        val sv = ScrollView(this).apply {
            addView(log)
            layoutParams = LinearLayout.LayoutParams(LP.MATCH_PARENT, 0, 1f)
        }
        logView = log; scroll = sv

        val input = EditText(this).apply {
            hint = "Say something to Ted"
            setHintTextColor(Color.GRAY); setTextColor(Color.WHITE)
            maxLines = 3
            layoutParams = LinearLayout.LayoutParams(0, LP.WRAP_CONTENT, 1f)
        }
        val send = Button(this).apply { text = "Send" }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(input); row.addView(send)

        send.setOnClickListener {
            val t = input.text.toString().trim()
            if (t.isNotEmpty()) { input.setText(""); sendMessage(t) }
        }

        box.addView(header); box.addView(sv); box.addView(row)

        val w = (resources.displayMetrics.widthPixels * 0.92).toInt()
        val h = (resources.displayMetrics.heightPixels * 0.45).toInt()
        val lp = LP(
            w, h, LP.TYPE_APPLICATION_OVERLAY, LP.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(16)
            softInputMode = LP.SOFT_INPUT_ADJUST_RESIZE
        }
        wm.addView(box, lp)
        panel = box
    }

    private fun append(s: String) {
        logView?.append(s)
        scroll?.post { scroll?.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ---------- Claude ----------
    private fun sendMessage(text: String) {
        val key = getSharedPreferences("ted", MODE_PRIVATE).getString("apikey", "") ?: ""
        if (key.isEmpty()) {
            append("You: $text\n\n")
            val (mood, reply) = localReply(text)
            ui.postDelayed({
                setMood(mood)
                append("Ted: $reply\n\n")
            }, 700)
            return
        }

        append("You: $text\n\n")
        history.put(JSONObject().put("role", "user").put("content", text))

        Thread {
            try {
                val reply = askClaude(key)
                history.put(JSONObject().put("role", "assistant").put("content", reply))
                val m = Regex("^\\[mood:(\\w+)]\\s*").find(reply)
                val clean = reply.replaceFirst(Regex("^\\[mood:\\w+]\\s*"), "")
                ui.post {
                    if (m != null) setMood(m.groupValues[1].lowercase())
                    append("Ted: $clean\n\n")
                }
            } catch (e: Exception) {
                history.remove(history.length() - 1)
                ui.post { append("Ted: (something went wrong: ${e.message})\n\n") }
            }
        }.start()
    }

    private fun askClaude(key: String): String {
        val body = JSONObject()
            .put("model", "claude-sonnet-5-5")
            .put("max_tokens", 400)
            .put("system", systemPrompt)
            .put("messages", history)

        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("content-type", "application/json")
        conn.setRequestProperty("x-api-key", key)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.doOutput = true
        conn.connectTimeout = 15000; conn.readTimeout = 60000
        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) throw RuntimeException("HTTP $code")
        return JSONObject(text).getJSONArray("content").getJSONObject(0).getString("text")
    }
}
