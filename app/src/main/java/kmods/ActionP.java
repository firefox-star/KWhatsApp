package kmods;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Environment;
import android.preference.Preference;
import android.util.AttributeSet;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;

import static kmods.Utils.getResID;
import static kmods.Utils.vers;

public class ActionP extends Preference {
    public ActionP(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    public ActionP(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public ActionP(Context context) {
        super(context);
    }
    @Override
    protected void onClick() {
        super.onClick();
        switch (getKey()) {
            case "cfu":
                new Update(getContext()).execute((String[]) new String[0]);
                break;
            case "reset":
                getContext().getSharedPreferences("kmods_privacy", 0).edit().clear().apply();
                Toast.makeText(getContext(), "All Privacy Reset", Toast.LENGTH_SHORT).show();
                break;
            case "backup":
                if (new File(Environment.getDataDirectory(), "data/com.whatsapp").exists()) {
                    if (!new File(Environment.getExternalStorageDirectory(), "WhatsApp/KBackup").exists()){
                        new File(Environment.getExternalStorageDirectory(), "WhatsApp/KBackup").mkdir();
                    }
                    if (!new File(Environment.getExternalStorageDirectory(), "WhatsApp/KBackup/com.whatsapp").exists()){
                        new File(Environment.getExternalStorageDirectory(), "WhatsApp/KBackup/com.whatsapp").mkdir();
                    }
                    new CopyTask(getContext(), true, new File(Environment.getDataDirectory(), "data/com.whatsapp"), new File(Environment.getExternalStorageDirectory(), "WhatsApp/KBackup/com.whatsapp")).execute(new File[0]);
                } else {
                    Toast.makeText(getContext(), "Can't find a Data!", Toast.LENGTH_SHORT).show();
                }
                break;
            case "restore":
                if (new File(Environment.getExternalStorageDirectory(), "WhatsApp/KBackup").exists() && new File(Environment.getExternalStorageDirectory(), "WhatsApp/KBackup/com.whatsapp").exists()) {
                    new CopyTask(getContext(), false, new File(Environment.getExternalStorageDirectory(), "WhatsApp/KBackup/com.whatsapp"), new File(Environment.getDataDirectory(), "data/com.whatsapp")).execute(new File[0]);
                } else {
                    Toast.makeText(getContext(), "Can't find a backup in '/sdcard/WhatsApp/KBackup'!", Toast.LENGTH_SHORT).show();
                }
                break;
            case "share":
                final String string = getContext().getString(getResID("ShareBdy", "string"));
                final Intent intent3 = new Intent("android.intent.action.SEND");
                intent3.setType("text/plain");
                intent3.putExtra("android.intent.extra.SUBJECT", getContext().getString(getResID("ShareSbj", "string")));
                intent3.putExtra("android.intent.extra.TEXT", string);
                getContext().startActivity(Intent.createChooser(intent3, getContext().getString(getResID("Share", "string"))));
                break;
            case "report":
                final Intent intent2 = new Intent("android.intent.action.SEND");
                intent2.setType("message/rfc822");
                intent2.putExtra("android.intent.extra.EMAIL", new String[] { "patel.kuldip91@gmail.com" });
                intent2.putExtra("android.intent.extra.SUBJECT", "KWhatsApp v" + Integer.parseInt(vers[0]) + "." + Integer.parseInt(vers[1]));
                intent2.putExtra("android.intent.extra.TEXT", "");
                try {
                    getContext().startActivity(Intent.createChooser(intent2,"Report..."));
                } catch (Exception ex) {
                    Toast.makeText(getContext(), "Can't find email client.", Toast.LENGTH_SHORT).show();
                }
                break;
            case "clemoji":
                final File file = new File("/data/data/com.whatsapp/files/emoji");
                if (file.exists()) {
                    file.delete();
                    Toast.makeText(getContext(), "All Recent Emojis Cleared", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(getContext(), "No Recent Emojis There!", Toast.LENGTH_SHORT).show();
                }
                break;
            case "cllogs":
                final File files = new File("/data/data/com.whatsapp/files/","Logs");
                if (files.exists() && files.isDirectory()) {
                    files.delete();
                    Toast.makeText(getContext(), "All Logs Cleared", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(getContext(), "No Logs There!", Toast.LENGTH_SHORT).show();
                }
                break;
            case "ai_logs":
                showAiLogs();
                break;
            case "ai_test":
                new AiTestTask(getContext()).execute((String[]) new String[0]);
                break;
            case "credits":
                AlertDialog.Builder alertDialog;
                alertDialog = new AlertDialog.Builder(getContext());
                alertDialog.setTitle("Credits");
                WebView wv = new WebView(getContext());
                wv.loadUrl("file:///android_asset/credits.html");
                alertDialog.setView(wv);
                alertDialog.setNeutralButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.dismiss();
                    }
                });
                alertDialog.show();
                break;
            case "clogs":
                AlertDialog.Builder ab;
                ab = new AlertDialog.Builder(getContext());
                ab.setTitle("Changelog");
                WebView cl = new WebView(getContext());
                cl.loadUrl("file:///android_asset/CL.html");
                ab.setView(cl);
                ab.setNeutralButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.dismiss();
                    }
                });
                ab.show();
                break;
        }
    }

    /** Scrollable monospace viewer for the AI debug log ring buffer. */
    private void showAiLogs() {
        android.app.AlertDialog.Builder ab = new android.app.AlertDialog.Builder(getContext());
        ab.setTitle("AI Debug Logs (tag: AiMods)");
        android.widget.TextView tv = new android.widget.TextView(getContext());
        tv.setText(kmods.ai.AiLogger.dump());
        tv.setTextIsSelectable(true);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextSize(11);
        int pad = (int) (16 * getContext().getResources().getDisplayMetrics().density);
        android.widget.ScrollView sv = new android.widget.ScrollView(getContext());
        sv.addView(tv);
        sv.setPadding(pad, pad / 2, pad, 0);
        ab.setView(sv);
        ab.setNeutralButton("Clear", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int id) {
                kmods.ai.AiLogger.clear();
                Toast.makeText(getContext(), "AI logs cleared", Toast.LENGTH_SHORT).show();
            }
        });
        ab.setPositiveButton("OK", null);
        ab.show();
    }

    /** Runs one real request against the configured API and reports the result. */
    private static class AiTestTask extends android.os.AsyncTask<String, Void, String> {
        private final Context ctx;
        AiTestTask(Context ctx) {
            this.ctx = ctx.getApplicationContext();
        }
        @Override
        protected String doInBackground(String... params) {
            try {
                return kmods.ai.AiEngine.testConnectionSync(ctx);
            } catch (Throwable t) {
                return "FAILED: " + t.getClass().getSimpleName();
            }
        }
        @Override
        protected void onPostExecute(String result) {
            try {
                boolean ok = result != null && result.startsWith("Connected");
                Toast.makeText(ctx, result, ok ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        }
    }
}