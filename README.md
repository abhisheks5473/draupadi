# Draupadi

**Say one word. Your phone texts your people, records what is happening, saves it to
your gallery, and wakes up every Draupadi phone around you.**

A real Android app — Kotlin, Jetpack Compose, CameraX, Firebase. Not a prototype:
the microphone, the camera, the SMS, the gallery write and the location are all the
actual Android APIs doing the actual thing.

---

## Getting the APK

You do not need Android Studio. GitHub builds it for you.

1. Run the one-time setup, which puts the CI workflow in place and makes the Gradle
   wrapper executable:

   ```bash
   cd draupadi
   ./setup.sh
   ```

   (If it is missing, do it by hand: `mkdir -p .github/workflows && mv
   workflow-build-apk.yml .github/workflows/build-apk.yml && chmod +x gradlew`.)

2. Create a new repository on GitHub (private is fine).
3. Push this folder to it:

   ```bash
   cd draupadi
   git init && git add -A && git commit -m "Draupadi"
   git branch -M main
   git remote add origin https://github.com/<you>/draupadi.git
   git push -u origin main
   ```

4. Open the **Actions** tab. A run called *Build APK* starts on its own and takes
   about four minutes.
5. When it finishes, download **draupadi-apk** from the Artifacts section — or grab
   `draupadi.apk` from the **latest** release, which is easier to open on a phone.

Every push rebuilds it. If a build fails, open the failed step and send me the red
lines; that is the fastest way to fix it.

> Prefer Android Studio? Open this folder, plug in a phone, press Run. Same project.

---

## Install it

The APK is debug-signed, so Android will ask before installing.

1. Open `draupadi.apk` on the phone.
2. On the "can't install" prompt, tap **Settings** and allow installs from that app.
3. Install, open, and go through the three setup screens.
4. **Turn off battery optimisation for Draupadi** (Settings → Apps → Draupadi →
   Battery → Unrestricted). Skip this and Android will quietly kill the listener
   after a few hours. This is the single most common reason a safety app fails.

---

## The three questions it asks, once

Setup is three screens and then never again:

1. Your name and your **safe word**.
2. **Who gets texted** — picked from your own contacts.
3. **Permissions**, granted in one tap.

Everything is granted at setup precisely so that nothing is ever asked of you during
an emergency. When the alert fires there are no dialogs, no confirmations and no
choices — only a timer and one button that says *I am safe*.

---

## What happens the moment an alert starts

Triggered by the safe word, by holding the button, or by shaking the phone hard four
times.

| When | What |
|---|---|
| instantly | The phone buzzes morse `· · · — — — · · ·` |
| ~1 second | **SMS to every contact and to 112** with a live Google Maps link. No tap, no dialog. |
| ~2 seconds | **Camera and microphone start recording**, straight into MediaStore |
| ~3 seconds | An alert document is created and **every Draupadi phone within 1 km** gets it |
| every 8 s | A still frame is uploaded, so evidence exists in the cloud even if the phone does not survive |
| 20 s | The ring widens to **2 km** |
| 45 s / 75 s | **3 km**, then **5 km** |
| every 2 min | A fresh location by SMS, three times over |
| when 3 people accept | The server releases the **exact** location to them |
| on *I am safe* | Recording stops → **the clip appears in your Gallery** → it uploads → the link is texted to your contacts → WhatsApp opens with the video attached, one tap to send |

---

## The consent gate

The interesting part is what a stranger *cannot* see.

A responder's phone buzzes and shows a distance and a neighbourhood — never a point.
The exact position lives in a separate document, and `firebase/firestore.rules`
refuses to serve it until three verified accounts have accepted:

```
match /precise/{doc} {
  allow read: if signedIn() && (
    alertOf(alertId).ownerUid == request.auth.uid ||
    alertOf(alertId).acceptedCount >= 3
  );
}
```

That rule runs on Google's servers. Decompiling the app, forging a request or
patching the APK does not get you past it — which is the only way a promise like
"your location is protected" means anything.

Your own contacts and the police are never subject to the gate. They get the exact
point in the first second, by SMS.

---

## Your six requirements

| # | Asked for | Where it lives |
|---|---|---|
| 1 | Voice activation with a keyword the user sets | `GuardianService.startListening()` — Android `SpeechRecognizer`, on-device where the phone supports it. The word is set in setup. |
| 2 | Vibration alert to everyone in 1 km, then 2 km, then wider, with live location and camera | `Cloud.fanOut()` + the radius ladder in `GuardianService.runAlert()` |
| 3 | Precise location only after 3–4 people accept | `firebase/firestore.rules`, enforced server-side |
| 4 | Alert to friends, family and the nearest police station | `Messenger.sendTo()` — real SMS, no interaction |
| 5 | Camera records, evidence to the cloud | `EvidenceRecorder` + `Cloud.uploadSnapshot()` / `uploadVideo()` |
| 6 | Discreet use in a club or public place | Shake trigger and **Silent alerts** in settings: no siren, no bright screen, the phone looks untouched |
| + | Video saved to the gallery when recording stops | `EvidenceRecorder` writes to MediaStore, so the clip *is* a gallery item |
| + | Video sent to contacts | The link is texted automatically the moment the upload finishes; the file itself goes by WhatsApp with one tap |
| + | Minimal, uncluttered under stress | Home is one button and one sentence |

---

## Being honest about three things

**WhatsApp needs one tap.** Android does not let any app send a WhatsApp message on
your behalf — that is a platform rule, not a design choice. So the video is *saved*
and *uploaded* automatically, the download link is *texted* automatically, and the
file itself is queued in a notification that opens WhatsApp with the video already
attached. One tap, afterwards, when you are safe.

**SEND_SMS is granted once.** Android will not let any app text without the
permission being granted by a human at least once. It is asked for during setup and
never again — during an emergency there is no prompt, no confirmation, nothing.
(Note: Google Play restricts this permission, so a Play release would need a
declaration. Sideloading is unaffected.)

**Keyword spotting is Android's recogniser, not a wake-word model.** It runs
on-device on Android 13+ where the phone ships the offline model, and it costs
battery. A production version would use a small always-on wake-word model
(Porcupine, openWakeWord) instead. Shake-to-trigger works with the screen off and
costs nothing, which is why it is on by default too.

---

## Firebase

The neighbourhood broadcast is the one thing a phone cannot do alone.
**`FIREBASE_SETUP.md`** walks through it — about ten minutes, free tier, no billing
account, no Cloud Functions.

Until you do it the app runs in offline mode, and **everything else still works**:
the safe word, the recording, the gallery save, the SMS with live location, the
siren, the shake trigger. The app is never less useful than a phone with no signal.

---

## Layout

```
app/src/main/java/com/draupadi/app/
  MainActivity.kt          routing between five screens, permission requests
  core/       Geo          grid cells, distance, the blurred location
              Buzz         vibration, including real morse SOS
              AppState     the two flows the service writes and the UI reads
  data/       Prefs        name, safe word, contacts, toggles
  net/        Cloud        auth, the geo index, fan-out, evidence upload
  service/    GuardianService   listens, triggers, runs the whole alert
              EvidenceRecorder  CameraX straight into the gallery
              Messenger         SMS and the WhatsApp hand-off
  ui/         one file per screen, plus the hold-to-confirm controls
firebase/     firestore.rules, storage.rules — the consent gate
.github/      the workflow that builds the APK
```

One service does the listening *and* runs the alert. Splitting them would be tidier,
but Android 14 refuses to let a background app start a camera service — and an SOS
must never lose a race with a policy check.

---

Not a substitute for emergency services. In India, dial **112**.
