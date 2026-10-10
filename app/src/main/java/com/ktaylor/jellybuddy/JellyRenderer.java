package com.ktaylor.jellybuddy;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import java.util.Random;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Renders Jelly Buddy, a googly-eyed blob that sloshes around a toy tray.
 *
 * Tilt (gravity sensor) rolls him around, the gyroscope spins him and makes him
 * dizzy, shaking throws him about, and tapping makes him jump. Everything he does
 * is driven by small spring simulations so he jiggles, squashes and wobbles.
 */
final class JellyRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = "JellyBuddy";

    // Tray and body dimensions (world units).
    private static final float TRAY_W = 1.5f, TRAY_D = 2.6f, WALL_H = 0.45f, FRONT_WALL_H = 0.3f;
    private static final float R = 0.6f;
    private static final float LIMIT_X = TRAY_W - R, LIMIT_Z = TRAY_D - R;

    // Physics tuning.
    private static final float TILT_GAIN = 0.95f;
    private static final float RESTITUTION = 0.55f;
    private static final float JUMP_GRAVITY = 14f;
    private static final float SQUASH_K = 170f, SQUASH_C = 6.5f;
    private static final float STEP = 1f / 240f;

    private static final float[] BODY_COLOR = {0.36f, 0.86f, 0.56f};
    private static final float[] WALL_COLOR = {0.98f, 0.60f, 0.52f};
    private static final float[] FLOOR_COLOR = {1.00f, 0.88f, 0.74f};
    private static final float[] WHITE = {0.98f, 0.98f, 0.97f};
    private static final float[] PUPIL = {0.07f, 0.07f, 0.11f};
    private static final float[] CHEEK = {1.00f, 0.52f, 0.62f};
    private static final float[] MOUTH = {0.38f, 0.05f, 0.12f};
    private static final float[] TIP = {1.00f, 0.80f, 0.22f};

    private static final String VERTEX_SHADER =
            "uniform mat4 uMVP;\n" +
            "uniform mat4 uModel;\n" +
            "uniform mat4 uNormalMat;\n" +
            "uniform float uTime;\n" +
            "uniform float uWobble;\n" +
            "attribute vec3 aPos;\n" +
            "attribute vec3 aNormal;\n" +
            "varying vec3 vN;\n" +
            "varying vec3 vWorld;\n" +
            "varying vec3 vLocal;\n" +
            "void main() {\n" +
            "  vec3 p = aPos + aNormal * uWobble * sin(uTime * 17.0 + aPos.y * 6.0 + aPos.x * 4.0);\n" +
            "  vLocal = aPos;\n" +
            "  vWorld = (uModel * vec4(p, 1.0)).xyz;\n" +
            "  vN = (uNormalMat * vec4(aNormal, 0.0)).xyz;\n" +
            "  gl_Position = uMVP * vec4(p, 1.0);\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "varying vec3 vN;\n" +
            "varying vec3 vWorld;\n" +
            "varying vec3 vLocal;\n" +
            "uniform vec3 uColor;\n" +
            "uniform float uMode;\n" +
            "uniform float uAlpha;\n" +
            "uniform vec3 uLight;\n" +
            "uniform vec3 uEye;\n" +
            "void main() {\n" +
            "  if (uMode > 1.5) {\n" +
            "    float d = length(vLocal.xz) * 2.0;\n" +
            "    float a = (1.0 - smoothstep(0.15, 1.0, d)) * uAlpha;\n" +
            "    gl_FragColor = vec4(0.30, 0.14, 0.10, a);\n" +
            "    return;\n" +
            "  }\n" +
            "  vec3 n = normalize(vN);\n" +
            "  vec3 v = normalize(uEye - vWorld);\n" +
            "  if (dot(n, v) < 0.0) n = -n;\n" +
            "  vec3 base = uColor;\n" +
            "  float shiny = 0.7;\n" +
            "  float rimAmt = 0.35;\n" +
            "  if (uMode > 0.5) {\n" +
            "    vec2 c = floor(vWorld.xz * 2.0);\n" +
            "    base = mix(uColor, uColor * 0.9, mod(c.x + c.y, 2.0));\n" +
            "    shiny = 0.08;\n" +
            "    rimAmt = 0.0;\n" +
            "  }\n" +
            "  float diff = max(dot(n, uLight), 0.0);\n" +
            "  float toon = smoothstep(0.25, 0.35, diff) * 0.45 + diff * 0.45;\n" +
            "  vec3 h = normalize(uLight + v);\n" +
            "  float spec = pow(max(dot(n, h), 0.0), 48.0) * shiny;\n" +
            "  float rim = pow(1.0 - max(dot(n, v), 0.0), 3.0) * rimAmt;\n" +
            "  vec3 col = base * (0.38 + toon) + vec3(spec) + rim * vec3(1.0, 0.97, 0.9);\n" +
            "  gl_FragColor = vec4(col, 1.0);\n" +
            "}\n";

    private final Vibrator vibrator;
    private final Random rnd = new Random();

    // Sensor input, written from the UI thread.
    private volatile float inGx, inGy, inGz = 9.81f;
    private volatile float inWx, inWy, inWz;
    private volatile boolean jumpPending, shakePending;

    // GL handles.
    private int program;
    private int aPos, aNormal;
    private int uMVP, uModel, uNormalMat, uTime, uWobble, uColor, uMode, uAlpha, uLight, uEye;
    private Mesh sphere, quad;

    private final float[] proj = new float[16];
    private final float[] view = new float[16];
    private final float[] vp = new float[16];
    private final float[] mvp = new float[16];
    private final float[] inv = new float[16];
    private final float[] nrm = new float[16];
    private final float[] body = new float[16];
    private final float[] local = new float[16];
    private final float[] world = new float[16];
    private final float[] light = {-0.35f, 1.0f, 0.55f};
    private final float[] eye = new float[3];

    // Simulation state.
    private long lastNanos;
    private float time;
    private float gx, gy = 0f;                     // smoothed gravity (device frame)
    private float px, pz, vx, vz, py, vy;           // body position / velocity
    private final float[] def = new float[3];       // squash & stretch per axis
    private final float[] defV = new float[3];
    private float roll, rollV, pitch, pitchV, yaw, yawV;
    private float antRoll, antRollV, antPitch, antPitchV;
    private final float[] pupil = new float[4];     // left x,y  right x,y (unit disk)
    private final float[] pupilV = new float[4];
    private float jiggle, dizzy;
    private float blinkTimer = 2f, blinkLeft;
    private long lastHapticMs;

    JellyRenderer(Vibrator vibrator) {
        this.vibrator = vibrator;
        pupil[1] = pupil[3] = -0.6f;
        normalize(light);
    }

    // ---------------------------------------------------------------- input

    void setGravity(float x, float y, float z) { inGx = x; inGy = y; inGz = z; }

    void setGyro(float x, float y, float z) { inWx = x; inWy = y; inWz = z; }

    void requestJump() { jumpPending = true; }

    void requestShake() { shakePending = true; }

    // ---------------------------------------------------------------- GL lifecycle

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        GLES20.glClearColor(0.99f, 0.94f, 0.87f, 1f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aNormal = GLES20.glGetAttribLocation(program, "aNormal");
        uMVP = GLES20.glGetUniformLocation(program, "uMVP");
        uModel = GLES20.glGetUniformLocation(program, "uModel");
        uNormalMat = GLES20.glGetUniformLocation(program, "uNormalMat");
        uTime = GLES20.glGetUniformLocation(program, "uTime");
        uWobble = GLES20.glGetUniformLocation(program, "uWobble");
        uColor = GLES20.glGetUniformLocation(program, "uColor");
        uMode = GLES20.glGetUniformLocation(program, "uMode");
        uAlpha = GLES20.glGetUniformLocation(program, "uAlpha");
        uLight = GLES20.glGetUniformLocation(program, "uLight");
        uEye = GLES20.glGetUniformLocation(program, "uEye");

        sphere = Mesh.sphere(32, 40);
        quad = Mesh.quadXZ();
        lastNanos = 0;
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        float aspect = (float) width / Math.max(1, height);
        // Widen the view on narrow portrait screens so the whole tray fits.
        float fovy = aspect < 0.6f ? 50f : 42f;
        Matrix.perspectiveM(proj, 0, fovy, aspect, 0.5f, 40f);
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 1f / 60f : Math.min((now - lastNanos) / 1e9f, 0.05f);
        lastNanos = now;
        update(dt);
        render();
    }

    // ---------------------------------------------------------------- simulation

    private void update(float dt) {
        time += dt;
        float k = Math.min(1f, dt * 12f);
        gx += (inGx - gx) * k;
        gy += (inGy - gy) * k;
        float wx = inWx, wy = inWy, wz = inWz;

        if (jumpPending) {
            jumpPending = false;
            if (py < 0.02f) {
                vy = 5.2f;
                defV[1] += 2.8f;
                defV[0] -= 1.2f;
                defV[2] -= 1.2f;
                jiggle += 0.03f;
            }
        }
        if (shakePending) {
            shakePending = false;
            vx += (rnd.nextFloat() * 2f - 1f) * 4.5f;
            vz += (rnd.nextFloat() * 2f - 1f) * 4.5f;
            if (py < 0.02f) vy = 3.2f + rnd.nextFloat() * 2f;
            yawV += (rnd.nextFloat() * 2f - 1f) * 12f;
            jiggle += 0.06f;
            dizzy += 0.2f;
            haptic(3f);
        }

        // Spinning the phone (gyro around the screen normal) makes him dizzy.
        dizzy += (Math.abs(yawV) * 0.11f + Math.abs(wz) * 0.05f) * dt;
        dizzy -= dizzy * 0.33f * dt;
        dizzy = clamp(dizzy, 0f, 2f);

        int steps = Math.max(1, (int) Math.ceil(dt / STEP));
        float h = dt / steps;
        for (int i = 0; i < steps; i++) substep(h, wx, wy, wz);

        jiggle *= (float) Math.exp(-2.5f * dt);

        blinkTimer -= dt;
        if (blinkTimer <= 0f) {
            blinkLeft = 0.14f;
            blinkTimer = 1.6f + rnd.nextFloat() * 3.5f;
        }
        if (blinkLeft > 0f) blinkLeft -= dt;
    }

    private void substep(float h, float wx, float wy, float wz) {
        // Tilting the phone pours him "downhill": screen x -> world x, screen up -> world -z.
        float fx = -gx * TILT_GAIN;
        float fz = gy * TILT_GAIN;

        float pvx = vx, pvy = vy;
        vx += fx * h;
        vz += fz * h;
        float friction = (float) Math.exp(-0.7f * h);
        vx *= friction;
        vz *= friction;
        px += vx * h;
        pz += vz * h;

        if (px > LIMIT_X) { px = LIMIT_X; if (vx > 0) { hitSide(vx); vx = -vx * RESTITUTION; } }
        if (px < -LIMIT_X) { px = -LIMIT_X; if (vx < 0) { hitSide(vx); vx = -vx * RESTITUTION; } }
        if (pz > LIMIT_Z) { pz = LIMIT_Z; if (vz > 0) { hitEnd(vz); vz = -vz * RESTITUTION; } }
        if (pz < -LIMIT_Z) { pz = -LIMIT_Z; if (vz < 0) { hitEnd(vz); vz = -vz * RESTITUTION; } }

        if (py > 0f || vy > 0f) {
            vy -= JUMP_GRAVITY * h;
            py += vy * h;
            if (py <= 0f) {
                py = 0f;
                float impact = -vy;
                land(impact);
                vy = impact > 2.5f ? impact * 0.35f : 0f;
            }
        }

        // Squash & stretch springs, roughly volume preserving.
        for (int i = 0; i < 3; i++) {
            defV[i] += (-SQUASH_K * def[i] - SQUASH_C * defV[i]) * h;
            def[i] = clamp(def[i] + defV[i] * h, -0.45f, 0.6f);
        }

        // Body leans against the pull of gravity; flicks of the phone kick it.
        float rollTarget = clamp(fx * 0.05f, -0.5f, 0.5f);
        float pitchTarget = clamp(-fz * 0.05f, -0.5f, 0.5f);
        rollV += (-60f * (roll - rollTarget) - 5f * rollV - wy * 2.5f) * h;
        pitchV += (-60f * (pitch - pitchTarget) - 5f * pitchV + wx * 2.5f) * h;
        roll = clamp(roll + rollV * h, -0.7f, 0.7f);
        pitch = clamp(pitch + pitchV * h, -0.7f, 0.7f);

        // Spinning the phone twirls him; a bouncy spring brings him back to face you.
        yawV += (-22f * yaw - 1.0f * yawV - wz * 7f) * h;
        yaw += yawV * h;

        // Antenna lags behind the body with a floppier spring.
        antRollV += (-35f * (antRoll - rollTarget * 1.6f) - 2.2f * antRollV) * h;
        antPitchV += (-35f * (antPitch - pitchTarget * 1.6f) - 2.2f * antPitchV) * h;
        antRoll = clamp(antRoll + antRollV * h, -1.1f, 1.1f);
        antPitch = clamp(antPitch + antPitchV * h, -1.1f, 1.1f);

        // Googly pupils: they look downhill, slam on impacts, float during jumps.
        float bax = clamp((vx - pvx) / h, -60f, 60f);
        float bay = clamp((vy - pvy) / h, -60f, 60f);
        float dizzyAmt = clamp((dizzy - 0.4f) * 1.5f, 0f, 1f);
        for (int e = 0; e < 2; e++) {
            int ix = e * 2, iy = ix + 1;
            float ax = (fx * 0.9f - bax * 0.5f) * 0.9f;
            float ay = (-6f - bay * 0.5f) * 0.9f * (1f - dizzyAmt);
            if (dizzyAmt > 0f) {
                float ang = time * 9f * (e == 0 ? 1f : -1f);
                ax += ((float) Math.cos(ang) * 0.75f - pupil[ix]) * 70f * dizzyAmt;
                ay += ((float) Math.sin(ang) * 0.75f - pupil[iy]) * 70f * dizzyAmt;
            }
            float damp = (float) Math.exp(-1.6f * h);
            pupilV[ix] = (pupilV[ix] + ax * h) * damp;
            pupilV[iy] = (pupilV[iy] + ay * h) * damp;
            pupil[ix] += pupilV[ix] * h;
            pupil[iy] += pupilV[iy] * h;
            float len = (float) Math.hypot(pupil[ix], pupil[iy]);
            if (len > 1f) {
                float nx = pupil[ix] / len, ny = pupil[iy] / len;
                pupil[ix] = nx;
                pupil[iy] = ny;
                float vn = pupilV[ix] * nx + pupilV[iy] * ny;
                if (vn > 0f) {
                    pupilV[ix] -= 1.5f * vn * nx;
                    pupilV[iy] -= 1.5f * vn * ny;
                }
            }
        }
    }

    private void hitSide(float v) {
        float s = Math.abs(v);
        if (s < 0.3f) return;
        defV[0] -= s * 0.9f;
        defV[1] += s * 0.45f;
        defV[2] += s * 0.45f;
        rollV -= v * 1.6f;
        antRollV -= v * 3.5f;
        yawV += (rnd.nextFloat() - 0.5f) * s * 2f;
        jiggle += s * 0.02f;
        haptic(s);
    }

    private void hitEnd(float v) {
        float s = Math.abs(v);
        if (s < 0.3f) return;
        defV[2] -= s * 0.9f;
        defV[0] += s * 0.45f;
        defV[1] += s * 0.45f;
        pitchV += v * 1.6f;
        antPitchV += v * 3.5f;
        jiggle += s * 0.02f;
        haptic(s);
    }

    private void land(float impact) {
        if (impact < 0.5f) return;
        defV[1] -= impact * 0.6f;
        defV[0] += impact * 0.3f;
        defV[2] += impact * 0.3f;
        antRollV += (rnd.nextFloat() - 0.5f) * impact;
        jiggle += impact * 0.012f;
        haptic(impact * 0.6f);
    }

    private void haptic(float strength) {
        if (vibrator == null || strength < 1.2f) return;
        long nowMs = SystemClock.uptimeMillis();
        if (nowMs - lastHapticMs < 70) return;
        lastHapticMs = nowMs;
        int amplitude = (int) clamp(strength * 45f, 1f, 255f);
        int ms = (int) clamp(10f + strength * 6f, 10f, 45f);
        try {
            vibrator.vibrate(VibrationEffect.createOneShot(ms, amplitude));
        } catch (RuntimeException e) {
            Log.w(TAG, "Vibration failed", e);
        }
    }

    // ---------------------------------------------------------------- rendering

    private void render() {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        GLES20.glUseProgram(program);

        // A little parallax: the camera drifts as you tilt.
        eye[0] = -gx * 0.07f;
        eye[1] = 7.4f;
        eye[2] = 6.0f + gy * 0.05f;
        Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], 0f, 0f, 0.35f, 0f, 1f, 0f);
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0);

        GLES20.glUniform3f(uLight, light[0], light[1], light[2]);
        GLES20.glUniform3f(uEye, eye[0], eye[1], eye[2]);
        GLES20.glUniform1f(uTime, time);

        // Floor and walls.
        identity(world);
        Matrix.scaleM(world, 0, TRAY_W * 2f, 1f, TRAY_D * 2f);
        draw(quad, world, FLOOR_COLOR, 1f, 1f, 0f);

        wall(-TRAY_W, WALL_H, 0f, true);
        wall(TRAY_W, WALL_H, 0f, true);
        wall(0f, WALL_H, -TRAY_D, false);
        wall(0f, FRONT_WALL_H, TRAY_D, false);

        float sx = 1f + def[0], sy = 1f + def[1], sz = 1f + def[2];

        // Soft contact shadow.
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glDepthMask(false);
        float shadow = R * 2.5f * (1f + def[0] * 0.5f) / (1f + py * 0.3f);
        identity(world);
        Matrix.translateM(world, 0, px, 0.012f, pz);
        Matrix.scaleM(world, 0, shadow, 1f, shadow * (1f + def[2] * 0.3f) / (1f + def[0] * 0.3f));
        draw(quad, world, PUPIL, 2f, 0.42f / (1f + py * 0.8f), 0f);
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);

        // Body: pivot at the contact point so he leans and squashes from the base.
        identity(body);
        Matrix.translateM(body, 0, px, py, pz);
        Matrix.rotateM(body, 0, deg(roll), 0f, 0f, 1f);
        Matrix.rotateM(body, 0, deg(pitch), 1f, 0f, 0f);
        Matrix.rotateM(body, 0, deg(yaw), 0f, 1f, 0f);
        Matrix.translateM(body, 0, 0f, R * sy, 0f);
        Matrix.scaleM(body, 0, R * sx, R * sy, R * sz);
        float dizzyWobble = dizzy > 0.4f ? 0.012f : 0f;
        draw(sphere, body, BODY_COLOR, 0f, 1f, Math.min(jiggle, 0.06f) + dizzyWobble);

        // Face, in body-local unit-sphere coordinates.
        float blink = blinkLeft > 0f ? Math.max(0.08f, Math.abs(blinkLeft - 0.07f) / 0.07f) : 1f;
        for (int e = 0; e < 2; e++) {
            float side = e == 0 ? -1f : 1f;
            float ex = 0.34f * side, ey = 0.28f, ez = 0.88f;
            part(ex, ey, ez, 0.27f, 0.27f * blink, 0.12f, WHITE);
            part(ex + pupil[e * 2] * 0.14f, ey + pupil[e * 2 + 1] * 0.14f * blink, ez + 0.10f,
                    0.12f, 0.12f * blink, 0.05f, PUPIL);
            part(0.55f * side, -0.06f, 0.82f, 0.15f, 0.09f, 0.07f, CHEEK);
        }

        float speed = (float) Math.hypot(vx, vz) + Math.abs(vy) * 0.5f;
        float open = clamp(speed * 0.12f + jiggle * 4f + dizzy * 0.15f, 0f, 1f);
        part(0f, -0.16f, 0.96f, 0.16f + 0.04f * (1f - open), 0.035f + 0.15f * open, 0.07f, MOUTH);

        // Floppy antenna with a ball on top.
        identity(local);
        Matrix.translateM(local, 0, 0f, 0.95f, 0f);
        Matrix.rotateM(local, 0, deg(antRoll), 0f, 0f, 1f);
        Matrix.rotateM(local, 0, deg(antPitch), 1f, 0f, 0f);
        float[] stalk = local.clone();
        Matrix.translateM(stalk, 0, 0f, 0.32f, 0f);
        Matrix.scaleM(stalk, 0, 0.045f, 0.34f, 0.045f);
        Matrix.multiplyMM(world, 0, body, 0, stalk, 0);
        draw(sphere, world, BODY_COLOR, 0f, 1f, 0f);
        Matrix.translateM(local, 0, 0f, 0.7f, 0f);
        Matrix.scaleM(local, 0, 0.13f, 0.13f, 0.13f);
        Matrix.multiplyMM(world, 0, body, 0, local, 0);
        draw(sphere, world, TIP, 0f, 1f, 0f);
    }

    private void wall(float x, float height, float z, boolean side) {
        identity(world);
        Matrix.translateM(world, 0, x, height / 2f, z);
        if (side) {
            Matrix.rotateM(world, 0, 90f, 0f, 0f, 1f);
            Matrix.scaleM(world, 0, height, 1f, TRAY_D * 2f);
        } else {
            Matrix.rotateM(world, 0, 90f, 1f, 0f, 0f);
            Matrix.scaleM(world, 0, TRAY_W * 2f, 1f, height);
        }
        draw(quad, world, WALL_COLOR, 0f, 1f, 0f);
    }

    /** Draws a squashed sphere attached to the body at a body-local position. */
    private void part(float x, float y, float z, float rx, float ry, float rz, float[] color) {
        identity(local);
        Matrix.translateM(local, 0, x, y, z);
        Matrix.scaleM(local, 0, rx, ry, rz);
        Matrix.multiplyMM(world, 0, body, 0, local, 0);
        draw(sphere, world, color, 0f, 1f, 0f);
    }

    private void draw(Mesh mesh, float[] model, float[] color, float mode, float alpha, float wobble) {
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0);
        Matrix.invertM(inv, 0, model, 0);
        Matrix.transposeM(nrm, 0, inv, 0);
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
        GLES20.glUniformMatrix4fv(uNormalMat, 1, false, nrm, 0);
        GLES20.glUniform3f(uColor, color[0], color[1], color[2]);
        GLES20.glUniform1f(uMode, mode);
        GLES20.glUniform1f(uAlpha, alpha);
        GLES20.glUniform1f(uWobble, wobble);
        mesh.draw(aPos, aNormal);
    }

    // ---------------------------------------------------------------- helpers

    private static int buildProgram(String vs, String fs) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vs);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(p);
            GLES20.glDeleteProgram(p);
            throw new RuntimeException("Program link failed: " + log);
        }
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(s);
            GLES20.glDeleteShader(s);
            throw new RuntimeException("Shader compile failed: " + log);
        }
        return s;
    }

    private static void identity(float[] m) { Matrix.setIdentityM(m, 0); }

    private static float deg(float rad) { return (float) Math.toDegrees(rad); }

    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    private static void normalize(float[] v) {
        float l = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        v[0] /= l; v[1] /= l; v[2] /= l;
    }
}
