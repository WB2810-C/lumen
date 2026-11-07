# PlaizierOS — a home screen for your Peaq Google TV

PlaizierOS (inside the code still called Lumen) replaces the Google TV home screen with your own, in the **Cinema** layout (like Apple TV,
but better): tabs at the top, a floating glass dock with your favourites at the bottom, and a big
preview that fills the screen with whatever you select — "Good evening, Wolfram" with the big
clock and the weather, the top VRT NWS story with its photo, your teams' scores, the song that's
playing, an app's real logo and when you last opened it, or your **Xbox / PlayStation** and the
HDMI port it's on. Opening something plays a transition where the logo grows to fill the screen.
Works in dark themes and in the light Silver/Cloud themes.

**What makes it special:**

- **Living aurora:** the background is a slowly drifting aurora in the colour of the app
  you've selected (red on YouTube, green on Spotify), with a fine film grain. The colour is
  taken from each app's own logo, so it works for every app you install.
- **Greeting + rolling clock:** "Good evening, Wolfram" above a big bold clock whose digits
  roll up when the minute changes. The clock picks up a hint of the glow colour.
- **Light sweep:** a glint of light slides over each tile when you land on it, with a soft
  shadow in the app's colour underneath.
- **Xbox and PlayStation:** in your favourites from the start. Lumen finds each console by the name
  it gives the TV over HDMI ("PS5", "Xbox Series X"), so OK switches straight to the right HDMI port,
  even if you move it. If the console doesn't say its name, you pick the port once.
- **Rearrange the dock:** hold OK on a tile, move it with ◀ ▶, OK to drop, ▼ to remove it.
- **No screensaver clock:** Lumen stays on the home screen when you don't use the remote
  (set `AMBIENT_AFTER_MINUTES` in `LumenConfig.kt` if you want the drifting clock back).
- **Welcome animation:** when the TV starts, the Lumen logo appears and zooms into the home
  screen, in the colour of the last app you used.
- **11 colour themes:** Silver (the default), Cloud (white), Stone (grey), App colour (follows
  the selected app), Northern Lights, Sunset, Ocean, Lavender, Gold, Graphite and Neon. Open them
  with the palette icon in the side menu: moving over a theme previews it, OK keeps it.
- **Assistant (free):** the sparkle button at the top of the side menu. Talk or type in Dutch
  or English: "open Netflix", "HDMI 1", "thema Oceaan", "zoek … op YouTube", "pauze", "wat voor
  weer is het?", "het nieuws", "de score". No account and no costs. For any other question you
  can add a free AI that runs on your laptop (see **step 1b**).
- **Weather sky:** the real weather in Leuven on the screen: rain streaks, drifting snow, fog,
  lightning, and twinkling stars on clear nights, plus a weather card (Open-Meteo, free).
- **Live scores in a Dynamic Island:** when Bayern München, the Netherlands, the Rode Duivels or
  Liverpool play, a black pill at the top of the home screen shows the live score. When someone
  scores it opens up big with a chime. It only appears on the home screen.
- **VRT NWS:** headlines rotate under the clock and refresh by themselves every hour. The News
  page shows the photo, headline and VRT's summary right inside Lumen, with no ads and no browser.
- **On this day:** a history fact for today under the clock, fresh every day.
- **Now Playing:** when Spotify, YouTube or another app plays, a card shows the cover and title
  with play/pause/skip. Ambient mode then shows the cover big. See **step 5** to switch it on.
- **Phone remote:** scan a QR code and use your iPhone to type questions, open apps, control the
  music, send YouTube links, and **show photos, videos, PDFs and music on the TV**, including
  files from **iCloud Drive** (see below).
- **UI sounds:** soft ticks, pops and a whoosh when an app opens.

**About iCloud:** Apple doesn't let other apps on Android or TVs sign in to iCloud Drive, and
typing your Apple ID password into a homemade app wouldn't be safe. So Lumen uses your iPhone
as the bridge: on the phone remote, tap **Show on TV** and pick photos, videos or files from
your library, the Files app or iCloud Drive. They go straight to the TV over your Wi-Fi (not
through the internet) and stay on the **Files** page until you delete them.

It is a normal Android TV app, so nothing about your TV's system is changed and you can always
go back to the original home screen.

---

## 0. The easy way: no Android Studio

GitHub builds the app for you in the cloud, for free (`.github/workflows/build.yml`).

1. Put this project in a GitHub repository (Claude can do this for you once GitHub is
   connected). Every change starts a build that takes about 5 minutes.
2. Download **PlaizierOS-installer.zip** from the repository's **Releases** page (or Claude sends it).
3. Unzip it on your laptop and double-click **Install-PlaizierOS.bat**. It downloads Google's small adb tool by itself, connects to
   the TV, installs Lumen, switches on the microphone and Now Playing, checks that Lumen starts,
   sets the TV picture to 1080p, and only then asks whether to make it your home screen.
   Undo with **Back-to-Google-TV.bat**. Something not working? It saves **plaizieros-report.txt**
   (or run **PlaizierOS-report.bat**): that file shows what the TV says about the picture, the
   internet and voice.

That replaces steps 1 to 5 below. If a build fails, the log is on the `build-output` branch.

## 1. Build the app (the long way, with Android Studio)

1. Install **Android Studio** (free): https://developer.android.com/studio
2. Unzip `Lumen.zip`, then in Android Studio choose **File → Open** and pick the `Lumen` folder.
3. Wait for "Gradle sync" to finish (the first time it downloads a lot, give it a few minutes).
   If Android Studio offers to upgrade the Android Gradle Plugin, you can accept.
4. **Build → Build App Bundle(s) / APK(s) → Build APK(s).**
   The file appears at `app\build\outputs\apk\debug\app-debug.apk`.

If you see a red error in Android Studio, copy it and send it to Claude.

## 1b. The assistant (free)

The assistant works **for free, without any account**. Lumen understands everyday commands
itself, in Dutch and English, and answers from what it already knows:

- "open Netflix", "zet VRT MAX op", "HDMI 1", "thema Oceaan", "make it Cloud"
- "zoek Wonderwall op YouTube", "search YouTube for cat videos"
- "pauze", "volgende", "vorige" (while music or a video plays)
- "wat voor weer is het?", "gaat het regenen?", "het nieuws", "de score", "hoe laat is het?"
- "toon het nieuws", "toon mijn bestanden", "schermbeveiliging"

**Optional: a free AI for any other question.** It runs on your own laptop with Ollama, so it
costs nothing and your questions never leave your home. The laptop has to be on when you ask.

1. Install Ollama for Windows from **https://ollama.com/download**.
2. Open PowerShell and download the model (about 3.4 GB, once):
   ```
   ollama pull gemma3
   ```
   On an older or slower laptop use `ollama pull gemma3:1b` instead (0.8 GB), and change
   `OLLAMA_MODEL` in `LumenConfig.kt` to `"gemma3:1b"`.
3. Let the TV reach it: quit Ollama (taskbar icon → Quit), open **Edit environment variables
   for your account**, add `OLLAMA_HOST` with the value `0.0.0.0:11434`, then start Ollama
   again from the Start menu. If Windows asks about the firewall, allow **private networks**.
4. Find the laptop's IP address: in PowerShell type `ipconfig` and look for **IPv4 Address**
   (for example `192.168.1.20`).
5. In the `Lumen` folder, open **`local.properties`** and add:
   ```
   ollama.host=192.168.1.20
   ```
6. Build and install Lumen again.

Commands like "open Netflix" still work instantly without the AI; only other questions go to
your laptop. The AI knows the weather, the headlines and the live scores that Lumen has, but it
can't search the internet.

*(Claude also still works if you ever want it: put `claude.apiKey=…` in `local.properties`
instead. That one is paid per question, so it's off unless you add a key.)*

The first time you press the microphone, the TV asks whether Lumen may use the microphone.
Choose **Allow**. If your remote has no microphone, use the keyboard button to type instead.

## 2. Connect your laptop to the TV

On the TV:

1. **Settings → System → About**, then click **Android TV OS build** 7 times until it says
   you're a developer.
2. **Settings → System → Developer options** → turn on **USB debugging**
   (and **Wireless debugging** if you see it).
3. Write down the TV's IP address: **Settings → Network & Internet →** your Wi-Fi network.

On the laptop, open a terminal (PowerShell) in the folder where Android Studio keeps `adb`:

```
cd $env:LOCALAPPDATA\Android\Sdk\platform-tools
.\adb connect 192.168.1.50:5555        # use your TV's IP address
```

Accept the "Allow debugging?" pop-up on the TV.
If your TV shows **Wireless debugging → Pair device with pairing code**, use that instead:
`.\adb pair IP:PORT` (type the code it shows), then `.\adb connect IP:PORT`.

## 3. Install Lumen

```
.\adb install "C:\path\to\Lumen\app\build\outputs\apk\debug\app-debug.apk"
```

(Or simply press the green ▶ Run button in Android Studio with your TV selected at the top.)

Lumen now shows up in the TV's app list. Open it from there first and try it out.

## 4. Make Lumen your home screen

**Step A — the safe way.** Try this first:

```
.\adb shell cmd package set-home-activity --user 0 com.wolfram.lumen/.MainActivity
```

Press **Home** on the remote. If Lumen appears, you're done.

**Step B — only if Home still opens Google TV.** This switches off Google's home screen
(it is not deleted and you can switch it back on). Only do this **after** Lumen is installed
and works, and keep your laptop connected the first time:

```
.\adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
.\adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
```

While Google's home screen is off, its recommendations and profiles are gone too.

**To go back to the normal Google TV home screen:**

```
.\adb shell pm enable --user 0 com.google.android.apps.tv.launcherx
.\adb shell pm enable --user 0 com.google.android.tungsten.setupwraith
.\adb uninstall com.wolfram.lumen
```

A factory reset also always brings the TV back to how it came out of the box.

## 5. Switch on Now Playing (once)

Android only lets an app see what other apps are playing if you allow it. Lumen doesn't read
your notifications; this just unlocks the Now Playing card:

```
.\adb shell cmd notification allow_listener com.wolfram.lumen/com.wolfram.lumen.MediaListener
```

Then press Home once. To switch it off again, use `disallow_listener` instead of `allow_listener`.

## 6. Use your phone as a remote

1. Make sure your iPhone is on the same Wi-Fi as the TV.
2. On the TV, open the phone icon at the bottom of the side menu.
3. Scan the QR code with the iPhone camera and open the link.
4. Tip: in Safari tap **Share → Add to Home Screen**, so the remote is one tap away.

The link contains a secret key, so other people on your Wi-Fi can't use it without scanning
the code on your TV. Everything stays inside your home network.

---

## Using Lumen

| Remote            | What it does                                              |
|-------------------|-----------------------------------------------------------|
| Arrows            | Move between tiles (the background glow follows)          |
| OK                | Open — the logo fills the screen, then the app opens      |
| Hold OK           | Add or remove an app from **Favourites**                  |
| Left (from the first tile) | Side menu: Assistant, Home, Apps, News, Files, Inputs, Search, Themes, Phone remote, Settings |
| Up (from Favourites) | The news strip, weather and Now Playing controls |
| Back              | From another page back to Home; closes the assistant      |
| Home              | Back to Lumen; the logo shrinks back into its tile        |

- **Favourites** starts with YouTube, Netflix, Spotify, Disney+ and Prime Video (if installed).
  Add anything else, like VRT MAX, by holding OK on it.
- **Inputs** (in the side menu) only shows up on a TV with HDMI ports (not on a TV stick).
- The text follows the TV's language: English or Dutch.

## What's in the code

| File | What it does |
|---|---|
| `MainActivity.kt` | Starts Lumen and notices when you come back to it |
| `HomeScreen.kt` | The home screen: side menu, clock, rows, Apps and Inputs pages, what the assistant may do |
| `Tiles.kt` | The app tiles: real logos, focus ring, light sweep |
| `ThemesPage.kt` | The Themes page with live preview |
| `LocalAssistant.kt` | The free assistant: understands commands and answers from weather, news and scores |
| `Assistant.kt` | The conversation, and the optional AIs (Ollama on your laptop, or Claude) |
| `Weather.kt` | Weather from Open-Meteo, the rain/snow/fog/stars effects and the weather card |
| `Football.kt` | Live scores from ESPN's public score feed and the Dynamic Island |
| `News.kt` | VRT NWS feed (refreshes every hour), the headline strip and the News page |
| `History.kt` | The daily "On this day" fact |
| `NowPlaying.kt` | Now Playing card, controls and the big cover in ambient mode |
| `PhoneRemote.kt` | The little web server for the phone remote, file transfer and the QR code |
| `Files.kt` | The Files page and the photo, video, music and PDF viewer |
| `Sounds.kt` | The UI sounds (in `res/raw`) |
| `assets/remote.html` | The page you see on your iPhone |
| `AssistantPanel.kt` | The assistant's glass panel, the orb, voice and typing |
| `Takeover.kt` | The logo-takes-over-the-screen transition (and the way back) |
| `Special.kt` | Aurora background, greeting + rolling clock, ambient mode, welcome animation |
| `LumenConfig.kt` | Your name, the on/off switches, the starting theme and assistant settings |
| `Themes.kt` | The 11 colour themes — change a colour or add your own here |
| `TvRepository.kt` | Reads your apps, their real logos and HDMI inputs; saves favourites |
| `Launcher.kt` | Opens apps, HDMI inputs, Settings and Search |
| `UiParts.kt` | Colours, wallpaper glow, frosted glass, and the small icons |

Things you might want to change first:

- **`LumenConfig.kt`** — your name in the greeting, after how many minutes ambient mode
  starts, the starting theme, switches for the moving background and the welcome animation,
  the assistant's model, web search and your city, the weather location and effects, the news
  feed and how often it refreshes, your football teams, the phone remote, and the sound volume.
  To follow another team, add its ESPN id (it's in the team's address on espn.com). If the background feels slow on your
  TV, set `LIVING_BACKGROUND = false`.
- **Colours:** the themes in `Themes.kt`; the aurora's shape and movement in `AuroraBackground` (`Special.kt`).
- **Glow speed:** the `700` in `LumenApp` (`HomeScreen.kt`).
- **Clock size:** `100.sp` in `HomeContent` (`HomeScreen.kt`).
- **Tile sizes:** `TileSize` in `HomeScreen.kt`.
- **Transition speed:** the `560` (open) and `460` (back) in `Takeover.kt`.
- **Default favourites:** `DEFAULT_FAVOURITES` in `TvRepository.kt`.

Good to know: Android TV can't blur what's *behind* a panel the way iPhone does,
so the glass is a frosted see-through look rather than a real blur.

## Where the live information comes from

- **Weather:** Open-Meteo (free, no account, no ads).
- **News:** the official VRT NWS feed. Lumen only shows the headline, photo and summary.
- **Football:** ESPN's public score feed. It's free but unofficial, so if ESPN changes it the
  island may stop showing scores until the code is updated.
- **On this day:** Wikipedia (or Claude, if you ever add a Claude key).
