# JustType Beta — Tester Quick Start

Thank you for trying JustType! This page covers the few things you need
to do once to get going, plus how to share feedback with us.

For a fuller walkthrough of how JustType works, see the
[User Guide](UserGuide.md).

---

## 1. Install the APK

You'll receive a `.apk` file from us (by email, link, or shared drive).

1. On your Android device, open the file.
2. If Android asks whether to "Allow installs from this source," tap
   **Settings** and turn it on for the app you used to receive the
   file (Gmail, Drive, your browser, etc.).
3. Tap **Install**.

JustType requires Android 8.0 (Oreo) or newer.

---

## 2. Turn JustType on as your keyboard

After install, JustType is on your phone but not yet active. You need to
*enable* it and *select* it.

1. Open Android **Settings → System → Languages & input → On-screen
   keyboard → Manage on-screen keyboards** (the exact path varies by
   device; some makers put it under **General management** or **Input
   methods**).
2. Find **JustType** in the list and turn the switch **ON**. Android
   will warn you that an input method "may collect text you type" —
   that's a standard warning for *every* keyboard. JustType does not
   transmit anything off your device.
3. Open any app where you would normally type (Messages, Gmail, the
   address bar of your browser). Tap the text field.
4. Tap the small keyboard icon near the bottom-right of the screen
   (Android calls this "switch input method"). Pick **JustType**.

JustType should now appear whenever you tap a text field. To go back to
your previous keyboard at any time, use that same input-method-switch
icon.

---

## 3. Pick a voice you like (especially a male voice)

JustType uses Android's built-in text-to-speech engine to read words
aloud. By default, Android's Google Speech Services ships with a
**female voice** and Accessibility Settings only let you change pitch
and speed — *not* the voice itself.

To switch to a male voice, you have to dig into the TTS engine's *own*
settings, which Android hides somewhat awkwardly.

### On most Android devices (Google Speech Services)

1. Open Android **Settings**.
2. Search for **Text-to-speech** in the settings search bar. (Or
   navigate to **System → Languages & input → Text-to-speech output**
   — the path varies by maker.)
3. Make sure **Preferred engine** is set to **Google Speech Services**.
4. Tap the **gear icon** next to "Google Speech Services."
5. Tap **Install voice data** → **English (United States)** (or your
   preferred locale).
6. You'll see several voices labeled "Voice I," "Voice II," "Voice III,"
   etc. **"Voice I" is the female default; "Voice II," "Voice III," and
   "Voice IV" are typically male.** Tap one to download it, then tap
   it again to play a sample.
7. Back out to the engine's main settings and set your chosen voice as
   the default.

JustType will pick up the new voice automatically — no need to
restart.

### On Samsung devices

Samsung devices often default to **Samsung Text-to-speech** instead of
Google's. Samsung TTS has named voices like **"Daniel"** (male) and
**"Stephanie"** (female). The path is **Settings → General management
→ Text-to-speech → ⚙ next to Samsung Text-to-speech engine → Voice**.

### Other engines

If you'd like an even more natural-sounding voice, install one of these
from the Play Store:

- **Acapela TTS Voices** — paid, but offers a free trial; "Will" (US)
  and "Peter" (UK) are widely considered the most natural-sounding
  male voices on Android.
- **RHVoice** — free and open source; smaller voice catalog but works
  fully offline.

After installing, set the new engine as your TTS default (same path
as step 4 above), then optionally pick a specific voice within it.

---

## 4. Submit Feedback (and send us crash reports)

If something doesn't work right, or just crashes, please share the
diagnostic logs with us — it saves us a lot of detective work.

1. Open the **JustType Settings** app from your home screen (the app
   icon, not the keyboard).
2. Scroll all the way to the bottom of the main Settings page.
3. Tap **Submit Feedback**.
4. Android's share sheet will appear. Pick **Gmail**, **Drive**, or
   any messaging app you like, and send the file to us at the address
   we provided.

The bundled file contains only diagnostic logs from JustType itself —
no text you typed in other apps, no contacts, no passwords. If you
have privacy concerns about any specific log, open the `.zip` and
review it before sending. We're happy to answer questions.

---

## 5. Where to get more info, ask questions, or report bugs

- **User Guide** — [UserGuide.md](UserGuide.md): how the keyboard
  layouts work, how prediction handles ambiguous keys, how to use
  each input method (head tracking, joystick, single-switch,
  two-switch).
- **Input Methods reference** — [InputMethods.md](InputMethods.md):
  setup details for head-tracking with our companion HeadBoard app,
  switch-input wiring, etc.
- **Settings reference** — [SettingsReference.md](SettingsReference.md):
  page-by-page explanation of every setting and when you might want
  to change it.
- **Contact us** — at the email address provided with your beta
  invitation.

Thank you for being part of this. We're excited to put JustType in
your hands.
