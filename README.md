# TurtleTavern Mobile

The Android shell for [TurtleTavern (GO)](https://github.com/DaTurtleGuy/TurtleTavern-GO-):
a WebView wrapping the Go server running **in-process** (bound via gomobile).
No remote server, no Node — install and use.

> **Heads up:** this is my first *real* Android project and I have zero formal
> clue what I'm doing — it's vibe-coded end to end (see the Go repo's README
> for the model rotation madness). It works on my phone (Galaxy A54). If you
> find a bug, the app keeps rotating logcat + file logs precisely so you can
> send me the smoking gun.

### The x86_64 saga (why there's no emulator build)

Android's x86_64 seccomp blocks the `stat` syscall family that SQLite needs,
so the app hard-crashes (`SIGSYS`) on any x86_64 emulator. I fought this off
and on for two hours — swapped SQLite drivers, tried CGO tricks, even
considered building an arm64 emulator — and then simply gave up and shipped
arm64-only, because every phone I care about is arm64 and the Go server works
fine under Termux. If you know a clean fix, PRs will be accepted with
unhealthy amounts of gratitude.

## Features

- Embedded Go server on 127.0.0.1 (gomobile AAR), WebView front with the
  unchanged SillyTavern UI
- **arm64 only** — Android's x86_64 seccomp blocks the stat syscalls SQLite
  needs; the app shows a clear "not supported" screen elsewhere
- Swipe-right drawer:
  - **Config** — edit `config.yaml` live, Save, and **Restart** the server
  - **Logs** — last 256 KiB of server logs (Go ring buffer)
  - **Import Backup…** — picks a zip and restores locally: verified hashes,
    and a hash-mismatch dialog with "Import anyway"
  - **Keep server running (wake lock)** — foreground service + partial wake
    lock so OneUI stops killing the app
- Exports stream into the system Downloads (notification included)

## Building

Requirements: JDK 17+, Android SDK (API 37), NDK 30, gomobile tooling.

1. Generate `bootstrap.zip` (frontend bundle) from the TurtleTavern(GO)
   server repo:
   ```bash
   python tools/pack_mobile_assets.py <gotavern-repo-path> \
       app/src/main/assets/bootstrap.zip
   ```
2. Build the Go AAR (from the server repo):
   ```bash
   gomobile bind -target=android/arm64 -androidapi=30 \
       -javapkg=com.daturtleguy.turtletavern \
       -o <mobile-repo>/app/libs/gotavern.aar ./gotavern
   ```
3. `.\gradlew assembleRelease`

`versionName` / `versionCode` bump every release; the keystore lives outside
the repo (`app/signing/` — credentials not committed).

## Debug builds vs release

Debug builds behave identically (no special flags needed), but releases are
signed with the project keystore so users can update in place.

## Migrating from the Node version

Export the user-data backup in the node TurtleTavern fork and import that
zip in this app — formats are wire-compatible. Details in the Go repo's
`public/MIGRATION.md`.
