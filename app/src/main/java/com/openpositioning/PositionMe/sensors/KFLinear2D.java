package com.openpositioning.PositionMe.sensors;

public class KFLinear2D {
    // State = [ x, y, vx, vy ] in meters
    private double[] x;      // length=4
    private double[][] P;    // 4x4
    private double[][] Q;    // 4x4
    private double[][] R;    // 2x2

    public KFLinear2D(double[] initialState,
                      double[][] initialCov,
                      double[][] processNoise,
                      double[][] measurementNoise) {
        this.x = initialState.clone();
        this.P = copyMatrix(initialCov);
        this.Q = copyMatrix(processNoise);
        this.R = copyMatrix(measurementNoise);
    }

    public void setMeasurementNoise(double[][] newR) {
        this.R = copyMatrix(newR);
    }

    /**
     * If you want to incorporate direct PDR displacement each cycle
     * before or after predict().
     */
    public void applyPdrDelta(double dx, double dy) {
        x[0] += dx;   // add displacement to X
        x[1] += dy;   // add displacement to Y
    }

    /**
     * Predict step for constant velocity.
     * x = x + vx*dt,  y = y + vy*dt
     */
    public void predict(double dt) {
        if (dt <= 0) dt = 0.001;
        // F = [[1,0,dt,0],[0,1,0,dt],[0,0,1,0],[0,0,0,1]]
        double[][] F = {
                {1, 0, dt, 0},
                {0, 1, 0,  dt},
                {0, 0, 1,  0},
                {0, 0, 0,  1}
        };
        double[] xPred = multiplyMatrixVector(F, x);
        double[][] FP = multiplyMatrices(F, P);
        double[][] FPFt = multiplyMatrices(FP, transpose(F));
        double[][] PPred = addMatrices(FPFt, Q);

        x = xPred;
        P = PPred;
    }

    /**
     * Update step with measurement z = [xm, ym] in meters
     * (the identity H picks out X,Y from [X,Y,vx,vy]).
     */
    public void update(double[] z) {
        // H = [[1,0,0,0],[0,1,0,0]]
        double[][] H = {
                {1,0,0,0},
                {0,1,0,0}
        };
        // zPred = H*x
        double[] zPred = multiplyMatrixVector(H, x);
        // y = z - zPred
        double[] y = subtractVectors(z, zPred);

        // S = H P H^T + R
        double[][] HP = multiplyMatrices(H, P);
        double[][] HPHt = multiplyMatrices(HP, transpose(H));
        double[][] S = addMatrices(HPHt, R);

        double[][] S_inv = invert2x2(S);
        // K = P H^T S^-1
        double[][] PHt = multiplyMatrices(P, transpose(H));
        double[][] K = multiplyMatrices(PHt, S_inv);

        // x = x + K*y
        double[] Ky = multiplyMatrixVector(K, y);
        x = addVectors(x, Ky);

        // P = (I - K*H) P
        double[][] I = identityMatrix(4);
        double[][] KH = multiplyMatrices(K, H);
        double[][] IminusKH = subtractMatrices(I, KH);
        P = multiplyMatrices(IminusKH, P);
    }

    public double[] getState() {
        return x.clone();
    }

    /**
     * Return [X, Y] for convenience
     */
    public double[] getXY() {
        return new double[]{ x[0], x[1] };
    }

    // ---------------------------------------
    //     Matrix / Vector Utilities
    // ---------------------------------------

    private double[][] addMatrices(double[][] A, double[][] B) {
        int rows = A.length;
        int cols = A[0].length;
        double[][] out = new double[rows][cols];
        for (int r=0; r<rows; r++){
            for (int c=0; c<cols; c++){
                out[r][c] = A[r][c] + B[r][c];
            }
        }
        return out;
    }

    private double[] addVectors(double[] a, double[] b) {
        double[] out = new double[a.length];
        for (int i=0; i<a.length; i++){
            out[i] = a[i] + b[i];
        }
        return out;
    }

    private double[] subtractVectors(double[] a, double[] b) {
        double[] out = new double[a.length];
        for (int i=0; i<a.length; i++){
            out[i] = a[i] - b[i];
        }
        return out;
    }

    private double[][] subtractMatrices(double[][] A, double[][] B) {
        int rows = A.length;
        int cols = A[0].length;
        double[][] out = new double[rows][cols];
        for (int r=0; r<rows; r++){
            for (int c=0; c<cols; c++){
                out[r][c] = A[r][c] - B[r][c];
            }
        }
        return out;
    }

    private double[][] multiplyMatrices(double[][] A, double[][] B) {
        int rows = A.length;
        int cols = B[0].length;
        int common = A[0].length;
        double[][] out = new double[rows][cols];
        for (int i=0; i<rows; i++){
            for (int j=0; j<cols; j++){
                double sum=0;
                for (int k=0; k<common; k++){
                    sum += A[i][k]*B[k][j];
                }
                out[i][j] = sum;
            }
        }
        return out;
    }

    private double[] multiplyMatrixVector(double[][] M, double[] v){
        int rows = M.length;
        int cols = M[0].length;
        double[] out = new double[rows];
        for (int r=0; r<rows; r++){
            double sum=0;
            for (int c=0; c<cols; c++){
                sum += M[r][c]*v[c];
            }
            out[r] = sum;
        }
        return out;
    }

    private double[][] transpose(double[][] A){
        int rows = A.length;
        int cols = A[0].length;
        double[][] T = new double[cols][rows];
        for (int r=0; r<rows; r++){
            for (int c=0; c<cols; c++){
                T[c][r] = A[r][c];
            }
        }
        return T;
    }

    private double[][] identityMatrix(int n){
        double[][] I = new double[n][n];
        for (int i=0; i<n; i++){
            I[i][i] = 1.0;
        }
        return I;
    }

    private double[][] copyMatrix(double[][] src){
        double[][] dst = new double[src.length][src[0].length];
        for (int i=0; i<src.length; i++){
            System.arraycopy(src[i], 0, dst[i], 0, src[i].length);
        }
        return dst;
    }

    private double[][] invert2x2(double[][] M){
        if (M.length!=2 || M[0].length!=2){
            throw new IllegalArgumentException("invert2x2 only for 2x2 matrix.");
        }
        double det = M[0][0]*M[1][1] - M[0][1]*M[1][0];
        if (Math.abs(det)<1e-12){
            throw new RuntimeException("Matrix is singular or near singular.");
        }
        double invDet = 1.0/det;
        double[][] out = new double[2][2];
        out[0][0] =  M[1][1]*invDet;
        out[0][1] = -M[0][1]*invDet;
        out[1][0] = -M[1][0]*invDet;
        out[1][1] =  M[0][0]*invDet;
        return out;
    }
}
