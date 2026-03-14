# CardioBuddy

Personal Android app to record heart rate data from a BLE heart rate monitor over a workout session.

## Features

- Scans for and connects to any BLE-compliant heart rate monitor
- Records timestamped HR data to CSV while the screen is off
- Persistent foreground notification with live BPM and a Stop button
- Session summary: duration, average HR, peak HR, sample count
- Share/export CSV via standard Android share sheet

## CSV Format

```
timestamp_ms,timestamp_iso,heart_rate_bpm
1710412800000,2024-03-14T10:00:00.000,72
1710412801000,2024-03-14T10:00:01.000,74
```

Files are saved to: `/sdcard/Android/data/com.nolan.cardiobuddy/files/hr_session_YYYYMMDD_HHMMSS.csv`

## Building (GitHub Actions — no local tools needed)

### One-time setup

1. Create a free account at [github.com](https://github.com) if you don't have one
2. Create a new **public** repository (private repos also work, but public is simplest)
3. Download and install [GitHub Desktop](https://desktop.github.com/) — a free GUI, no command line needed

### Push the code

1. In GitHub Desktop: **File → Clone Repository** → paste your new repo URL → Choose local path → Clone
2. Copy all files from this project folder into that local folder (keeping the directory structure)
3. In GitHub Desktop you'll see all files listed as new — write any commit message → **Commit to main** → **Push origin**

### Get the APK

1. Go to your repo on github.com
2. Click the **Actions** tab — you'll see a workflow run in progress (takes ~3-5 minutes)
3. Click the run → scroll to **Artifacts** → download **cardiobuddy-debug**
4. Unzip it — inside is `app-debug.apk`

### Sideload to phone

1. On your Android phone: Settings → Security → enable **Install unknown apps** for your file manager
2. Copy the APK to your phone (USB, email yourself, Google Drive, etc.)
3. Tap the APK file → Install

## Usage

1. Put on your HR monitor and activate it (chest straps: breathe on contacts)
2. Open the app → **Scan for Devices** (15 second scan)
3. Tap your device in the list
4. App connects and starts recording automatically
5. Lock your screen — recording continues in the background
6. When done, pull down the notification shade → tap **Stop** in the notification, OR open the app and tap **Stop Recording**
7. Summary screen shows stats and lets you export the CSV

## Requirements

- Android 8.0 (API 26) or higher
- Bluetooth LE heart rate monitor supporting the standard Heart Rate Profile (UUID 0x180D)
