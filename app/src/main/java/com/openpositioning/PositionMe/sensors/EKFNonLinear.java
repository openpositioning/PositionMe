package com.openpositioning.PositionMe.sensors;

public class EKFNonLinear {
    // State = [X, Y, Vx, Vy]
    private double[] x;    // 4D
    private double[][] P;  // 4x4
    private double[][] Q;  // 4x4 (process noise)
    private double[][] R;  // 2x2 (current measurement noise), settable

    private double lat0Rad, lon0Deg, A;

    public EKFNonLinear(double[] initialState,
                        double[][] initialCovariance,
                        double[][] processNoise,
                        double[][] measurementNoise,
                        double lat0Rad,
                        double lon0Deg,
                        double A) {
        this.x = initialState.clone();
        this.P = copyMatrix(initialCovariance);
        this.Q = copyMatrix(processNoise);
        // We'll treat "measurementNoise" as the *default* R. We can later override it:
        this.R = copyMatrix(measurementNoise);

        this.lat0Rad = lat0Rad;
        this.lon0Deg = lon0Deg;
        this.A = A;
    }

    /**
     * If you want to dynamically set different measurement noise (e.g. WiFi vs GNSS),
     * call this before update().
     */
    public void setMeasurementNoise(double[][] newR) {
        this.R = copyMatrix(newR);
    }

    /**
     * Let PDR shift (X,Y) by dx, dy each cycle.
     */
    public void applyPdrDelta(double dx, double dy) {
        x[0] += dx;
        x[1] += dy;
    }

    /**
     * Predict step: constant-velocity or near-zero velocity if desired.
     */
    public void predict(double dt) {
        if (dt <= 0) dt = 0.001;
        // xPred
        double[] xPred = new double[4];
        xPred[0] = x[0] + x[2]*dt;
        xPred[1] = x[1] + x[3]*dt;
        xPred[2] = x[2];
        xPred[3] = x[3];

        double[][] F = {
                {1, 0, dt, 0},
                {0, 1, 0,  dt},
                {0, 0, 1,  0},
                {0, 0, 0,  1}
        };
        double[][] FPFt = multiplyMatrices(multiplyMatrices(F, P), transpose(F));
        double[][] PPred = addMatrices(FPFt, Q);

        x = xPred;
        P = PPred;
    }

    /**
     * h(x) => lat/lon from local XY
     */
    public double[] h(double[] state) {
        double lat0Deg = Math.toDegrees(lat0Rad);
        double X = state[0];
        double Y = state[1];

        double latDeg = lat0Deg + (X / A);
        double lonDeg = lon0Deg + (Y / (A * Math.cos(lat0Rad)));
        return new double[]{ latDeg, lonDeg };
    }

    /**
     * H = 2x4
     */
    public double[][] H() {
        double[][] H = new double[2][4];
        H[0][0] = 1.0/A;
        H[0][1] = 0;
        H[0][2] = 0;
        H[0][3] = 0;

        H[1][0] = 0;
        H[1][1] = 1.0/(A*Math.cos(lat0Rad));
        H[1][2] = 0;
        H[1][3] = 0;
        return H;
    }

    /**
     * Standard 2D update with current R
     */
    public void update(double[] z) {
        // z = [lat, lon]
        double[] zPred = h(x);
        double[] y = subtractVectors(z, zPred);

        double[][] H_mat = H();
        double[][] HP = multiplyMatrices(H_mat, P);
        double[][] HPHt = multiplyMatrices(HP, transpose(H_mat));
        double[][] S = addMatrices(HPHt, R);

        S[0][0] += 1e-6;
        S[1][1] += 1e-6;

        double[][] S_inv = invert2x2(S);
        double[][] PHt = multiplyMatrices(P, transpose(H_mat));
        double[][] K = multiplyMatrices(PHt, S_inv);

        double[] Ky = multiplyMatrixVector(K, y);
        x = addVectors(x, Ky);

        double[][] I = identityMatrix(4);
        double[][] KH = multiplyMatrices(K, H_mat);
        double[][] IminusKH = subtractMatrices(I, KH);
        P = multiplyMatrices(IminusKH, P);
    }

    public double[] getState() {
        return x.clone();
    }
    public double[] getLatLon() {
        return h(x);
    }


    // ----------------------------------------------------
    //   Matrix / Vector utilities
    // ----------------------------------------------------
    private double[][] addMatrices(double[][] A, double[][] B) {
        int rows = A.length;
        int cols = A[0].length;
        double[][] out = new double[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                out[r][c] = A[r][c] + B[r][c];
            }
        }
        return out;
    }

    private double[][] subtractMatrices(double[][] A, double[][] B) {
        int rows = A.length;
        int cols = A[0].length;
        double[][] out = new double[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                out[r][c] = A[r][c] - B[r][c];
            }
        }
        return out;
    }

    private double[] subtractVectors(double[] a, double[] b) {
        double[] out = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = a[i] - b[i];
        }
        return out;
    }

    private double[] addVectors(double[] a, double[] b) {
        double[] out = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = a[i] + b[i];
        }
        return out;
    }

    private double[][] multiplyMatrices(double[][] A, double[][] B) {
        int rows = A.length;
        int cols = B[0].length;
        int common = A[0].length;
        double[][] result = new double[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                double sum = 0;
                for (int k = 0; k < common; k++) {
                    sum += A[i][k] * B[k][j];
                }
                result[i][j] = sum;
            }
        }
        return result;
    }

    private double[][] transpose(double[][] M) {
        int rows = M.length;
        int cols = M[0].length;
        double[][] T = new double[cols][rows];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                T[j][i] = M[i][j];
            }
        }
        return T;
    }

    private double[][] identityMatrix(int n) {
        double[][] I = new double[n][n];
        for (int i = 0; i < n; i++) {
            I[i][i] = 1;
        }
        return I;
    }

    private double[][] copyMatrix(double[][] src) {
        double[][] dst = new double[src.length][src[0].length];
        for (int i = 0; i < src.length; i++) {
            System.arraycopy(src[i], 0, dst[i], 0, src[i].length);
        }
        return dst;
    }

    private double[] multiplyMatrixVector(double[][] M, double[] v) {
        int rows = M.length;
        int cols = M[0].length;
        double[] result = new double[rows];
        for (int r = 0; r < rows; r++) {
            double sum = 0;
            for (int c = 0; c < cols; c++) {
                sum += M[r][c] * v[c];
            }
            result[r] = sum;
        }
        return result;
    }

    private double[][] invert2x2(double[][] M) {
        if (M.length != 2 || M[0].length != 2)
            throw new RuntimeException("Only 2x2 invert supported here.");
        double det = M[0][0]*M[1][1] - M[0][1]*M[1][0];
        if (Math.abs(det) < 1e-12) {
            throw new RuntimeException("Singular matrix");
        }
        double invDet = 1.0 / det;
        double[][] inv = new double[2][2];
        inv[0][0] =  M[1][1]*invDet;
        inv[0][1] = -M[0][1]*invDet;
        inv[1][0] = -M[1][0]*invDet;
        inv[1][1] =  M[0][0]*invDet;
        return inv;
    }
}
