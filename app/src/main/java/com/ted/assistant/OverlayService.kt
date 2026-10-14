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
import android.os.SystemClock
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
        addTed(dp(16), dp(200))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        player?.release(); player = null
        musicPlayer?.release(); musicPlayer = null
        ui.removeCallbacks(fightTick)
        hidePanel()
        teds.toList().forEach { hideBubble(it); try { wm.removeView(it.root) } catch (e: Exception) {} }
        teds.clear()
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

    // ---------- Ted's bodies (up to two) ----------
    private inner class TedActor(startX: Int, startY: Int) {
        var skin = 0                       // 0 = no skin
        var bubble: TextView? = null
        var bubbleLp: LP? = null
        val root = FrameLayout(this@OverlayService)
        val img = ImageView(this@OverlayService)
        val lp = LP(
            dp(110), dp(110), LP.TYPE_APPLICATION_OVERLAY,
            LP.FLAG_NOT_FOCUSABLE or LP.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = startX; y = startY }
    }

    private val teds = mutableListOf<TedActor>()
    private val skinNames = listOf("skin_warrior", "skin_suit")
    private var curMood = "neutral"

    private fun addTed(sx: Int, sy: Int) {
        val a = TedActor(sx, sy)
        a.img.scaleType = ImageView.ScaleType.FIT_CENTER
        a.root.addView(a.img, FrameLayout.LayoutParams(LP.MATCH_PARENT, LP.MATCH_PARENT))

        val btn = TextView(this).apply {
            text = "\uD83D\uDC55"; textSize = 14f; gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.parseColor("#99000000"))
            }
            setOnClickListener { cycleSkin(a) }
        }
        a.root.addView(btn, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.BOTTOM or Gravity.END))

        attachTouch(a)
        refresh(a)
        wm.addView(a.root, a.lp)
        teds.add(a)
    }

    private fun removeTed(a: TedActor) {
        hideBubble(a)
        try { wm.removeView(a.root) } catch (e: Exception) {}
        teds.remove(a)
        checkFight()
    }

    private fun moodDrawable(mood: String): Int {
        val id = resources.getIdentifier("ted_$mood", "drawable", packageName)
        return if (id == 0) R.drawable.ted_neutral else id
    }

    private fun refresh(a: TedActor) {
        val id = when {
            a.skin > 0 -> resources.getIdentifier(skinNames[a.skin - 1], "drawable", packageName)
            musicOn -> R.drawable.ted_music
            else -> moodDrawable(curMood)
        }
        a.img.setImageResource(if (id == 0) R.drawable.ted_neutral else id)
    }

    private fun refreshAll() { teds.forEach { refresh(it) } }

    private fun cycleSkin(a: TedActor) {
        a.skin = (a.skin + 1) % (skinNames.size + 1)
        refresh(a)
        checkFight()
    }

    // ---------- touch: drag, tap, double-tap, long-press ----------
    private var lastTap = 0L
    private var pendingSingle: Runnable? = null

    private fun onTap() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTap < 320) {
            pendingSingle?.let { ui.removeCallbacks(it) }
            pendingSingle = null
            lastTap = 0
            spawnTed()
        } else {
            lastTap = now
            val r = Runnable { pendingSingle = null; togglePanel() }
            pendingSingle = r
            ui.postDelayed(r, 320)
        }
    }

    private fun spawnTed() {
        if (teds.size >= 2) {
            teds.firstOrNull()?.let { say(it, "Only room for two of us.") }
            return
        }
        val first = teds[0]
        val screenW = resources.displayMetrics.widthPixels
        var nx = first.lp.x + dp(120)
        if (nx + dp(110) > screenW) nx = first.lp.x - dp(120)
        addTed(maxOf(0, nx), first.lp.y)
    }

    private fun attachTouch(a: TedActor) {
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        var moved = false; var longDone = false
        val longPress = Runnable {
            if (!moved && teds.size > 1) { longDone = true; removeTed(a) }
        }
        a.root.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = a.lp.x; startY = a.lp.y; touchX = e.rawX; touchY = e.rawY
                    moved = false; longDone = false
                    ui.postDelayed(longPress, 800)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX; val dy = e.rawY - touchY
                    if (abs(dx) > 10 || abs(dy) > 10) { moved = true; ui.removeCallbacks(longPress) }
                    if (moved) {
                        a.lp.x = startX + dx.toInt(); a.lp.y = startY + dy.toInt()
                        wm.updateViewLayout(a.root, a.lp)
                        moveBubble(a)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    ui.removeCallbacks(longPress)
                    if (!moved && !longDone) onTap()
                }
                MotionEvent.ACTION_CANCEL -> ui.removeCallbacks(longPress)
            }
            true
        }
    }

    // ---------- speech bubbles ----------
    private fun positionBubble(a: TedActor) {
        val lp = a.bubbleLp ?: return
        val screenW = resources.displayMetrics.widthPixels
        lp.x = maxOf(0, minOf(a.lp.x, screenW - dp(230)))
        lp.y = maxOf(0, a.lp.y - dp(52))
    }

    private fun moveBubble(a: TedActor) {
        val b = a.bubble ?: return
        positionBubble(a)
        try { wm.updateViewLayout(b, a.bubbleLp) } catch (e: Exception) {}
    }

    private fun hideBubble(a: TedActor) {
        a.bubble?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        a.bubble = null
        a.bubbleLp = null
    }

    private fun say(a: TedActor, msg: String, ms: Long = 2500) {
        hideBubble(a)
        val b = TextView(this).apply {
            text = msg; textSize = 14f; setTextColor(Color.BLACK)
            maxWidth = dp(220); setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(14).toFloat(); setStroke(dp(2), Color.BLACK)
            }
        }
        val lp = LP(
            LP.WRAP_CONTENT, LP.WRAP_CONTENT, LP.TYPE_APPLICATION_OVERLAY,
            LP.FLAG_NOT_FOCUSABLE or LP.FLAG_NOT_TOUCHABLE or LP.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        a.bubble = b; a.bubbleLp = lp
        positionBubble(a)
        wm.addView(b, lp)
        ui.postDelayed({ if (a.bubble === b) hideBubble(a) }, ms)
    }

    // ---------- two Teds in skins hate each other ----------
    private var fighting = false
    private var fightTurn = 0

    private val insultsAll = listOf(
        "Ugly!", "Go away!", "You're so annoying.", "Copycat!", "Shut up!", "Get lost!",
        "Nobody likes you.", "Loser.", "I hate you.", "Stop staring at me!",
        "You smell like a nebula.", "Boring."
    )

    private fun insult(a: TedActor): String {
        val special = when (skinNames.getOrNull(a.skin - 1)) {
            "skin_warrior" -> listOf("Nice tie. Who dressed you?", "I'll cut you down!", "Come at me, suit boy!")
            "skin_suit" -> listOf("Your sword is fake.", "Cosplayer!", "Nice chains. Very edgy.")
            else -> emptyList()
        }
        return (insultsAll + special + special).random()
    }

    private val fightTick = object : Runnable {
        override fun run() {
            if (teds.size < 2 || teds.any { it.skin == 0 }) { fighting = false; return }
            val speaker = teds[fightTurn % 2]
            fightTurn++
            say(speaker, insult(speaker))
            ui.postDelayed(this, (2800..4500).random().toLong())
        }
    }

    private fun checkFight() {
        val should = teds.size == 2 && teds.all { it.skin > 0 }
        if (should && !fighting) {
            fighting = true; fightTurn = 0
            ui.postDelayed(fightTick, 600)
        }
        if (!should) {
            fighting = false
            ui.removeCallbacks(fightTick)
            teds.forEach { hideBubble(it) }
        }
    }

    // ---------- moods ----------
    private val resetMood = Runnable { curMood = "neutral"; refreshAll() }

    private fun setMood(mood: String) {
        if (musicOn) return
        curMood = mood
        refreshAll()
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

    // ---------- Boombox music ----------
    private var musicOn = false
    private var musicPlayer: MediaPlayer? = null
    private var songIndex = -1
    private val songIds: List<Int> by lazy {
        (1..8).map { resources.getIdentifier("ted_song$it", "raw", packageName) }.filter { it != 0 }
    }

    private fun playSong(idx: Int) {
        if (songIds.isEmpty()) return
        songIndex = idx
        try {
            musicPlayer?.release()
            player?.release(); player = null
            musicPlayer = MediaPlayer.create(this, songIds[idx])?.apply {
                setOnCompletionListener { playSong((songIndex + 1) % songIds.size) }
                start()
            }
            musicOn = musicPlayer != null
            ui.removeCallbacks(resetMood)
            refreshAll()
        } catch (e: Exception) { musicOn = false }
    }

    private fun startMusic() { if (songIds.isNotEmpty()) playSong((0 until songIds.size).random()) }

    private fun nextSong() { if (songIds.isNotEmpty()) playSong((songIndex + 1) % songIds.size) }

    private fun stopMusic() {
        musicPlayer?.release(); musicPlayer = null
        musicOn = false
        curMood = "neutral"
        refreshAll()
    }

    // returns Ted's reply if the message was a music command, otherwise null
    private fun musicCommand(raw: String): String? {
        val t = raw.lowercase()
        if (musicOn && has(t, "next", "skip", "another", "change", "different")) {
            nextSong(); return "Next one!"
        }
        if (musicOn && has(t, "stop", "pause", "quiet", "silence", "turn off", "enough", "off")) {
            stopMusic(); return "Okay. Boombox off."
        }
        if (has(t, "music", "song", "boombox", "radio", "sing", "dance", "party", "tunes", "beat",
                "play some", "play something", "put on")) {
            if (musicOn) return "Already playing. Say 'next' to skip or 'stop' to quit."
            if (songIds.isEmpty()) return "I can't find my songs."
            startMusic()
            return listOf(
                "Got my boombox. Here we go.",
                "Music time!",
                "Hold on. Turning it up."
            ).random()
        }
        return null
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
        if (has(t, "help", "what can you do", "commands"))
            return "happy" to "Say 'play music' for my boombox ('next', 'stop'). Double-tap me to bring a friend, and use the shirt button for skins."
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
        val note = TextView(this).apply {
            text = "\u266A"; textSize = 20f; setTextColor(Color.WHITE)
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener {
                if (musicOn) { stopMusic(); this@OverlayService.append("Ted: Boombox off.\n\n") }
                else { startMusic(); this@OverlayService.append("Ted: Let's go.\n\n") }
            }
        }
        header.addView(name); header.addView(note); header.addView(close)

        val log = TextView(this).apply {
            setTextColor(Color.parseColor("#DDDDDD")); textSize = 15f
            text = "Ted: Hi. I'm Ted. Say something, or say 'play music'.\n\n"
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
        musicCommand(text)?.let { reply ->
            append("You: $text\n\n")
            append("Ted: $reply\n\n")
            return
        }
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
