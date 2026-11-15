package android.opengl;

/**
 * Stand-in for Android's {@code android.opengl.Matrix}: 4x4 float matrices stored column-major
 * (OpenGL order) in arrays at an offset. FIRST's {@code OpenGLMatrix} (used for AprilTag and
 * camera poses) does its math through these, so they follow Android's definitions exactly.
 */
public class Matrix {

    private static final float[] TEMP = new float[32];

    /** result = lhs x rhs. */
    public static void multiplyMM(float[] result, int resultOffset, float[] lhs, int lhsOffset, float[] rhs, int rhsOffset) {
        float[] r = new float[16];
        for (int i = 0; i < 4; i++) {          // row
            for (int j = 0; j < 4; j++) {      // column
                float sum = 0;
                for (int k = 0; k < 4; k++) sum += lhs[lhsOffset + i + 4 * k] * rhs[rhsOffset + k + 4 * j];
                r[i + 4 * j] = sum;
            }
        }
        System.arraycopy(r, 0, result, resultOffset, 16);
    }

    /** resultVec = lhsMat x rhsVec (4-element vectors). */
    public static void multiplyMV(float[] resultVec, int resultVecOffset, float[] lhsMat, int lhsMatOffset, float[] rhsVec, int rhsVecOffset) {
        float[] r = new float[4];
        for (int i = 0; i < 4; i++) {
            float sum = 0;
            for (int k = 0; k < 4; k++) sum += lhsMat[lhsMatOffset + i + 4 * k] * rhsVec[rhsVecOffset + k];
            r[i] = sum;
        }
        System.arraycopy(r, 0, resultVec, resultVecOffset, 4);
    }

    public static void transposeM(float[] mTrans, int mTransOffset, float[] m, int mOffset) {
        float[] r = new float[16];
        for (int i = 0; i < 4; i++) {
            int mBase = i * 4 + mOffset;
            r[i] = m[mBase];
            r[i + 4] = m[mBase + 1];
            r[i + 8] = m[mBase + 2];
            r[i + 12] = m[mBase + 3];
        }
        System.arraycopy(r, 0, mTrans, mTransOffset, 16);
    }

    /** Inverts a 4x4 matrix with Cramer's rule; returns false (leaving mInv alone) if it is singular. */
    public static boolean invertM(float[] mInv, int mInvOffset, float[] m, int mOffset) {
        // transpose
        final float src0 = m[mOffset], src4 = m[mOffset + 1], src8 = m[mOffset + 2], src12 = m[mOffset + 3];
        final float src1 = m[mOffset + 4], src5 = m[mOffset + 5], src9 = m[mOffset + 6], src13 = m[mOffset + 7];
        final float src2 = m[mOffset + 8], src6 = m[mOffset + 9], src10 = m[mOffset + 10], src14 = m[mOffset + 11];
        final float src3 = m[mOffset + 12], src7 = m[mOffset + 13], src11 = m[mOffset + 14], src15 = m[mOffset + 15];

        // pairs for the first 8 cofactors
        final float a0 = src10 * src15, a1 = src11 * src14, a2 = src9 * src15, a3 = src11 * src13;
        final float a4 = src9 * src14, a5 = src10 * src13, a6 = src8 * src15, a7 = src11 * src12;
        final float a8 = src8 * src14, a9 = src10 * src12, a10 = src8 * src13, a11 = src9 * src12;

        final float dst0 = (a0 * src5 + a3 * src6 + a4 * src7) - (a1 * src5 + a2 * src6 + a5 * src7);
        final float dst1 = (a1 * src4 + a6 * src6 + a9 * src7) - (a0 * src4 + a7 * src6 + a8 * src7);
        final float dst2 = (a2 * src4 + a7 * src5 + a10 * src7) - (a3 * src4 + a6 * src5 + a11 * src7);
        final float dst3 = (a5 * src4 + a8 * src5 + a11 * src6) - (a4 * src4 + a9 * src5 + a10 * src6);
        final float dst4 = (a1 * src1 + a2 * src2 + a5 * src3) - (a0 * src1 + a3 * src2 + a4 * src3);
        final float dst5 = (a0 * src0 + a7 * src2 + a8 * src3) - (a1 * src0 + a6 * src2 + a9 * src3);
        final float dst6 = (a3 * src0 + a6 * src1 + a11 * src3) - (a2 * src0 + a7 * src1 + a10 * src3);
        final float dst7 = (a4 * src0 + a9 * src1 + a10 * src2) - (a5 * src0 + a8 * src1 + a11 * src2);

        // pairs for the second 8 cofactors
        final float b0 = src2 * src7, b1 = src3 * src6, b2 = src1 * src7, b3 = src3 * src5;
        final float b4 = src1 * src6, b5 = src2 * src5, b6 = src0 * src7, b7 = src3 * src4;
        final float b8 = src0 * src6, b9 = src2 * src4, b10 = src0 * src5, b11 = src1 * src4;

        final float dst8 = (b0 * src13 + b3 * src14 + b4 * src15) - (b1 * src13 + b2 * src14 + b5 * src15);
        final float dst9 = (b1 * src12 + b6 * src14 + b9 * src15) - (b0 * src12 + b7 * src14 + b8 * src15);
        final float dst10 = (b2 * src12 + b7 * src13 + b10 * src15) - (b3 * src12 + b6 * src13 + b11 * src15);
        final float dst11 = (b5 * src12 + b8 * src13 + b11 * src14) - (b4 * src12 + b9 * src13 + b10 * src14);
        final float dst12 = (b2 * src10 + b5 * src11 + b1 * src9) - (b4 * src11 + b0 * src9 + b3 * src10);
        final float dst13 = (b8 * src11 + b0 * src8 + b7 * src10) - (b6 * src10 + b9 * src11 + b1 * src8);
        final float dst14 = (b6 * src9 + b11 * src11 + b3 * src8) - (b10 * src11 + b2 * src8 + b7 * src9);
        final float dst15 = (b10 * src10 + b4 * src8 + b9 * src9) - (b8 * src9 + b11 * src10 + b5 * src8);

        final float det = src0 * dst0 + src1 * dst1 + src2 * dst2 + src3 * dst3;
        if (det == 0.0f) return false;
        final float invdet = 1.0f / det;
        final float[] d = {dst0, dst1, dst2, dst3, dst4, dst5, dst6, dst7, dst8, dst9, dst10, dst11, dst12, dst13, dst14, dst15};
        for (int i = 0; i < 16; i++) mInv[mInvOffset + i] = d[i] * invdet;
        return true;
    }

    public static void setIdentityM(float[] sm, int smOffset) {
        for (int i = 0; i < 16; i++) sm[smOffset + i] = 0;
        for (int i = 0; i < 16; i += 5) sm[smOffset + i] = 1.0f;
    }

    /** m = m x scale(x, y, z). */
    public static void scaleM(float[] m, int mOffset, float x, float y, float z) {
        for (int i = 0; i < 4; i++) {
            int mi = mOffset + i;
            m[mi] *= x;
            m[4 + mi] *= y;
            m[8 + mi] *= z;
        }
    }

    /** sm = m x scale(x, y, z). */
    public static void scaleM(float[] sm, int smOffset, float[] m, int mOffset, float x, float y, float z) {
        for (int i = 0; i < 4; i++) {
            int smi = smOffset + i;
            int mi = mOffset + i;
            sm[smi] = m[mi] * x;
            sm[4 + smi] = m[4 + mi] * y;
            sm[8 + smi] = m[8 + mi] * z;
            sm[12 + smi] = m[12 + mi];
        }
    }

    /** m = m x translate(x, y, z). */
    public static void translateM(float[] m, int mOffset, float x, float y, float z) {
        for (int i = 0; i < 4; i++) {
            int mi = mOffset + i;
            m[12 + mi] += m[mi] * x + m[4 + mi] * y + m[8 + mi] * z;
        }
    }

    /** tm = m x translate(x, y, z). */
    public static void translateM(float[] tm, int tmOffset, float[] m, int mOffset, float x, float y, float z) {
        for (int i = 0; i < 12; i++) tm[tmOffset + i] = m[mOffset + i];
        for (int i = 0; i < 4; i++) {
            int tmi = tmOffset + i;
            int mi = mOffset + i;
            tm[12 + tmi] = m[mi] * x + m[4 + mi] * y + m[8 + mi] * z + m[12 + mi];
        }
    }

    /** rm = rotation of {@code a} degrees about the axis (x, y, z). */
    public static void setRotateM(float[] rm, int rmOffset, float a, float x, float y, float z) {
        rm[rmOffset + 3] = 0;
        rm[rmOffset + 7] = 0;
        rm[rmOffset + 11] = 0;
        rm[rmOffset + 12] = 0;
        rm[rmOffset + 13] = 0;
        rm[rmOffset + 14] = 0;
        rm[rmOffset + 15] = 1;
        a *= (float) (Math.PI / 180.0f);
        float s = (float) Math.sin(a);
        float c = (float) Math.cos(a);
        if (1.0f == x && 0.0f == y && 0.0f == z) {
            rm[rmOffset + 5] = c;
            rm[rmOffset + 10] = c;
            rm[rmOffset + 6] = s;
            rm[rmOffset + 9] = -s;
            rm[rmOffset + 1] = 0;
            rm[rmOffset + 2] = 0;
            rm[rmOffset + 4] = 0;
            rm[rmOffset + 8] = 0;
            rm[rmOffset] = 1;
        } else if (0.0f == x && 1.0f == y && 0.0f == z) {
            rm[rmOffset] = c;
            rm[rmOffset + 10] = c;
            rm[rmOffset + 8] = s;
            rm[rmOffset + 2] = -s;
            rm[rmOffset + 1] = 0;
            rm[rmOffset + 4] = 0;
            rm[rmOffset + 6] = 0;
            rm[rmOffset + 9] = 0;
            rm[rmOffset + 5] = 1;
        } else if (0.0f == x && 0.0f == y && 1.0f == z) {
            rm[rmOffset] = c;
            rm[rmOffset + 5] = c;
            rm[rmOffset + 1] = s;
            rm[rmOffset + 4] = -s;
            rm[rmOffset + 2] = 0;
            rm[rmOffset + 6] = 0;
            rm[rmOffset + 8] = 0;
            rm[rmOffset + 9] = 0;
            rm[rmOffset + 10] = 1;
        } else {
            float len = length(x, y, z);
            if (1.0f != len) {
                float recipLen = 1.0f / len;
                x *= recipLen;
                y *= recipLen;
                z *= recipLen;
            }
            float nc = 1.0f - c;
            float xy = x * y, yz = y * z, zx = z * x;
            float xs = x * s, ys = y * s, zs = z * s;
            rm[rmOffset] = x * x * nc + c;
            rm[rmOffset + 4] = xy * nc - zs;
            rm[rmOffset + 8] = zx * nc + ys;
            rm[rmOffset + 1] = xy * nc + zs;
            rm[rmOffset + 5] = y * y * nc + c;
            rm[rmOffset + 9] = yz * nc - xs;
            rm[rmOffset + 2] = zx * nc - ys;
            rm[rmOffset + 6] = yz * nc + xs;
            rm[rmOffset + 10] = z * z * nc + c;
        }
    }

    /** m = m x rotate(a, x, y, z). */
    public static void rotateM(float[] m, int mOffset, float a, float x, float y, float z) {
        synchronized (TEMP) {
            setRotateM(TEMP, 0, a, x, y, z);
            multiplyMM(TEMP, 16, m, mOffset, TEMP, 0);
            System.arraycopy(TEMP, 16, m, mOffset, 16);
        }
    }

    /** rm = m x rotate(a, x, y, z). */
    public static void rotateM(float[] rm, int rmOffset, float[] m, int mOffset, float a, float x, float y, float z) {
        synchronized (TEMP) {
            setRotateM(TEMP, 0, a, x, y, z);
            multiplyMM(rm, rmOffset, m, mOffset, TEMP, 0);
        }
    }

    public static float length(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }
}
