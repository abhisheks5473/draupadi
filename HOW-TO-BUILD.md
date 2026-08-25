# Getting the Draupadi APK — every step

GitHub compiles the app for you, free, in about four minutes. You need a GitHub
account and nothing else installed.

---

## Step 1 — Prepare the folder

Open a terminal:

```bash
cd ~/SOS/draupadi
./setup.sh
```

You should see:

```
✓ workflow moved to .github/workflows/build-apk.yml
✓ gradlew is executable
```

If `./setup.sh` says *permission denied*, run `bash setup.sh` instead.

---

## Step 2 — Make an empty repository on GitHub

1. Go to <https://github.com/new>
2. **Repository name:** `draupadi`
3. Choose **Private** (or Public — either builds).
4. **Do not** tick "Add a README", ".gitignore" or "license". The repo must be empty.
5. Click **Create repository**.

Leave that page open — you will need the URL.

---

## Step 3 — Push the code

Back in the terminal, in `~/SOS/draupadi`:

```bash
git init
git add -A
git commit -m "Draupadi"
git branch -M main
git remote add origin https://github.com/YOUR-USERNAME/draupadi.git
git push -u origin main
```

Replace `YOUR-USERNAME` with your actual GitHub username.

If git complains it does not know who you are:

```bash
git config --global user.email "abhisheks5473@gmail.com"
git config --global user.name "Abhishek"
```

then run the `git commit` line again.

### When it asks for a password

GitHub stopped accepting account passwords in 2021. Two ways through:

**Easiest — the GitHub CLI**

```bash
gh auth login
```

Choose *GitHub.com* → *HTTPS* → *Login with a web browser*, copy the code it shows,
press Enter, paste the code in the browser. Then run `git push -u origin main` again.

If `gh` is not installed: `sudo apt install gh`

**Or — a personal access token**

1. Go to <https://github.com/settings/tokens/new>
2. Note: `draupadi`, Expiration: 30 days
3. Tick the **`repo`** checkbox (that alone is enough)
4. **Generate token**, copy the string beginning `ghp_...`
5. Run `git push -u origin main` again. When it asks for **Username** type your
   GitHub username; when it asks for **Password**, paste the token.

The token is only shown once — copy it before leaving the page.

---

## Step 4 — Watch it build

1. Open `https://github.com/YOUR-USERNAME/draupadi`
2. Click the **Actions** tab.
3. A run called **Build APK** is already going. Click it, then click **build** to
   watch the log.
4. Give it about four minutes. A green tick means the APK exists.

---

## Step 5 — Get the APK

**From your phone (easiest)** — open

```
https://github.com/YOUR-USERNAME/draupadi/releases/latest
```

and tap `draupadi.apk`. It downloads as a real APK, ready to install.

**From your laptop** — on the finished Actions run, scroll to the bottom and click
**draupadi-apk** under Artifacts. That downloads a **.zip**; unzip it to get
`draupadi.apk` inside. (GitHub always zips artifacts — the Releases link above skips
that step, which is why it is better for a phone.)

---

## Step 6 — Install it

1. Open `draupadi.apk` on the phone.
2. Android says it cannot install from this source. Tap **Settings** on that prompt,
   turn on **Allow from this source**, come back and tap **Install**.
3. If it refuses with *App not installed*, uninstall any older Draupadi first.

---

## Step 7 — First run

1. Open Draupadi. Three setup screens:
   - your name and your **safe word**
   - **who gets texted** — pick from your contacts
   - **permissions** — tap *Allow everything* and accept each prompt
2. Tap **Stop Android from killing it** on the last screen. Find Draupadi in the
   list and set it to **Unrestricted**. Skipping this is the single most common
   reason a safety app silently stops working after a few hours.
3. Tap **Finish**.

You should now see one red button and the line *Listening for "your word"*.

### Test it safely

Remove your real contacts first, or add only your own second number — the alert
sends real SMS to real people.

- Hold the red button for a second and a half. The phone buzzes morse SOS, the timer
  starts, texts go out, the camera starts recording.
- Hold **I am safe**. Recording stops.
- Open your **Gallery** — the clip is there, in an album called *Draupadi*.

---

## Step 8 — Turn on the 1 km broadcast

Everything above works with no server. To make nearby phones buzz, follow
**FIREBASE_SETUP.md** — about ten minutes, free tier, no card needed. Then:

```bash
git add app/google-services.json
git commit -m "Connect Firebase"
git push
```

which rebuilds the APK automatically.

---

## If the build goes red

1. Click the failed run in **Actions**.
2. Click the **Build the debug APK** step.
3. Copy the lines containing the word `error:` — usually two or three.
4. Send them to me and I will fix them.

I wrote this app without being able to compile it (Google's build servers are not
reachable from my sandbox, which is exactly why the build runs on GitHub instead),
so a first-run error or two is likely. They are almost always one-line fixes.
