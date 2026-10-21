# NeoDev

A code editor for your phone: edit files, push to GitHub, run GitHub Actions builds, and download the APK or JAR, all inside the app.

## Features
- Code editor with syntax highlighting (Java, Kotlin, Python, Skript, YAML, JSON, XML, Gradle), tabs, undo/redo, find and replace, auto-indent, symbol bar
- Clone any of your GitHub repositories, create new projects from templates (Android app, Paper plugin, Skript, Python)
- Commit and push, pull latest, create a new GitHub repository from a local project
- Build screen: run workflows, watch live steps, read failed logs, ask AI to explain the failure
- Download the built APK (or JAR) to Downloads/NeoDev, install it, or share it
- Create with AI: describe an app in a chat and NeoDev writes the whole project, then changes it whenever you ask (needs a Gemini key)
- One-tap Build: creates the GitHub repo if needed, pushes, runs the workflow and shows the build; if it fails, "Let NeoDev fix it" repairs the project from the error log
- AI helper (Gemini): explain a file, fix problems, ask questions, apply the returned code

## Build it
Push this repo to GitHub. The included workflow (.github/workflows/build.yml) builds the APK on every push.
Download it from the Actions tab, or open the app after installing and use its own Build screen.

## Gemini key
Option A (recommended): in the GitHub repo go to Settings > Secrets and variables > Actions > New repository secret.
Name: GEMINI_API_KEY, value: your key. The workflow inserts it at build time, so the key is never committed.

Option B: replace the placeholder in app/src/main/java/com/cusil/neodev/Config.kt with your key before pushing.
Anyone who can see the repo or extract the APK can read that key. Use it only for a private repo and private APK.

Users can always enter their own key in Settings, which overrides the built-in one.

## GitHub token
Create a classic token with the repo and workflow scopes and paste it in NeoDev Settings.

## Updating the installed app
Builds keep the same signing key through a cache, so new versions install over old ones.
The very first update from an older build may need one uninstall (push your projects to GitHub first).
