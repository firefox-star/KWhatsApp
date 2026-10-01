# KWhatsApp AI Mod - Integration Guide (smali merge)

This document explains how the AI feature set added to this repository is
wired into a WhatsApp base APK (workflow described in the project README:
compile this project -> decompile the result -> merge smali + resources into
the WhatsApp base).

AI feature code lives in the `kmods.ai` package:

| Class | Role |
|---|---|
| `kmods.ai.AiConfig` | reads AI Settings from `com.whatsapp_preferences`, HTTPS validation |
| `kmods.ai.CryptoStore` | AES/GCM (AndroidKeyStore on API 23+, PBKDF2 fallback on older) storage for the API key |
| `kmods.ai.ApiKeyPreference` | password-style key editor; persists the ENCRYPTED blob only |
| `kmods.ai.AiSettingsUi` | binds the "AI Mods" settings section (summaries, HTTPS check) |
| `kmods.ai.AiProvider` | provider interface - modular, add new APIs here |
| `kmods.ai.OpenAiCompatibleProvider` | any `/chat/completions` API (OpenAI, Groq, OpenRouter, DeepSeek, Z.ai, Ollama, ...) |
| `kmods.ai.GeminiProvider` | Google Gemini `generateContent` API |
| `kmods.ai.Providers` | provider factory + auto-detect from Base URL |
| `kmods.ai.AiHttpClient` | strict-HTTPS HTTP layer (HttpURLConnection, default TLS only) |
| `kmods.ai.MessageHistory` | reads recent turns from WhatsApp's `messages` table |
| `kmods.ai.AiEngine` | orchestrates: history -> provider -> suggestions, threading, dedupe |
| `kmods.ai.SmartReplyBar` | the suggestion chips UI above the input box |
| `kmods.ai.AiReplies` | **the smali hook facade** |
| `kmods.ai.AiLogger` | logging (logcat tag `AiMods`) + in-app log viewer |

---

## 1. What needs patching in WhatsApp's smali

Only **one patch is required**; a second (optional) one enables real-time
suggestions while a chat is open.

### Patch A (required) - attach the smart reply bar

In `com/whatsapp/Conversation.smali`, at the end of `onCreate(...)`
(after `setContentView` / view initialization), add:

```smali
    invoke-static {p0}, Lkmods/ai/AiReplies;->attachToConversation(Landroid/app/Activity;)V
```

Java-side behavior (already implemented - no further work):
- reads the chat jid from the intent extra `"jid"`
- finds the input box via resource id `"entry"` (falls back to clipboard mode)
- inserts `SmartReplyBar` above the entry container
- when "Auto Suggest Replies" is enabled, immediately analyzes the newest
  incoming text message read from WhatsApp's `messages` database

Example `onCreate` tail (base v2.17.87, exact registers may differ):

```smali
.method public onCreate(Landroid/os/Bundle;)V
    ...existing code...

    invoke-static {p0}, Lkmods/ai/AiReplies;->attachToConversation(Landroid/app/Activity;)V

    return-void
.end method
```

### Patch B (optional) - real-time suggestions on incoming messages

Patch B is only needed if you want the bar to refresh the moment a message
arrives while the chat is open (without it, suggestions still load when the
chat is opened / refresh chip is tapped).

Find the smali method that handles a completed incoming text message for the
open conversation (in 2.17.87 the conversation message adapter receives insert
notifications; search for the class that calls the conversation update after
`messages` table inserts). At the point where the message body (`String`) and
chat jid (`String`) are both available, add:

```smali
    # v0 = context (the Conversation activity), v1 = chat jid, v2 = sender jid, v3 = message body
    invoke-static {v0, v1, v2, v3}, Lkmods/ai/AiReplies;->onIncomingMessage(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
```

The Java side is a no-op unless:
- AI features are enabled AND auto-suggest is on, and
- the currently attached bar belongs to the same jid.

That makes the patch safe to call from message paths for ALL chats.

### Patch C (optional, cleanliness) - detach

In `com/whatsapp/Conversation.smali` `onDestroy()` add:

```smali
    invoke-static {}, Lkmods/ai/AiReplies;->onConversationDestroy()V
```

(The bar is also removed automatically when the activity view tree goes away.)

---

## 2. Resource merge checklist

- `smali/kmods/` (all mod classes incl. new `kmods/ai/*`) -> merge into
  WhatsApp's `smali/` tree.
- `res/xml/settings.xml` -> merge the `AI Mods` category into WhatsApp's
  `res/xml/settings.xml` (or add this file if the base has none; the mod
  loads it via `getResID("settings","xml")`).
- `res/values-v1/*` -> merge strings/arrays/ids (`ai_provider_names`,
  `ai_provider_values`).
- Manifest: the mod's own manifest does NOT need `INTERNET` - WhatsApp's
  merged manifest already holds `android.permission.INTERNET`.
- If your toolchain renames/drops `kmods.ai.ApiKeyPreference` in the XML
  inflater, keep `minifyEnabled false` (this repo's default) so the custom
  preference class survives.

---

## 3. How the pieces satisfy the requirements

1. **AI Settings section** - new "AI Mods" category in KMods settings
   (`res/xml/settings.xml`), rendered by the existing `kmods.Settings` activity
   (menu: KMods > AI Mods).
2. **Configurable fields** -
   `ai_base_url` (Base URL), `ai_model` (Model), `ai_api_key` (encrypted via
   `CryptoStore`), `ai_enabled` (master toggle) + extras: provider type,
   system prompt, max tokens, auto-suggest, debug logging.
3. **Message analysis hooks** -
   `AiReplies.onIncomingMessage` / DB-based analysis via `MessageHistory`;
   suggestions render as chips; tap = accept, long-press = edit/copy/ignore.
4. **Smart reply UI** - `SmartReplyBar` (programmatic view, no resource
   collisions), injected above the entry box.
5. **HTTPS** - `AiConfig.validateBaseUrl` rejects non-HTTPS remote endpoints
   (plain HTTP only allowed for localhost/LAN test servers); `AiHttpClient`
   uses the platform's default certificate validation (no trust-all).
6. **Error handling** - `AiException.userMessage` texts for 401/403/404/429,
   network failures and bad JSON; shown as Toast + retry chip in the bar;
   every failure logged via `AiLogger` (logcat tag `AiMods` + in-app viewer).

---

## 4. Testing the AI features after merging

1. Open WhatsApp > KMods > AI Mods.
2. Enable AI Features.
3. Set **Base URL** (e.g. `https://api.groq.com/openai/v1`) and **Model**
   (e.g. `llama-3.1-8b-instant`), paste your **API Key**.
4. Tap **Test AI Connection** - expect
   "Connected to OpenAI-compatible (model) in ...ms".
5. Open any chat with recent incoming messages - the AI replies bar appears
   above the input box with up to 3 suggestion chips.
6. Tap a chip -> text lands in the input box (nothing is sent automatically).
   Long-press -> Edit / Copy / Ignore.
7. For issues: enable "AI Debug Logging", reproduce, then "View AI Debug Logs"
   (or `adb logcat -s AiMods`).

## 5. Adding another provider

Implement `kmods.ai.AiProvider` (one method: build request, parse reply),
then register it in `kmods.ai.Providers.resolve()` next to
`OpenAiCompatibleProvider` / `GeminiProvider`. No other file needs to change.
