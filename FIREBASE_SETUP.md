# Wiring up the neighbourhood broadcast

Ten minutes, once. Free tier — no billing account, no Cloud Functions, no card.

Everything else in Draupadi works without this. What you unlock here is the one
thing a single phone cannot do: reaching other people's phones.

---

## 1. Make the project

1. Go to <https://console.firebase.google.com> and sign in.
2. **Add project** → name it `draupadi` → **Continue**.
3. Turn **Google Analytics off** (you do not need it) → **Create project**.

## 2. Add the Android app

1. On the project home, click the **Android** icon.
2. **Android package name** — this must match exactly:

   ```
   com.draupadi.app
   ```

3. Nickname: `Draupadi`. Leave the SHA-1 box empty.
4. **Register app** → **Download google-services.json**.
5. Replace the placeholder file in this repo:

   ```
   app/google-services.json      ← overwrite this with your download
   ```

   The one already there is a dummy so the project always builds. Overwriting it is
   what switches the app from offline mode to connected.

6. Skip the remaining "add the SDK" steps — the Gradle files already have all of it.

## 3. Turn on anonymous sign-in

Nobody should have to make an account to be able to help a stranger.

1. **Build → Authentication → Get started**.
2. **Sign-in method** tab → **Anonymous** → enable → **Save**.

## 4. Create the database

1. **Build → Firestore Database → Create database**.
2. Pick a location near you — `asia-south1 (Mumbai)` for India.
3. Choose **production mode** (the rules come next).
4. Open the **Rules** tab, delete what is there, paste the whole of
   `firebase/firestore.rules` from this repo, and **Publish**.

This is the important step. Those rules are what make the consent gate real: the
server itself refuses to hand a woman's exact position to anyone until three
verified accounts have accepted.

## 5. Create storage

1. **Build → Storage → Get started** → accept the default bucket.
2. **Rules** tab → paste `firebase/storage.rules` → **Publish**.

## 6. Rebuild

Commit the new `google-services.json` and push:

```bash
git add app/google-services.json
git commit -m "Connect Firebase"
git push
```

GitHub rebuilds the APK. Install it, open **Settings** inside the app, and the
network line should read **Connected**.

---

## Proving it works

You need two phones with the app installed — a hackathon table is the perfect place
to borrow one.

1. Install on both, finish setup on both, and leave both open once.
2. On phone B, make sure **Answer other people's alerts** is on in settings.
3. On phone A, hold the button.
4. Phone B should buzz morse SOS within a few seconds and show
   *"Someone 40 m away needs help"* — with a distance, and no exact point.
5. Tap **I can help** on phone B. Phone A's screen shows `1 of 3 accepted`.
6. Get a third phone to accept, or accept twice from different installs, and watch
   phone B's screen switch to **Open in Maps** the instant the count hits three.

That moment — the location appearing only after the third person commits — is the
whole idea of the app, and it is worth demonstrating live rather than describing.

---

## What it costs

Nothing, at any scale you will reach.

The free Spark plan gives 50,000 document reads and 20,000 writes a day. One alert
that reaches 50 nearby phones costs roughly 60 writes and a few hundred reads. You
would need hundreds of alerts a day to leave the free tier.

The fan-out is written from the phone rather than from a Cloud Function precisely so
that no billing account is needed. In production you would move it server-side and
push through FCM — that scales further and works even when the responder's app is
not running.
