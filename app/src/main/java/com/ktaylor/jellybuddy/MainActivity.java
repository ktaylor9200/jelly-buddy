package com.ktaylor.jellybuddy;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.Vibrator;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

public class MainActivity extends Activity implements SensorEventListener {
    private static final float SHAKE_THRESHOLD = 9f;   // m/s^2 away from 1 g
    private static final long SHAKE_COOLDOWN_MS = 350;

    private GLSurfaceView glView;
    private JellyRenderer renderer;
    private SensorManager sensors;
    private Sensor gravity, accelerometer, gyroscope;
    private final float[] lowPass = {0f, 0f, 9.81f};
    private long lastShakeMs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && !vibrator.hasVibrator()) vibrator = null;
        renderer = new JellyRenderer(vibrator);

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setPreserveEGLContextOnPause(true);
        glView.setRenderer(renderer);

        FrameLayout root = new FrameLayout(this);
        root.addView(glView);
        root.addView(label(getString(R.string.title), 22, true),
                overlay(Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        root.addView(label(getString(R.string.hint), 14, false),
                overlay(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        setContentView(root);

        sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensors != null) {
            gravity = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY);
            accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            gyroscope = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        }
    }

    private TextView label(String text, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(Color.argb(190, 120, 60, 50));
        t.setGravity(Gravity.CENTER);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setLetterSpacing(0.15f);
        }
        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 28,
                getResources().getDisplayMetrics());
        t.setPadding(pad, pad, pad, pad);
        return t;
    }

    private static FrameLayout.LayoutParams overlay(int gravity) {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, gravity);
    }

    @Override
    protected void onResume() {
        super.onResume();
        glView.onResume();
        hideSystemBars();
        if (sensors != null) {
            if (gravity != null) sensors.registerListener(this, gravity, SensorManager.SENSOR_DELAY_GAME);
            if (accelerometer != null) sensors.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
            if (gyroscope != null) sensors.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    protected void onPause() {
        if (sensors != null) sensors.unregisterListener(this);
        glView.onPause();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            renderer.requestJump();
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float[] v = event.values;
        switch (event.sensor.getType()) {
            case Sensor.TYPE_GRAVITY:
                renderer.setGravity(v[0], v[1], v[2]);
                break;
            case Sensor.TYPE_ACCELEROMETER:
                if (gravity == null) {
                    for (int i = 0; i < 3; i++) lowPass[i] += (v[i] - lowPass[i]) * 0.1f;
                    renderer.setGravity(lowPass[0], lowPass[1], lowPass[2]);
                }
                float magnitude = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
                long now = SystemClock.uptimeMillis();
                if (Math.abs(magnitude - SensorManager.GRAVITY_EARTH) > SHAKE_THRESHOLD
                        && now - lastShakeMs > SHAKE_COOLDOWN_MS) {
                    lastShakeMs = now;
                    renderer.requestShake();
                }
                break;
            case Sensor.TYPE_GYROSCOPE:
                renderer.setGyro(v[0], v[1], v[2]);
                break;
            default:
                break;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not needed.
    }
}
