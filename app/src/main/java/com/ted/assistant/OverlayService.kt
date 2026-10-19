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
import android.view.View
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
        loadStats()
        ui.postDelayed(careTick, 20000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        player?.release(); player = null
        musicPlayer?.release(); musicPlayer = null
        ui.removeCallbacks(fightTick); ui.removeCallbacks(koReset); ui.removeCallbacks(careTick); ui.removeCallbacks(duelTick)
        stopTheme()
        saveStats()
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
        var hp = 100; var hunger = 80; var happy = 80; var energy = 80; var wins = 0
        var xp = 0; var level = 1
        var lastWarn = 0L
        val hpBg = FrameLayout(this@OverlayService)
        val hpFill = View(this@OverlayService)
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

        a.hpBg.setBackgroundColor(Color.parseColor("#AA000000"))
        a.hpBg.addView(a.hpFill, FrameLayout.LayoutParams(dp(94), LP.MATCH_PARENT))
        a.hpBg.visibility = View.GONE
        a.root.addView(a.hpBg, FrameLayout.LayoutParams(dp(98), dp(10), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        updateHp(a)

        attachTouch(a)
        refresh(a)
        wm.addView(a.root, a.lp)
        teds.add(a)
    }

    private fun removeTed(a: TedActor) {
        hideBubble(a)
        if (activeTed === a) activeTed = null
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

    private fun onTap(a: TedActor) {
        val now = SystemClock.uptimeMillis()
        if (now - lastTap < 320) {
            pendingSingle?.let { ui.removeCallbacks(it) }
            pendingSingle = null
            lastTap = 0
            spawnTed()
        } else {
            lastTap = now
            val r = Runnable {
                pendingSingle = null
                if (panel != null && activeTed !== a && teds.contains(a)) {
                    activeTed = a; updateStatLine()
                } else {
                    activeTed = a; togglePanel()
                }
            }
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
                    if (!moved && !longDone) onTap(a)
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

    // ---------- fighting: two skinned Teds battle ----------
    private var fighting = false

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

    private val koReset = Runnable {
        teds.forEach { it.hp = 100; updateHp(it) }
        if (teds.size == 2 && teds.all { it.skin > 0 }) ui.postDelayed(fightTick, 800) else fighting = false
    }

    private val fightTick = object : Runnable {
        override fun run() {
            if (teds.size < 2 || teds.any { it.skin == 0 }) { fighting = false; return }
            val att = teds.random()
            val def = if (att === teds[0]) teds[1] else teds[0]
            val warrior = skinNames.getOrNull(att.skin - 1) == "skin_warrior"
            var dmg = if (warrior) (8..16).random() else (6..12).random()
            dmg += (att.level - 1).coerceAtMost(8)               // higher level hits harder
            if (att.hunger < 20) dmg /= 2                       // too hungry to hit hard
            val crit = (1..10).random() == 1
            if (crit) dmg *= 2
            val blocked = skinNames.getOrNull(def.skin - 1) == "skin_suit" && (1..4).random() == 1
            if (blocked) dmg = 0                                 // suit skin can block
            def.hp = (def.hp - dmg).coerceAtLeast(0)
            att.energy = cl(att.energy - 2)
            updateHp(def)
            say(att, insult(att))
            ui.postDelayed({
                if (fighting && teds.contains(def)) {
                    say(def, if (blocked) "Blocked!" else if (crit) "CRIT -$dmg!" else "-$dmg")
                }
            }, 500)
            if (def.hp == 0) {
                ui.postDelayed({
                    if (fighting && teds.contains(att) && teds.contains(def)) knockOut(att, def)
                }, 1500)
                return
            }
            ui.postDelayed(this, (2800..4500).random().toLong())
        }
    }

    private fun knockOut(winner: TedActor, loser: TedActor) {
        winner.wins++
        winner.happy = cl(winner.happy + 15)
        loser.happy = cl(loser.happy - 15)
        coins += 25; addXp(winner, 25); addXp(loser, 8)
        say(winner, "KO! I win! (${winner.wins})", 3200)
        say(loser, "ugh...", 3200)
        saveStats(); updateStatLine()
        ui.postDelayed(koReset, 4000)
    }

    private fun checkFight() {
        val should = teds.size == 2 && teds.all { it.skin > 0 }
        if (should && !fighting) {
            fighting = true
            teds.forEach { it.hp = 100; updateHp(it); it.hpBg.visibility = View.VISIBLE }
            ui.postDelayed(fightTick, 600)
        }
        if (!should) {
            fighting = false
            ui.removeCallbacks(fightTick); ui.removeCallbacks(koReset)
            teds.forEach { hideBubble(it); it.hpBg.visibility = View.GONE }
        }
    }

    private fun updateHp(a: TedActor) {
        val lp = a.hpFill.layoutParams
        lp.width = dp(94) * a.hp / 100
        a.hpFill.layoutParams = lp
        a.hpFill.setBackgroundColor(
            Color.parseColor(if (a.hp > 50) "#43A047" else if (a.hp > 25) "#FB8C00" else "#E53935")
        )
    }

    // ---------- pet care: hunger, happiness, energy ----------
    private fun cl(v: Int) = v.coerceIn(0, 100)

    private var statView: TextView? = null
    private var activeTed: TedActor? = null
    private fun cur(): TedActor? = activeTed?.takeIf { teds.contains(it) } ?: teds.firstOrNull()

    private var coins = 50
    private fun need(l: Int) = 40 + 10 * l

    private fun updateStatLine() {
        val a = cur() ?: return
        val n = teds.indexOf(a) + 1
        statView?.text = "Ted $n  \u00B7  Lv ${a.level} (${a.xp}/${need(a.level)} XP)  \u00B7  Coins: $coins\n" +
            "\uD83C\uDF4E ${a.hunger}   \u2764 ${a.happy}   \u26A1 ${a.energy}" +
            (if (a.wins > 0) "   \uD83C\uDFC6 ${a.wins}" else "")
    }

    private fun addXp(a: TedActor, n: Int) {
        a.xp += n
        while (a.xp >= need(a.level)) {
            a.xp -= need(a.level); a.level++; coins += 10
            ui.postDelayed({ if (teds.contains(a)) say(a, "Level up! Lv ${a.level}  +10 coins", 3200) }, 700)
        }
    }

    private fun afterCare() { saveStats(); updateStatLine() }

    private fun feed(a: TedActor) {
        if (a.hunger >= 95 && !fighting) { say(a, "I'm stuffed."); return }
        a.hunger = cl(a.hunger + 25); a.happy = cl(a.happy + 5)
        if (fighting) { a.hp = cl(a.hp + 20); updateHp(a) }
        say(a, if (fighting) "Snack break! +HP" else listOf("Yum.", "Nom nom.", "Thanks!").random())
        addXp(a, 3)
        afterCare()
    }

    private fun pet(a: TedActor) {
        if (a.happy < 90) coins += 1
        a.happy = cl(a.happy + 15)
        addXp(a, 2)
        say(a, listOf("Aww.", "That's nice.", "Hehe.").random())
        afterCare()
    }

    private fun play(a: TedActor) {
        if (a.energy < 15) { say(a, "Too tired to play..."); return }
        a.happy = cl(a.happy + 20); a.energy = cl(a.energy - 12); a.hunger = cl(a.hunger - 6)
        say(a, listOf("Wheee!", "Again!", "Catch me!").random())
        coins += 3; addXp(a, 6)
        afterCare()
    }

    private fun nap(a: TedActor) {
        a.energy = cl(a.energy + 35); a.happy = cl(a.happy + 3)
        setMood("sleepy")
        say(a, "Zzz...", 3000)
        addXp(a, 2)
        afterCare()
    }

    private val careTick = object : Runnable {
        override fun run() {
            teds.forEach { a ->
                a.hunger = cl(a.hunger - 3)
                a.happy = cl(a.happy - if (a.hunger < 25) 3 else 2)
                a.energy = cl(a.energy - 1)
                warn(a)
            }
            if (teds.any { it.happy > 50 }) coins += 1
            saveStats(); updateStatLine()
            ui.postDelayed(this, 20000)
        }
    }

    private fun warn(a: TedActor) {
        if (fighting) return
        val now = SystemClock.uptimeMillis()
        if (now - a.lastWarn < 60000) return
        val msg = when {
            a.hunger < 25 -> "I'm hungry..."
            a.energy < 20 -> "So sleepy..."
            a.happy < 25 -> "Pet me? I'm lonely."
            else -> null
        } ?: return
        a.lastWarn = now
        say(a, msg)
    }

    private fun saveStats() {
        val a = teds.firstOrNull() ?: return
        getSharedPreferences("ted", MODE_PRIVATE).edit()
            .putInt("hunger", a.hunger).putInt("happy", a.happy).putInt("energy", a.energy)
            .putInt("coins", coins).putInt("level", a.level).putInt("xp", a.xp).apply()
    }

    private fun loadStats() {
        val a = teds.firstOrNull() ?: return
        val p = getSharedPreferences("ted", MODE_PRIVATE)
        a.hunger = p.getInt("hunger", 80); a.happy = p.getInt("happy", 80); a.energy = p.getInt("energy", 80)
        coins = p.getInt("coins", 50); a.level = p.getInt("level", 1); a.xp = p.getInt("xp", 0)
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
            stopTheme()
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

    // ---------- shop ----------
    private fun buy(a: TedActor, item: String) {
        val cost = when (item) { "pizza" -> 15; "ball" -> 10; else -> 20 }
        if (coins < cost) { say(a, "Not enough coins."); return }
        coins -= cost
        when (item) {
            "pizza" -> {
                a.hunger = cl(a.hunger + 60); a.happy = cl(a.happy + 10)
                if (fighting) { a.hp = cl(a.hp + 30); updateHp(a) }
                say(a, "PIZZA!"); addXp(a, 4)
            }
            "ball" -> {
                a.happy = cl(a.happy + 30); a.energy = cl(a.energy - 5)
                say(a, "Ball! Ball!"); addXp(a, 5)
            }
            else -> {
                a.energy = cl(a.energy + 50)
                if (fighting) { a.hp = 100; updateHp(a); say(a, "Fully healed!") }
                else { a.happy = cl(a.happy + 5); say(a, "Feeling great.") }
                addXp(a, 4)
            }
        }
        afterCare()
    }

    // ---------- chat mini-games ----------
    private var guessTarget = 0
    private var guessTries = 0

    private fun reward(c: Int, xp: Int) {
        coins += c
        cur()?.let { addXp(it, xp) }
        saveStats(); updateStatLine()
    }

    // ---------- Water Pistol Roulette (with the tension theme) ----------
    private var themePlayer: MediaPlayer? = null

    private fun startTheme() {
        stopMusic()
        val id = resources.getIdentifier("ted_theme", "raw", packageName)
        if (id == 0) return
        try {
            themePlayer?.release()
            themePlayer = MediaPlayer.create(this, id)?.apply {
                isLooping = true; setVolume(0.7f, 0.7f); start()
            }
        } catch (e: Exception) { /* no theme, game still works */ }
    }

    private fun stopTheme() { themePlayer?.release(); themePlayer = null }

    // --- Ted vs Ted duel (one chamber holds a splash) ---
    private var duelOn = false
    private var duelLoaded = 0
    private var duelPull = 0

    private val duelTick = object : Runnable {
        override fun run() {
            if (teds.size < 2 || fighting) { duelOn = false; stopTheme(); return }
            duelPull++
            val a = teds[(duelPull - 1) % 2]
            val b = teds[duelPull % 2]
            if (duelPull == duelLoaded) {
                duelOn = false
                ui.postDelayed({ stopTheme() }, 3500)
                say(a, "SPLASH! I'm soaked!", 3000)
                ui.postDelayed({ if (teds.contains(b)) say(b, "Ha! Got you.", 3000) }, 700)
                a.happy = cl(a.happy - 10); b.happy = cl(b.happy + 10)
                coins += 5; addXp(b, 8)
                saveStats(); updateStatLine()
                return
            }
            say(a, listOf("Click.", "Phew.", "Safe...", "Click. Your turn.").random())
            ui.postDelayed(this, 2600)
        }
    }

    private fun startDuel() {
        duelLoaded = (1..6).random(); duelPull = 0; duelOn = true
        startTheme()
        ui.postDelayed(duelTick, 800)
    }

    // --- you vs Ted: lives, shells, items ---
    private var gOn = false
    private var gShots = 0
    private var pLives = 3
    private var tLives = 3
    private val shells = mutableListOf<Boolean>()      // true = splash, false = blank
    private val pItems = mutableListOf<String>()
    private val tItems = mutableListOf<String>()
    private var pDouble = false
    private var tDouble = false
    private var pCuffed = false
    private var tCuffed = false
    private var pKnown: Boolean? = null
    private var tKnown: Boolean? = null
    private var reloaded = false
    private val itemPool = listOf("magnifier", "cuffs", "double")

    private fun hearts(n: Int) = "\u2665".repeat(n.coerceAtLeast(0))

    private fun status(): String =
        "You ${hearts(pLives)}   Ted ${hearts(tLives)}   Shells left: ${shells.size}\nYour items: " +
            (if (pItems.isEmpty()) "none" else pItems.joinToString(", "))

    private fun loadRound(log: StringBuilder) {
        val total = (4..8).random()
        val live = (1 until total).random()
        shells.clear()
        repeat(live) { shells.add(true) }
        repeat(total - live) { shells.add(false) }
        shells.shuffle()
        pKnown = null; tKnown = null
        repeat((1..2).random()) { if (pItems.size < 4) pItems.add(itemPool.random()) }
        repeat((1..2).random()) { if (tItems.size < 4) tItems.add(itemPool.random()) }
        log.append("New round: $total shells, $live splash and ${total - live} blank, in a random order. New items dealt.\n")
    }

    // returns true when the turn passes to the other side
    private fun shoot(byPlayer: Boolean, atSelf: Boolean, log: StringBuilder): Boolean {
        val live = shells.removeAt(0)
        gShots++
        val targetIsPlayer = if (atSelf) byPlayer else !byPlayer
        val dbl = if (byPlayer) pDouble else tDouble
        if (byPlayer) pDouble = false else tDouble = false
        pKnown = null; tKnown = null
        val who = if (byPlayer) "You aim" else "Ted aims"
        val at = if (atSelf) (if (byPlayer) "yourself" else "himself") else (if (byPlayer) "Ted" else "you")
        log.append("$who at $at... ")
        var passes: Boolean
        if (live) {
            val dmg = if (dbl) 2 else 1
            if (targetIsPlayer) pLives -= dmg else tLives -= dmg
            log.append("SPLASH! ${if (targetIsPlayer) "You're" else "Ted's"} soaked (-$dmg).\n")
            if (targetIsPlayer) setMood("happy") else setMood("surprised")
            passes = true
        } else {
            log.append("click. Blank.\n")
            passes = !atSelf
        }
        if (pLives > 0 && tLives > 0 && shells.isEmpty()) {
            loadRound(log)
            reloaded = true
            passes = true
        }
        return passes
    }

    private fun checkEnd(log: StringBuilder): Boolean {
        if (pLives <= 0) {
            gOn = false
            ui.postDelayed({ stopTheme() }, 2500)
            log.append("You're out of lives. I win. You lose your 10 coins.\n")
            saveStats(); updateStatLine()
            return true
        }
        if (tLives <= 0) {
            gOn = false
            ui.postDelayed({ stopTheme() }, 2500)
            reward(25, 15)
            log.append("Ted is out of lives. You win! +25 coins.\n")
            return true
        }
        return false
    }

    private fun tedUseItems(log: StringBuilder) {
        if (shells.isEmpty()) return
        if (tKnown == null && tItems.remove("magnifier")) {
            tKnown = shells[0]; log.append("Ted peeks through a magnifier.\n")
        }
        if (!pCuffed && tItems.contains("cuffs") && (1..2).random() == 1) {
            tItems.remove("cuffs"); pCuffed = true
            log.append("Ted cuffs you. You'll skip your next turn.\n")
        }
        val k = tKnown
        val likelySplash = k == true || (k == null && shells.count { it } * 2 >= shells.size)
        if (!tDouble && likelySplash && tItems.remove("double")) {
            tDouble = true; log.append("Ted loads a double-splash.\n")
        }
    }

    private fun tedWantsSelf(): Boolean {
        val k = tKnown
        if (k != null) return !k                      // known blank: shoot himself for a free turn
        return shells.count { it } * 2 < shells.size  // mostly blanks left: gamble on himself
    }

    private fun runTed(log: StringBuilder) {
        while (gOn) {
            if (tCuffed) { tCuffed = false; log.append("Ted is cuffed and skips his turn.\n"); return }
            var passes = false
            while (!passes && gOn) {
                tedUseItems(log)
                passes = shoot(false, tedWantsSelf(), log)
                if (checkEnd(log)) return
                if (reloaded) { reloaded = false; return }
            }
            if (pCuffed) { pCuffed = false; log.append("You're cuffed and skip your turn.\n"); continue }
            return
        }
    }

    private fun gameCommand(raw: String): String? {
        val t = raw.lowercase().trim().trimEnd('!', '.')

        if (gOn) {
            val log = StringBuilder()
            if (has(t, "quit", "stop", "give up", "cancel")) {
                gOn = false; stopTheme()
                return if (gShots == 0) {
                    coins += 10; saveStats(); updateStatLine()
                    "Cancelled. Your 10 coins are back."
                } else {
                    saveStats(); updateStatLine()
                    "You forfeit. You lose your 10 coins."
                }
            }
            if (has(t, "item")) return status()
            if (has(t, "magnifier", "peek", "look")) {
                if (!pItems.remove("magnifier")) return "You don't have a magnifier."
                pKnown = shells[0]
                return "The next shell is " + (if (pKnown == true) "a SPLASH." else "a blank.")
            }
            if (has(t, "cuff")) {
                if (!pItems.remove("cuffs")) return "You don't have cuffs."
                tCuffed = true
                return "Ted is cuffed. He skips his next turn."
            }
            if (has(t, "double")) {
                if (!pItems.remove("double")) return "You don't have a double."
                pDouble = true
                return "Your next splash will do double."
            }
            val self = t == "me" || t == "self" || has(t, "myself", "shoot me", "at me")
            val ted = t == "ted" || t == "him" || has(t, "shoot ted", "at ted", "shoot him")
            if (self || ted) {
                reloaded = false
                val passes = shoot(true, self, log)
                if (!checkEnd(log)) {
                    if (reloaded) reloaded = false
                    else if (passes) runTed(log)
                    else log.append("Blank! You go again.\n")
                    if (gOn) log.append(status())
                }
                return log.toString().trim()
            }
        }

        if (has(t, "roulet")) {
            if (gOn) return "We're already playing. Say 'me' or 'ted' to fire."
            if (fighting) return "They're busy fighting right now."
            if (duelOn) return "The Teds are already playing."
            if (teds.size == 2) {
                startDuel()
                return "Two Teds, one toy water pistol, six chambers. Watch who gets soaked."
            }
            if (coins < 10) return "You need 10 coins to play."
            coins -= 10; gOn = true; gShots = 0
            pLives = 3; tLives = 3; pItems.clear(); tItems.clear()
            pDouble = false; tDouble = false; pCuffed = false; tCuffed = false
            val log = StringBuilder()
            log.append("Water Pistol Roulette. Stake: 10 coins. Three lives each. ")
            log.append("Shells are splash or blank. Say 'me' to shoot yourself (a blank means you go again) or 'ted' to shoot Ted. ")
            log.append("Items: 'magnifier' peeks at the next shell, 'cuffs' skip his turn, 'double' doubles your next splash.\n")
            loadRound(log)
            log.append(status())
            saveStats(); updateStatLine()
            startTheme()
            return log.toString()
        }

        if (guessTarget > 0) {
            val n = t.toIntOrNull()
            if (n != null) {
                if (n == guessTarget) {
                    guessTarget = 0; setMood("happy"); reward(10, 10)
                    return "You got it! +10 coins."
                }
                guessTries--
                if (guessTries <= 0) {
                    val x = guessTarget; guessTarget = 0; setMood("surprised")
                    return "Out of tries. It was $x."
                }
                return (if (n < guessTarget) "Higher." else "Lower.") + " $guessTries tries left."
            }
            if (has(t, "quit", "stop", "give up")) { guessTarget = 0; return "Okay. Game over." }
        }
        if (t == "guess" || has(t, "guess a number", "guess game", "number game")) {
            guessTarget = (1..10).random(); guessTries = 3
            return "I'm thinking of a number from 1 to 10. You have 3 tries."
        }
        if (has(t, "rock paper scissors") || t == "rps") return "Say rock, paper or scissors."
        if (t == "rock" || t == "paper" || t == "scissors") {
            val mine = listOf("rock", "paper", "scissors").random()
            val win = (t == "rock" && mine == "scissors") || (t == "paper" && mine == "rock") ||
                (t == "scissors" && mine == "paper")
            return when {
                t == mine -> { setMood("surprised"); "I picked $mine too. Tie!" }
                win -> { setMood("sad"); reward(5, 10); "I picked $mine. You win! +5 coins." }
                else -> { setMood("happy"); "I picked $mine. I win!" }
            }
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
            return "happy" to "Feed, pet, play, nap, and shop with the buttons. Say 'guess', 'rock paper scissors' or 'roulette' (a toy water pistol) to play for coins. 'play music' starts my boombox. Double-tap me to bring a friend, give both skins, and they fight."
        if (has(t, "how are you", "how r u", "how you doing", "what's up", "whats up")) {
            val a = cur()
            if (a != null) {
                if (a.hunger < 30) return "sad" to "Kinda hungry, honestly. Feed me?"
                if (a.energy < 25) return "sleepy" to "So tired. Maybe a nap?"
                if (a.happy < 30) return "sad" to "A little lonely. Pet me?"
            }
        }
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
        statView = null
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

        val stats = TextView(this).apply {
            setTextColor(Color.parseColor("#BBBBBB")); textSize = 13f
            setPadding(0, dp(2), 0, dp(4))
        }
        statView = stats
        updateStatLine()

        fun careBtn(label: String, act: (TedActor) -> Unit) = Button(this).apply {
            text = label; textSize = 12f; isAllCaps = false
            minHeight = 0; minimumHeight = 0
            setPadding(dp(2), 0, dp(2), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
            setOnClickListener { cur()?.let(act) }
        }
        val shopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; visibility = View.GONE
        }
        shopRow.addView(careBtn("Pizza 15") { buy(it, "pizza") })
        shopRow.addView(careBtn("Ball 10") { buy(it, "ball") })
        shopRow.addView(careBtn("Potion 20") { buy(it, "potion") })
        val careRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        careRow.addView(careBtn("Feed") { feed(it) })
        careRow.addView(careBtn("Pet") { pet(it) })
        careRow.addView(careBtn("Play") { play(it) })
        careRow.addView(careBtn("Nap") { nap(it) })
        careRow.addView(careBtn("Shop") {
            shopRow.visibility = if (shopRow.visibility == View.GONE) View.VISIBLE else View.GONE
        })

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

        box.addView(header); box.addView(stats); box.addView(careRow); box.addView(shopRow); box.addView(sv); box.addView(row)

        val w = (resources.displayMetrics.widthPixels * 0.92).toInt()
        val h = (resources.displayMetrics.heightPixels * 0.58).toInt()
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
        gameCommand(text)?.let { reply ->
            append("You: $text\n\n")
            append("Ted: $reply\n\n")
            return
        }
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
