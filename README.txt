TED - floating AI assistant (Android)

BUILD
1. Install Android Studio. File > Open > select this TedApp folder.
2. Let Gradle sync (it downloads what it needs). Plug in your phone (USB debugging on) and press Run,
   or Build > Build APK(s) and install the APK.

USE
1. Get an API key at console.anthropic.com and paste it into the Ted app.
2. Tap "Allow Ted to appear over other apps" and enable it.
3. Tap "Wake Ted up". Ted floats over other apps. Drag him around, tap him to chat.

MOODS
Ted starts each reply with a mood tag. To give him a face for a mood, add an image named
ted_<mood>.png in app/src/main/res/drawable/ (lowercase). Moods: neutral (included), happy,
sad, angry, sleepy, surprised. Missing ones fall back to neutral. You can add your own moods by
editing the mood list in systemPrompt in OverlayService.kt.

NEW IN THIS VERSION
- Double-tap Ted: a second Ted appears (max 2). Long-press one of them to send it away.
- Shirt button on each Ted: cycles skins (none, warrior, suit, none).
- When both Teds wear skins, they trade insults in speech bubbles.
- Say "play music" (or tap the note button in the chat): Ted grabs his boombox and plays songs.
  Say "next" to skip, "stop" to quit.
