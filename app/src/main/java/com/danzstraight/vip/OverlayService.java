package com.danzstraight.vip;

import android.app.Service;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

public class OverlayService extends Service {

    public static final String ACTION_CROSSHAIR = "ACTION_CROSSHAIR";
    public static final String ACTION_HUD       = "ACTION_HUD";
    public static final String ACTION_STOP      = "ACTION_STOP";

    private WindowManager wm;
    private View crosshairView;
    private TextView hudView;
    private boolean runningHud = false;
    private long lastFrameNs = 0;
    private int frames = 0;

    private final Choreographer.FrameCallback frameCb = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!runningHud) return;
            frames++;
            if (lastFrameNs == 0) {
                lastFrameNs = frameTimeNanos;
            } else {
                long elapsed = frameTimeNanos - lastFrameNs;
                if (elapsed >= 1_000_000_000L) {
                    int fps = (int) ((frames * 1_000_000_000L) / elapsed);
                    if (hudView != null) {
                        Runtime rt = Runtime.getRuntime();
                        long ramMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                        hudView.setText("ROG HUD | " + fps + " FPS | MEM: " + ramMb + "MB");
                    }
                    frames = 0;
                    lastFrameNs = frameTimeNanos;
                }
            }
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) return START_STICKY;
        String act = intent.getAction();
        boolean en = intent.getBooleanExtra("enable", false);

        if (ACTION_CROSSHAIR.equals(act)) {
            toggleCrosshair(en);
        } else if (ACTION_HUD.equals(act)) {
            toggleHud(en);
        } else if (ACTION_STOP.equals(act)) {
            toggleCrosshair(false);
            toggleHud(false);
            stopSelf();
        }
        return START_STICKY;
    }

    private int getOverlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void toggleCrosshair(boolean enable) {
        if (enable && crosshairView == null) {
            crosshairView = new View(this) {
                private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                @Override
                protected void onDraw(Canvas c) {
                    super.onDraw(c);
                    int cx = getWidth() / 2;
                    int cy = getHeight() / 2;
                    p.setColor(Color.parseColor("#FF1824"));
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(3.2f);
                    c.drawCircle(cx, cy, 16f, p);
                    c.drawLine(cx - 26, cy, cx - 7, cy, p);
                    c.drawLine(cx + 7, cy, cx + 26, cy, p);
                    c.drawLine(cx, cy - 26, cx, cy - 7, p);
                    c.drawLine(cx, cy + 7, cx, cy + 26, p);
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(cx, cy, 2.8f, p);
                }
            };
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    84, 84, getOverlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.CENTER;
            wm.addView(crosshairView, lp);
        } else if (!enable && crosshairView != null) {
            wm.removeView(crosshairView);
            crosshairView = null;
        }
    }

    private void toggleHud(boolean enable) {
        if (enable && hudView == null) {
            hudView = new TextView(this);
            hudView.setTextColor(Color.parseColor("#00FF88"));
            hudView.setTextSize(11.5f);
            hudView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            hudView.setPadding(24, 12, 24, 12);
            hudView.setText("ROG HUD | -- FPS");

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#CC140508"));
            bg.setStroke(2, Color.parseColor("#FF1824"));
            bg.setCornerRadius(16f);
            hudView.setBackground(bg);

            final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    getOverlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = 30;
            lp.y = 80;

            hudView.setOnTouchListener(new View.OnTouchListener() {
                private int initX, initY;
                private float touchX, touchY;
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            initX = lp.x;
                            initY = lp.y;
                            touchX = event.getRawX();
                            touchY = event.getRawY();
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            lp.x = initX + (int) (event.getRawX() - touchX);
                            lp.y = initY + (int) (event.getRawY() - touchY);
                            wm.updateViewLayout(hudView, lp);
                            return true;
                    }
                    return false;
                }
            });

            wm.addView(hudView, lp);
            runningHud = true;
            Choreographer.getInstance().postFrameCallback(frameCb);
        } else if (!enable && hudView != null) {
            runningHud = false;
            Choreographer.getInstance().removeFrameCallback(frameCb);
            wm.removeView(hudView);
            hudView = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
