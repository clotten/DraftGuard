package com.draftguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * 保活用的前台服务。
 *
 * 为什么要它：安卓会按内存压力回收后台进程，无障碍服务本身**不保证常驻**。
 * 一旦本应用进程被清掉，采集会中断（表现为"某段时间的记录断了"、计数归零）。
 * 前台服务是安卓给"用户明确知道正在运行"的任务留的后门：必须显示一条常驻通知，
 * 作为交换系统基本不会回收它。两者跑在同一进程，所以只要它活着，采集就活着。
 *
 * 代价就是通知栏那条通知。如果不想要，在应用「设置 → 后台保活」里关掉即可。
 */
public class KeepAliveService extends Service {

    private static final String TAG = "DraftGuardKeepAlive";
    private static final String CHANNEL_ID = "draftguard_keepalive";
    private static final int NOTI_ID = 1001;

    /** 通知内容刷新间隔：让用户一眼看到"还在记"，但不必频繁刷 */
    private static final long REFRESH_MS = 8000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastRefresh;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            notifyNow(getApplicationContext());
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    /** 启动保活；任何入口都可以调用，重复调用无副作用 */
    static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable t) {
            Log.w(TAG, "启动保活失败", t);
        }
    }

    static void stop(Context ctx) {
        try {
            ctx.stopService(new Intent(ctx, KeepAliveService.class));
        } catch (Throwable ignored) {
        }
    }

    /** 用户是否开启了保活 */
    static boolean isDesired(Context ctx) {
        return Prefs.keepAlive(ctx);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel(this);
        try {
            startForeground(NOTI_ID, build(this));
        } catch (Throwable t) {
            // Android 14+ 在极端情况下可能拒绝启动前台服务；失败不能让进程崩
            Log.w(TAG, "startForeground 失败", t);
        }
        handler.postDelayed(refresher, REFRESH_MS);
        Log.i(TAG, "保活服务已启动");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 被系统杀死后重建时也要继续保持前台状态
        try {
            startForeground(NOTI_ID, build(this));
        } catch (Throwable ignored) {
        }
        if (!Prefs.keepAlive(this)) {
            Log.i(TAG, "用户已关闭保活，退出");
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;   // 被系统回收后自动重建
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(refresher);
        Log.i(TAG, "保活服务已停止");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ 通知

    private static void createChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) {
                return;
            }
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "后台保活", NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("保持草稿记录服务常驻，防止被系统回收");
            ch.setShowBadge(false);
            ch.enableLights(false);
            ch.enableVibration(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        } catch (Throwable t) {
            Log.w(TAG, "创建通知渠道失败", t);
        }
    }

    private static Notification build(Context ctx) {
        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, piFlags);

        String text = statusText();

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(ctx, CHANNEL_ID);
        } else {
            b = new Notification.Builder(ctx);
        }
        b.setContentTitle("DraftGuard 记录中")
         .setContentText(text)
         .setSmallIcon(R.mipmap.ic_launcher)
         .setContentIntent(pi)
         .setOngoing(true)
         .setShowWhen(false)
         .setPriority(Notification.PRIORITY_MIN);
        if (Build.VERSION.SDK_INT >= 21) {
            b.setVisibility(Notification.VISIBILITY_SECRET);
        }
        return b.build();
    }

    private static String statusText() {
        long w = TypelogService.written;
        String app = TypelogService.lastAppLabel;
        StringBuilder sb = new StringBuilder();
        sb.append("已保存 ").append(w).append(" 条");
        if (app != null && !app.isEmpty()) {
            sb.append("　最近：").append(app).append(' ').append(TypelogService.lastText.length()).append(" 字");
        }
        return sb.toString();
    }

    private static void notifyNow(Context ctx) {
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTI_ID, build(ctx));
            }
        } catch (Throwable ignored) {
        }
    }
}
