package com.healthlife.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import java.util.Calendar;

/**
 * 久坐提醒广播接收器
 *   - 由 AlarmManager 每 30 分钟触发一次
 *   - 先检查「开关」+「时间段」，满足条件才发通知
 *   - 时间段：8:00–11:30、13:00–17:00
 */
public class SitReminderReceiver extends BroadcastReceiver {

    private static final String CHANNEL_ID = "sit_reminder_channel";
    private static final int    NOTIFICATION_ID = 1001;

    @Override
    public void onReceive(Context context, Intent intent) {
        // 1) 检查开关
        SharedPreferences sp = context.getSharedPreferences("health_life", Context.MODE_PRIVATE);
        boolean enabled = sp.getBoolean("sit_reminder", true);
        if (!enabled) return;

        // 2) 检查时间段：8:00–11:30 或 13:00–17:00
        Calendar c = Calendar.getInstance();
        int mins = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        boolean inMorning   = mins >= 8 * 60  && mins < 11 * 60 + 30;
        boolean inAfternoon = mins >= 13 * 60 && mins < 17 * 60;
        if (!inMorning && !inAfternoon) return;

        // 3) 发通知
        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        // Android 8.0+ 必须先建 channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "久坐提醒",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("每 30 分钟提醒起身走动（仅 8:00–11:30 和 13:00–17:00）");
            channel.enableVibration(true);
            nm.createNotificationChannel(channel);
        }

        // 点击通知回到 App
        Intent openApp = new Intent(context, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(context, 0, openApp, flags);

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("该站起来走动了")
                        .setContentText("起身 → 走动2–3分钟 → 下巴轻收5次 → 肩胛向后下轻收5–8次")
                        .setStyle(new NotificationCompat.BigTextStyle()
                                .bigText("起身 → 走动2–3分钟 → 下巴轻收5次 → 肩胛向后下轻收5–8次"))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setDefaults(NotificationCompat.DEFAULT_VIBRATE | NotificationCompat.DEFAULT_SOUND)
                        .setAutoCancel(true)
                        .setContentIntent(pi);

        nm.notify(NOTIFICATION_ID, builder.build());
    }
}
