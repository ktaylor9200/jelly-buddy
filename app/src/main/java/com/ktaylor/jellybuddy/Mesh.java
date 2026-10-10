package com.ktaylor.jellybuddy;

import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/** Interleaved position + normal mesh drawn with indexed triangles. */
final class Mesh {
    private static final int STRIDE = 6 * 4;

    private final FloatBuffer vertices;
    private final ShortBuffer indices;
    private final int indexCount;

    private Mesh(float[] v, short[] i) {
        vertices = ByteBuffer.allocateDirect(v.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertices.put(v).position(0);
        indices = ByteBuffer.allocateDirect(i.length * 2)
                .order(ByteOrder.nativeOrder()).asShortBuffer();
        indices.put(i).position(0);
        indexCount = i.length;
    }

    /** Unit sphere centred on the origin. */
    static Mesh sphere(int stacks, int slices) {
        float[] v = new float[(stacks + 1) * (slices + 1) * 6];
        int p = 0;
        for (int st = 0; st <= stacks; st++) {
            double phi = Math.PI * st / stacks;
            float y = (float) Math.cos(phi);
            float r = (float) Math.sin(phi);
            for (int sl = 0; sl <= slices; sl++) {
                double theta = 2.0 * Math.PI * sl / slices;
                float x = r * (float) Math.cos(theta);
                float z = r * (float) Math.sin(theta);
                v[p++] = x; v[p++] = y; v[p++] = z;
                v[p++] = x; v[p++] = y; v[p++] = z;
            }
        }
        short[] idx = new short[stacks * slices * 6];
        int q = 0;
        for (int st = 0; st < stacks; st++) {
            for (int sl = 0; sl < slices; sl++) {
                int a = st * (slices + 1) + sl;
                int b = a + slices + 1;
                idx[q++] = (short) a; idx[q++] = (short) b; idx[q++] = (short) (a + 1);
                idx[q++] = (short) (a + 1); idx[q++] = (short) b; idx[q++] = (short) (b + 1);
            }
        }
        return new Mesh(v, idx);
    }

    /** 1x1 quad lying in the XZ plane, facing +Y. */
    static Mesh quadXZ() {
        float[] v = {
                -0.5f, 0f, -0.5f, 0f, 1f, 0f,
                 0.5f, 0f, -0.5f, 0f, 1f, 0f,
                 0.5f, 0f,  0.5f, 0f, 1f, 0f,
                -0.5f, 0f,  0.5f, 0f, 1f, 0f,
        };
        short[] i = {0, 1, 2, 0, 2, 3};
        return new Mesh(v, i);
    }

    void draw(int aPos, int aNormal) {
        vertices.position(0);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, STRIDE, vertices);
        GLES20.glEnableVertexAttribArray(aPos);
        vertices.position(3);
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, STRIDE, vertices);
        GLES20.glEnableVertexAttribArray(aNormal);
        indices.position(0);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indices);
    }
}
