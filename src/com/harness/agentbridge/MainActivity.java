package com.harness.agentbridge;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    private TextView info;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            info.setText("status: " + BridgeService.status()
                    + "\ncdp: 127.0.0.1:" + BridgeService.CDP_PORT
                    + "\ncmd: 127.0.0.1:" + BridgeService.CMD_PORT
                    + "\ntoken: " + BridgeService.token());
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        info = new TextView(this);
        info.setTextSize(13);
        root.addView(info);

        Button start = new Button(this);
        start.setText("Start bridge");
        start.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                Intent svc = new Intent(MainActivity.this, BridgeService.class);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
                else startService(svc);
            }
        });
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("Stop bridge");
        stop.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                stopService(new Intent(MainActivity.this, BridgeService.class));
            }
        });
        root.addView(stop);

        setContentView(root);
        handler.post(tick);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(tick);
        super.onDestroy();
    }
}
