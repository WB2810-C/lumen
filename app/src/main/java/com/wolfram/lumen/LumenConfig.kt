package com.wolfram.lumen

/** Things you can easily change. Rebuild and reinstall after changing them. */
object LumenConfig {
    /** Shown in the greeting: "Good evening, Wolfram". Leave empty ("") for no name. */
    const val OWNER_NAME = "Wolfram"

    /** Ambient mode (big clock) starts after this many minutes without touching the remote. */
    const val AMBIENT_AFTER_MINUTES = 3

    /** Set to false if the background movement is too slow on your TV. */
    const val LIVING_BACKGROUND = true

    /** Play the welcome animation when the TV starts. */
    const val WELCOME_ANIMATION = true

    /** After standby the TV comes back on Lumen's home screen, not on the app you were watching. */
    const val HOME_AFTER_STANDBY = true

    /**
     * Ask the TV for the sharpest picture it can take (1080p on yours) while Lumen is on screen,
     * instead of the 720p the stick sometimes picks.
     */
    const val SHARPEST_PICTURE = true

    /**
     * Theme used until you pick one on the TV (side menu → palette icon).
     * One of: "app", "north", "sunset", "ocean", "lavender", "gold", "graphite", "neon",
     * "stone", "silver", "cloud".
     */
    const val DEFAULT_THEME = "silver"

    /* ----- Assistant -----
     * Free by default: Lumen understands commands itself and answers from the weather, news and scores.
     * For other questions you can switch on a free AI on your laptop (Ollama), or Claude (paid).
     * Both go in local.properties, see the README.
     */

    /** The model Ollama runs on your laptop. Gemma 3 speaks Dutch well; use "gemma3:1b" on a slower laptop. */
    const val OLLAMA_MODEL = "gemma3"

    /** The Claude model (only used if you add a Claude API key). */
    const val ASSISTANT_MODEL = "claude-haiku-5-5"

    /** Let the assistant search the web for weather, news, scores… (costs a little extra per search). */
    const val ASSISTANT_WEB_SEARCH = true

    /** Where you are, so "what's the weather?" means your weather. */
    const val CITY = "Leuven"
    const val COUNTRY = "BE"
    const val TIME_ZONE = "Europe/Brussels"

    /* ----- Weather sky (Open-Meteo, free, no key) ----- */

    const val LATITUDE = 50.8798
    const val LONGITUDE = 4.7005

    /** Rain, snow, fog, lightning and stars on the screen. */
    const val WEATHER_EFFECTS = true

    /* ----- News (VRT NWS, official feed: headlines and summaries only, no ads) ----- */

    const val NEWS_FEED_URL = "https://www.vrt.be/vrtnws/nl.rss.articles.xml"
    const val NEWS_REFRESH_MINUTES = 60

    /* ----- Live football scores (Dynamic Island on the home screen) ----- */

    /**
     * ESPN team ids: 132 Bayern München, 449 Netherlands, 459 Belgium (Rode Duivels), 364 Liverpool.
     * Find another team's id in its URL on espn.com, e.g. espn.com/soccer/team/_/id/364/liverpool.
     */
    val FOOTBALL_TEAMS = listOf(132, 449, 459, 364)

    /* ----- Phone remote ----- */

    const val PHONE_REMOTE = true
    const val REMOTE_PORT = 8765

    /* ----- Sounds ----- */

    const val UI_SOUNDS = true

    /** 0.0 (silent) to 1.0 (loud). */
    const val UI_SOUND_VOLUME = 0.35f
}
