# Screen Translator (Android)

A floating bubble that translates any text on your phone screen. Tap the bubble, drag a box over
the text you want (or tap once for the whole screen), and the translation pops up on top of
whatever app you're using. The default target language is English; you can change it to any
language.

**Live mode** keeps translating on its own: pick an area (or the whole screen) once, and anything
new that appears there, such as chat messages, notices or subtitles, is translated automatically
and added to a floating panel. Text that was already translated isn't repeated.

## Choose a translation engine

All three translate with context and meaning, not word by word. Pick one in the app.

| Engine | Cost | Internet | Setup |
| --- | --- | --- | --- |
| **On-device AI** (Google Gemma 4) | Free | Not needed after setup | One-time download of 2.6 GB. Best on phones with 8 GB+ memory. |
| **Gemini** (Google) | Free tier with daily limits | Needed | Free API key from aistudio.google.com |
| **ChatGPT** (OpenAI) | Paid per use (fractions of a cent) | Needed | API key with credit from platform.openai.com |

How the on-device AI works: the phone reads the text on the screen with Google's built-in text
recognition (ML Kit), then the Gemma 4 model translates it on the phone, seeing all the text on
the screen at once so it can follow meaning, tone and slang. Nothing is sent anywhere.

- It reads **Korean, English and other Latin-alphabet languages** (Tagalog, Spanish, Vietnamese
  and so on). For Japanese, Chinese or other scripts, use Gemini or ChatGPT.
- The first translation after starting the bubble takes a few seconds while the AI loads.
- On a recent flagship it usually translates a screenful in a few seconds; mid-range phones are
  slower.

---

## 1. Build the APK in the cloud (no Android Studio needed)

1. Create a free account at **github.com**, then click **New repository** (name it e.g.
   `screen-translator`). Public is fine: no keys are stored in the code.
2. On the new repository page, click **uploading an existing file** and drag in **everything inside**
   the `ScreenTranslator` folder, then click **Commit changes**.
   - The `.github` folder holds the build instructions. Its name starts with a dot, so most computers
     hide it and it usually doesn't upload by drag-and-drop. If you don't see `.github` in the file
     list, click **Add file → Create new file**, type the name `.github/workflows/build-apk.yml`
     exactly (the slashes create the folders), paste in the contents of that file, and commit.
   - It's fine if the app ends up inside a folder: the build finds it automatically.
   - Updating from an older version? Upload the new files over the old ones, and delete
     `app/src/main/java/app/screentranslator/OpenAiClient.java` from the repository (it was
     replaced).
3. Open the **Actions** tab. A run called **Build APK** starts by itself (if not, click
   **Build APK → Run workflow**). The first build takes about 5 to 8 minutes.
4. When it shows a green check, go back to the repository's main page and open **Releases**
   (right-hand side). The newest release contains **ScreenTranslator.apk**.

Every time you change a file and commit, a fresh APK is built and the release is replaced.

**Prefer Android Studio?** Open this folder in Android Studio, wait for it to sync, then use
**Build → Build App Bundle(s) / APK(s) → Build APK(s)**, or plug in your phone and press **Run**.

## 2. Install it on your phone

The app needs **Android 12 or newer**.

1. On the phone, open the Releases page and tap **ScreenTranslator.apk** to download it.
2. Open the downloaded file. Android will ask you to allow installs from your browser: allow it.
3. If Play Protect warns that the app is from an unknown developer, tap **Install anyway**
   (it's your own app, built from this code).

## 3. Set up your engine

**On-device AI**

1. Open Screen Translator and choose **On-device AI (Gemma 4)**.
2. Leave **Gemma 4 E2B** selected (choose E4B only if your phone has 12 GB of memory or more).
3. Connect to Wi-Fi and tap **Download**. Android downloads the model in the background and shows
   its progress in the notifications; you can leave the app. When it's done, the app shows
   "✓ … ready".

If the download fails because the site asks you to sign in, download the `.litertlm` file in a
browser from the model page shown in the error message, then tap **Use a model file from this
phone** and pick it.

**Gemini**

1. Go to **aistudio.google.com/apikey**, sign in with a Google account, and create an API key.
2. In Screen Translator, choose **Gemini (Google)** and paste the key. Leave the model as
   `gemini-3.1-flash-lite`.

The free tier allows a limited number of requests per minute and per day, and Google may use
free-tier requests to improve its products. Live mode automatically checks less often with Gemini
(at most every 5 seconds) to stay within the per-minute limit.

**ChatGPT**

1. Go to **platform.openai.com**, add a little credit under **Settings → Billing**, then create a key
   under **API keys**. API usage is billed separately from a ChatGPT Plus subscription.
2. In Screen Translator, choose **ChatGPT (OpenAI)** and paste the key. Leave the model as
   `gpt-6-luna`.

## 4. Use it

1. In the app, check the **Translate into** language, then tap **Start translator bubble**.
   - Turn on **Allow display over other apps**, then go back.
   - Allow the screen capture prompt (it may say "Start now" or "Share screen").
2. Open any app. Tap the round bubble, then:
   - **drag a box** over the text you want translated, or
   - **tap anywhere** to translate everything on the screen.
3. The translation card appears. Tap **Copy** to copy it, **✕** to close it, or tap the bubble
   again for the next one. Drag the bubble to move it out of the way.
4. To turn it off: tap **Stop** in the app, in the notification, or on the screen-sharing chip in
   the status bar.

You can switch engines in the app at any time, even while the bubble is on; the next translation
uses the new choice.

**Live mode**

1. Tap the bubble, then switch the tab at the bottom from **Translate once** to **● Live**
   (the app remembers your choice).
2. Drag a box over the area to watch, for example the message list of a chat, or tap anywhere to
   watch the whole screen.
3. A panel opens and the bubble turns red. Whenever new text appears in the area, it's translated
   and added to the bottom of the panel shortly after the screen stops moving. Text that's already
   in your language is skipped.
4. Panel buttons: **Pause / Resume**, **Clear** (empties the panel; old text still won't repeat),
   **⇅** (moves the panel to the other end of the screen), **✕** (stops Live). Long-press a line to
   copy it.
5. Tap the bubble again to pick a different area, or to switch back to Translate once.

How Live mode stays light:

- It only checks when the watched area actually changes, and waits until the screen stops scrolling
  (or up to 3 seconds for things that never stop moving, like videos).
- With the on-device AI, the phone first reads the text and only runs the AI when there's text it
  hasn't translated yet.
- When changes keep turning out to have no new text (an animation, say), it checks less often until
  new text shows up.
- The panel and bubble are greyed out of the screenshot, so the app never re-translates itself.
- It pauses itself after 20 minutes. Tap **Resume** to continue.

With ChatGPT, Live mode costs a few cents an hour for normal use; video with constant motion costs
more. The on-device AI is free but uses battery while it works.

**Read aloud**

Translations can be spoken with your phone's built-in voices, which work offline once installed.

- Tap **🔊** on a translation card to hear it (tap **⏹** to stop). In the Live panel, tap any line
  to hear it.
- To hear every translation automatically, tick **Read every translation aloud automatically** in
  the app, or tap **🔇** in the Live panel (it turns into **🔊**). In Live mode, new lines are read one
  after another; if they arrive faster than they can be read, it skips ahead to the newest ones.
- Other sound, like a video, is turned down while a translation is read.
- Choose **Slow**, **Normal** or **Fast** under **Voice speed**, and tap **Test voice** to hear a
  sample in your target language. If the phone has no voice for that language, the app offers to
  open the voice settings, where you can download one for free.

**Settings**

- **Also show the original text**: shows each original line above its translation, which is handy
  when you're learning a language.

## Good to know

- **Privacy:** with the on-device AI, nothing leaves the phone. With Gemini or ChatGPT, only the
  area you select is sent, and only when a translation runs. Keys are stored only on your phone.
- While the bubble is on, Android shows a screen-sharing indicator. That's expected. Turn the bubble
  off when you don't need it, to save battery.
- Apps that block screenshots (banking apps, some video apps) appear black, so they can't be
  translated.
- After restarting your phone, open the app and tap Start again; Android requires fresh permission
  for screen capture each time.

**Troubleshooting**

| Message or problem | Fix |
| --- | --- |
| "The on-device AI isn't downloaded yet" | Open the app, choose On-device AI and tap Download. |
| "Waiting for Wi-Fi" | Connect to Wi-Fi, or tick "Allow download over mobile data" and tap Download again. |
| On-device translation is slow, or the app closes | Close other apps, use Gemma 4 E2B rather than E4B, or switch to Gemini. |
| Some on-device text isn't translated | It reads Korean and Latin-alphabet text only; use Gemini or ChatGPT for other scripts. |
| "Google didn't accept your Gemini API key" | Create a new key at aistudio.google.com/apikey and paste it again. |
| "You've used today's free Gemini allowance" | Wait until tomorrow, or switch to the on-device AI. |
| "OpenAI didn't accept your API key" | Paste the key again (no spaces). Create a new key if needed. |
| "Your OpenAI account is out of credit" | Add credit under Settings → Billing on platform.openai.com. |
| Nothing is read aloud, or "no … voice installed" | In the app, tap **Test voice**, then **Install voices**, and download the language. If there's no voice app at all, install **Speech Services by Google** from the Play Store. |
| "Display over other apps" switch is greyed out | Settings → Apps → Screen Translator → ⋮ (top right) → **Allow restricted settings**, then try again. |
| The Actions run fails | Open the failed run, click the red step, and read the last lines of the log. |

## For developers

Java, minimum Android 12 (API 31), target API 35, built with Android Gradle Plugin 9.0.1 and
Gradle 9.2.1 on JDK 21 (the same setup as Google's AI Edge Gallery; LiteRT-LM's classes need a
Java 21 compiler to read them). Libraries: LiteRT-LM 0.18.1 (runs Gemma
on the phone) and ML Kit Korean text recognition 16.0.1 (bundled, works offline). The APK includes
arm64 code only.

| File | What it does |
| --- | --- |
| `MainActivity.java` | Settings screen: engine choice, keys, model download, and the permission steps. |
| `TranslatorService.java` | Foreground service: screen capture, bubble, selection layer, result card, and Live mode (change detection, pacing, de-duplication). |
| `TranslationEngine.java` | The interface every engine implements (Translate once and Live). |
| `OnDeviceEngine.java` | ML Kit text recognition, then Gemma 4 via LiteRT-LM, translating numbered items with the rest of the screen as context. |
| `GeminiEngine.java` / `OpenAiEngine.java` | Send the screenshot to Gemini or ChatGPT, which read and translate it in one request. |
| `ModelStore.java` | Downloads, imports, checks and deletes the on-device model files. |
| `Prompts.java` | All instructions given to the AI, and the code that reads its answers. |
| `LivePanel.java`, `SelectionView.java` | The Live panel and the drag-a-box layer. |
| `Speaker.java` | Reads translations aloud with Android's text-to-speech: picks the voice from the language name, splits long text, turns other sound down while speaking. |
| `Prefs.java`, `EngineSettings.java` | Saved settings, and the snapshot each translation uses. |

To change how translations are worded, edit `Prompts.java`. Live mode's timing and sensitivity are
the `LIVE_*` constants at the top of `TranslatorService.java`, and each engine's Live pace is in
`EngineSettings.java`.

Gemma 4 is released by Google under the Apache License 2.0. The translate icon is from Google's
Material Icons (Apache License 2.0).
