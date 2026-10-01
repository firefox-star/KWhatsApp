# What is KWhatsApp?
KWhatsApp Is Basically Well Improved And Pollished WhatsApp. KWhatsApp is a modification(mod) of WhatsApp for Android. Includes new features like privacy.

# How to use
To include this code to WhatsApp you need to compile this project to an APK, and decompile to get the smali code, layout, pngs And Xmls to merge both apps.

## Building (modernized 2026)
The build was migrated from the defunct Gradle 2.14.1 / AGP 2.2.3 / jcenter
setup to **Gradle 8.13 + AGP 8.5.2 + JDK 17/21** (same output, same merge
workflow):

```bash
# requires JDK 17+ and the Android SDK (platform 34, build-tools 34.0.0)
./gradlew assembleRelease
# output: app/build/outputs/apk/release/app-release-unsigned.apk
```

A prebuilt, signed container APK (`KWhatsApp-v2.7-AI-release.apk`) is
attached to the GitHub Releases for direct decompiling.

## Updates
- KWhatsApp 2.7-AI
- Current Base: v2.17.87
- Clean Code from Scratch for new WhatsApp Base

# AI Features (new in 2.7-AI)
Bring your own AI API and get smart reply suggestions inside any chat:

- **AI Mods settings section** (KMods > AI Mods) with:
  - Enable/Disable toggle for all AI features
  - **AI API Base URL** - plug in any endpoint you have access to
  - **AI Model Name** - use exactly the model you want
  - **API Key / Auth Token** - password-style editor, stored ENCRYPTED
    (AndroidKeyStore AES/GCM on Android 6+, PBKDF2-derived key on older)
  - Provider type: Auto-detect / OpenAI-compatible / Google Gemini
  - Custom system prompt, max reply tokens, auto-suggest toggle
  - **Test AI Connection** button and in-app **AI Debug Logs** viewer
- **Smart reply suggestions** - an "AI replies" bar above the message input
  shows up to 3 candidate replies for the latest incoming message:
  - tap a chip to ACCEPT (text goes into the input box, you still send)
  - long-press to EDIT (small editor dialog) / COPY / IGNORE
  - refresh and close buttons; error state with tap-to-retry
- **Works with any free AI API**: OpenAI, Groq, OpenRouter, DeepSeek, Z.ai
  (GLM), Together, Mistral, local Ollama / LM Studio, Google Gemini - all
  through a modular `kmods.ai.AiProvider` interface (add your own in one
  class).
- **Secure by design**: HTTPS enforced for all remote APIs (plain HTTP only
  for localhost/LAN test servers), platform TLS certificate validation kept
  intact, API key never stored or logged in plaintext.
- **Graceful errors + logging**: friendly messages for 401/403/404/429 and
  network failures; logcat tag `AiMods` plus in-app log viewer.

Integration instructions for the merge workflow (smali patches, one required
one-liner) are in **tools/AiMod/README.md**.

# Features
- Fast , Secure , Stable and Clean as Stock
- Sms Verification Fixed
- Smart Custom Privacy (choose Privacy for Groups, Broadcasts, Contacts And Custom Contact)
- Media Sharing Limit Increased to 700MB (send video(s) upto 700MB)
- Send more than 10 images!
- Group Subject Name upto 45
- Select text in conversation
- Hide Archived Chat
- Contact Status Copy
- Hide Date and name while copying 2 messages or more
- Fast Backup and Restore
- Restore Button like wa reborn
- Auto Restart. Restart As Back Button Press.
- In App Update Checker With Auto Update Checking.
- MultiTask Chat.
- Contact Online Toast.
- Group Counter.
- Profile Pic Zoom.
- Clear Recent Emojis.
- Clear Logs.
- Bubble And Tick Changing.(Disabled)
- Send message to any WhatsApp number! Even if it's not in your contact!!
- Option to make Phone call instead of WhatsApp call
- All Hidden Features UnLocked.

## Technlogy Communication
> Email: patel.kuldip91@gmail.com

# **Note**:
This project is NOT affiliated or approved by WhatsApp Inc.