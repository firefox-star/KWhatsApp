# Reference smali snippets for the KWhatsApp AI Mod patches.
# These are TEMPLATES - register names must be adapted to the exact base APK.
#
# The only REQUIRED patch (Patch A) is a single invoke-static line at the end
# of com.whatsapp.Conversation.onCreate, shown below.
#
# ============================================================
# Patch A - com/whatsapp/Conversation.smali  (REQUIRED)
# ============================================================
#
# Find:
#   .method public onCreate(Landroid/os/Bundle;)V
#
# At the very end of the method, right before its final return, add:
#
#     invoke-static {p0}, Lkmods/ai/AiReplies;->attachToConversation(Landroid/app/Activity;)V
#
# Full template (illustrative):
#
# .method public onCreate(Landroid/os/Bundle;)V
#     .locals 4
#     .param p1, "savedInstanceState"    # Landroid/os/Bundle;
#
#     ...existing WhatsApp code (setContentView, adapter init, etc.)...
#
#     # KWhatsApp AI Mod: install smart-reply suggestion bar
#     invoke-static {p0}, Lkmods/ai/AiReplies;->attachToConversation(Landroid/app/Activity;)V
#
#     return-void
# .end method
#
# ============================================================
# Patch B - incoming-message hook  (OPTIONAL, real-time)
# ============================================================
#
# Locate the method invoked when a completed text message is inserted for the
# currently open conversation. In base v2.17.87 this is reached from the
# conversation message-list observer (search for classes holding a reference
# to the Conversation instance that trigger list refresh after DB inserts).
#
# When the following values are on the smali stack/registers:
#   - Context  (the Conversation activity or the application)
#   - String   chat jid          e.g. "15551234567@s.whatsapp.net" / "12-34@g.us"
#   - String   sender jid        e.g. "15559876543@s.whatsapp.net" ("" in 1:1)
#   - String   message body
#
# insert:
#
#     invoke-static {vCtx, vJid, vSender, vBody}, Lkmods/ai/AiReplies;->onIncomingMessage(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
#
# Safety: the Java implementation filters everything itself (enabled? auto-
# suggest? same chat open?), so this call is safe on every message path.
#
# ============================================================
# Patch C - com/whatsapp/Conversation.smali  (OPTIONAL)
# ============================================================
#
# .method public onDestroy()V
#     ...existing code...
#     invoke-static {}, Lkmods/ai/AiReplies;->onConversationDestroy()V
#     return-void
# .end method
