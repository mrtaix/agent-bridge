package com.harness.agentbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            Intent svc = new Intent(context, BridgeService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
            Log.i(BridgeService.TAG, "boot start requested");
        } catch (Throwable t) {
            Log.w(BridgeService.TAG, "boot start failed", t);
        }
    }
}
