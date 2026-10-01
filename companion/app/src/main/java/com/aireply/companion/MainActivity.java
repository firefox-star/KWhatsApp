package com.aireply.companion;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.aireply.companion.ai.AiConfig;
import com.aireply.companion.ai.AiEngine;
import com.aireply.companion.ai.AiLogger;

/**
 * Dashboard: at-a-glance status (AI on/off, notification access, API
 * configured) plus one-tap entry points: AI Settings, Test AI Connection,
 * Debug Logs, and the Android notification-access screen.
 */
public class MainActivity extends Activity {

    private static final int REQ_POST_NOTIFICATIONS = 41;

    private TextView tvAi, tvAccess, tvConfig, tvTestResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvAi = findViewById(R.id.status_ai);
        tvAccess = findViewById(R.id.status_access);
        tvConfig = findViewById(R.id.status_config);
        tvTestResult = findViewById(R.id.test_result);

        findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, AiSettingsActivity.class));
            }
        });

        findViewById(R.id.btn_access).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestNotificationAccess();
            }
        });

        findViewById(R.id.btn_logs).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, LogActivity.class));
            }
        });

        findViewById(R.id.btn_test).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runTest();
            }
        });

        // Android 13+: our own suggestion notifications need POST_NOTIFICATIONS.
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_POST_NOTIFICATIONS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        tvAi.setText(AiConfig.isEnabled(this) ? R.string.on : R.string.off);
        tvAi.setTextColor(AiConfig.isEnabled(this) ? 0xFF25D366 : 0xFFFF6B6B);

        boolean access = hasNotificationAccess();
        tvAccess.setText(access ? R.string.granted : R.string.not_granted);
        tvAccess.setTextColor(access ? 0xFF25D366 : 0xFFFF6B6B);

        boolean configured = AiConfig.isConfigured(this);
        tvConfig.setText(configured ? R.string.configured : R.string.not_configured);
        tvConfig.setTextColor(configured ? 0xFF25D366 : 0xFFFF6B6B);
    }

    private boolean hasNotificationAccess() {
        try {
            String listeners = Settings.Secure.getString(
                    getContentResolver(), "enabled_notification_listeners");
            return listeners != null && listeners.contains(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private void requestNotificationAccess() {
        try {
            Toast.makeText(this, R.string.notif_access_rationale, Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Throwable t) {
            AiLogger.e(this, "Cannot open notification access settings", t);
        }
    }

    private void runTest() {
        tvTestResult.setVisibility(View.VISIBLE);
        tvTestResult.setText("Testing…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String result = AiEngine.testConnectionSync(MainActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tvTestResult.setText(result);
                    }
                });
            }
        }).start();
    }
}
