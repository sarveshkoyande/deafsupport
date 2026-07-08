# EchoSense

A phone-based mobility aid for **blind users**: it senses obstacles in front of you with
the camera and turns their distance and direction into sound (and vibration), like a
"reversing sensor" you can hear.

Designed to be worn/held facing forward and used with **bone-conduction headphones**, so
it never blocks the real-world sounds a blind person relies on.

> **Status: Stage 0 (diagnostic).**
> This first version only checks that the build pipeline works and that your phone supports
> depth sensing. It shows and *speaks aloud* the result, and plays a test beep.
> The real obstacle detector is Stage 1.

---

## How to build it (no coding tools needed on your PC)

The app is compiled **in the cloud by GitHub**, not on your computer. You only need a free
GitHub account.

### 1. Create an empty repository on GitHub
- Go to https://github.com/new
- Name it e.g. `echosense`
- Leave everything else default, click **Create repository**.
- Copy the repo URL, e.g. `https://github.com/YOUR-NAME/echosense.git`

### 2. Push this code (done from your PC once)
Your assistant can run these for you, or you can paste them in a terminal in this folder:

```bash
git add .
git commit -m "EchoSense Stage 0"
git branch -M main
git remote add origin https://github.com/YOUR-NAME/echosense.git
git push -u origin main
```

### 3. Let GitHub build it
- Open your repo on github.com → **Actions** tab.
- You'll see a build running ("Build APK"). Wait ~3–5 minutes for the green check.

### 4. Download the APK to your phone
- On your **phone's** browser, open your repo → **Releases** (right-hand side) →
  **Latest EchoSense build** → download **`EchoSense-debug.apk`**.
- Tap the downloaded file to install. You'll need to allow
  "Install unknown apps" for your browser when prompted (Android will guide you).

### 5. Run it
- Open **EchoSense**. It should speak the result aloud and beep.
- Tell your assistant what it said — that decides how we build Stage 1.

---

## Roadmap
- **Stage 0 (now):** pipeline + phone capability check. ✅ (once it runs on your phone)
- **Stage 1:** live camera + ARCore depth → nearest-obstacle detection → spatial beeps
  (pitch = distance, left/right pan = direction) + haptics for "very close".
- **Later:** tuning, calibration, optional external sensors for wider coverage.
