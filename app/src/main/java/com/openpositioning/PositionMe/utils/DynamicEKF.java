package com.openpositioning.PositionMe.utils;

import org.ejml.simple.SimpleMatrix;

/**
 * Extended Kalman Filter (EKF) with dynamic observation covariance for indoor positioning.
 *
 * <p>The filter maintains a 3-DOF state vector {@code [x, y, theta]}, where {@code x} and
 * {@code y} are East and North positions in metres (relative to the local ENU origin) and
 * {@code theta} is the heading in radians (0 = north, clockwise positive per Android convention).
 *
 * <p>Two stages are exposed:
 * <ol>
 *   <li><b>Predict</b> ({@link #predict}): propagates the state using a step-and-turn motion
 *       model driven by PDR step length and heading change.</li>
 *   <li><b>Update</b> ({@link #updateWithDynamicR}): fuses an absolute 2-D position observation
 *       (from WiFi or GNSS) whose measurement noise covariance R is scaled by a confidence
 *       score — higher score → smaller R → stronger correction.</li>
 * </ol>
 *
 * <p>The Joseph-form covariance update {@code P = (I-KH)P(I-KH)^T + KRK^T} is used for
 * numerical stability.
 *
 * @author PositionMe team
 */
public class DynamicEKF {

    /** Minimum value used as a floor for dynamic observation variance to prevent division by zero. */
    private static final double EPS = 1e-9;

    /**
     * Process noise covariance matrix Q (3×3 diagonal).
     * Diagonal values represent per-step uncertainty growth in x (0.05 m²), y (0.05 m²),
     * and heading theta (0.002 rad²). Larger values cause the EKF to trust observations
     * more than its own motion model.
     */
    private final SimpleMatrix q;

    /**
     * Exponent scaling factor governing how sharply observation variance grows as the
     * confidence score falls below 1.0.
     * For example, alpha=3.0 doubles the variance roughly when score drops to ~0.77.
     */
    private final double alpha;

    /**
     * Baseline observation variance (m²) at confidence score = 1.0 (perfect observation).
     * At lower scores the actual variance is {@code baseVariance * exp(alpha * (1 - score))}.
     */
    private final double baseVariance;

    private SimpleMatrix state;      // [x, y, theta]^T  — East (m), North (m), heading (rad)
    private SimpleMatrix covariance; // 3×3 state error covariance matrix P

    /**
     * Convenience constructor using default tuning parameters (alpha=3.0, baseVariance=2.0).
     *
     * @param initX     initial East position in metres (local ENU frame).
     * @param initY     initial North position in metres (local ENU frame).
     * @param initTheta initial heading in radians (0 = north, clockwise positive).
     */
    public DynamicEKF(double initX, double initY, double initTheta) {
        this(initX, initY, initTheta, 3.0, 2.0);
    }

    /**
     * Full constructor with explicit tuning parameters.
     *
     * @param initX         initial East position in metres.
     * @param initY         initial North position in metres.
     * @param initTheta     initial heading in radians.
     * @param alpha         observation variance scaling exponent (see {@link #alpha}).
     * @param baseVariance  baseline observation variance in m² (see {@link #baseVariance}).
     */
    public DynamicEKF(double initX, double initY, double initTheta, double alpha, double baseVariance) {
        this.alpha = alpha;
        this.baseVariance = baseVariance;
        this.state = new SimpleMatrix(3, 1, true, new double[]{initX, initY, initTheta});
        this.covariance = SimpleMatrix.identity(3);
        this.q = new SimpleMatrix(3, 3, true, new double[]{
            0.05, 0, 0,
            0, 0.05, 0,
            0, 0, 0.002
        });
    }

    /**
     * EKF predict step: propagates the state using a unicycle motion model.
     *
     * <p>State transition:
     * <pre>
     *   x'     = x + stepLen * sin(theta + deltaTheta)
     *   y'     = y + stepLen * cos(theta + deltaTheta)
     *   theta' = wrap(theta + deltaTheta)
     * </pre>
     * The covariance is propagated as {@code P = Fx * P * Fx^T + Q}, where Fx is the
     * Jacobian of the motion model with respect to the state.
     *
     * @param stepLen    distance travelled in this step, in metres.
     * @param deltaTheta change in heading since the previous step, in radians.
     */
    public synchronized void predict(double stepLen, double deltaTheta) {
        double x = state.get(0);
        double y = state.get(1);
        double theta = state.get(2);

        double heading = theta + deltaTheta;
        // Android azimuth convention (0=north, clockwise positive):
        // east = step * sin(heading), north = step * cos(heading)
        double newX = x + stepLen * Math.sin(heading);
        double newY = y + stepLen * Math.cos(heading);
        double newTheta = wrapAngle(heading);

        state.set(0, newX);
        state.set(1, newY);
        state.set(2, newTheta);

        // Partial derivatives of the motion model with respect to theta (for Jacobian Fx)
        double dxdTheta = stepLen * Math.cos(heading);
        double dydTheta = -stepLen * Math.sin(heading);

        // Jacobian Fx of the motion model: linearises state transition around current estimate
        SimpleMatrix fx = new SimpleMatrix(3, 3, true, new double[]{
                1, 0, dxdTheta,
                0, 1, dydTheta,
                0, 0, 1
        });

        // Covariance prediction: P = Fx * P * Fx^T + Q
        covariance = fx.mult(covariance).mult(fx.transpose()).plus(q);
    }

    /**
     * EKF update step with dynamic observation noise covariance R.
     *
     * <p>The observation noise variance is computed as:
     * <pre>
     *   dynamicVar = baseVariance * exp(alpha * (1 - score))
     * </pre>
     * A high {@code score} (close to 1.0) yields a small R, causing the filter to trust
     * the observation strongly. A low score (e.g. from a weak WiFi fingerprint match)
     * inflates R and reduces the correction magnitude.
     *
     * <p>The Joseph-form update is used for numerical stability:
     * <pre>
     *   K = P * H^T * (H * P * H^T + R)^{-1}
     *   P = (I - K*H) * P * (I - K*H)^T + K * R * K^T
     * </pre>
     *
     * @param obsX   observed East position in metres (local ENU frame).
     * @param obsY   observed North position in metres (local ENU frame).
     * @param score  confidence score in [0, 1]; higher = more reliable observation.
     */
    public synchronized void updateWithDynamicR(double obsX, double obsY, double score) {
        double safeScore = Math.max(0.0, Math.min(1.0, score));
        double penalty = Math.exp(alpha * (1.0 - safeScore));
        double dynamicVar = Math.max(EPS, baseVariance * penalty);

        // Observation matrix H: maps 3-D state [x, y, theta] to 2-D observation [x, y]
        SimpleMatrix h = new SimpleMatrix(2, 3, true, new double[]{
                1, 0, 0,
                0, 1, 0
        });

        // Observation noise covariance matrix R (2×2 diagonal, isotropic)
        SimpleMatrix r = new SimpleMatrix(2, 2, true, new double[]{
                dynamicVar, 0,
                0, dynamicVar
        });

        // Observation vector z and innovation (z - H*x)
        SimpleMatrix z = new SimpleMatrix(2, 1, true, new double[]{obsX, obsY});
        SimpleMatrix innovation = z.minus(h.mult(state));

        // Innovation covariance S = H * P * H^T + R
        SimpleMatrix s = h.mult(covariance).mult(h.transpose()).plus(r);
        // Kalman gain K = P * H^T * S^{-1}
        SimpleMatrix k = covariance.mult(h.transpose()).mult(s.invert());

        // State update: x = x + K * innovation
        state = state.plus(k.mult(innovation));

        // Joseph-form covariance update for numerical stability: P = (I-KH)*P*(I-KH)^T + K*R*K^T
        SimpleMatrix i = SimpleMatrix.identity(3);
        SimpleMatrix iMinusKh = i.minus(k.mult(h));
        covariance = iMinusKh.mult(covariance).mult(iMinusKh.transpose())
                .plus(k.mult(r).mult(k.transpose()));
    }

    /**
     * Resets the position component of the state and relaxes positional uncertainty.
     * Heading is preserved unchanged. Use this when a reliable absolute position fix is
     * available after an extended period of dead reckoning.
     *
     * @param newX new East position in metres.
     * @param newY new North position in metres.
     */
    public synchronized void resetState(double newX, double newY) {
        state.set(0, newX);
        state.set(1, newY);

        covariance = new SimpleMatrix(3, 3, true, new double[]{
                1.0, 0, 0,
                0, 1.0, 0,
                0, 0, 0.2
        });
    }

    /**
     * Directly sets the position components of the state without modifying the covariance.
     * Prefer {@link #resetState} when also resetting positional uncertainty.
     *
     * @param newX new East position in metres.
     * @param newY new North position in metres.
     */
    public synchronized void setPosition(double newX, double newY) {
        state.set(0, newX);
        state.set(1, newY);
    }

    /**
     * Directly sets the heading component of the state (normalised to [-π, π]).
     *
     * @param headingRad heading in radians (0 = north, clockwise positive).
     */
    public synchronized void setHeading(double headingRad) {
        state.set(2, wrapAngle(headingRad));
    }

    /**
     * Returns the current estimated East position in metres (local ENU frame).
     *
     * @return East coordinate in metres.
     */
    public synchronized double getX() {
        return state.get(0);
    }

    /**
     * Returns the current estimated North position in metres (local ENU frame).
     *
     * @return North coordinate in metres.
     */
    public synchronized double getY() {
        return state.get(1);
    }

    /**
     * Returns the current estimated heading in radians.
     *
     * @return heading in radians, normalised to [-π, π] (0 = north, clockwise positive).
     */
    public synchronized double getTheta() {
        return state.get(2);
    }

    /**
     * Returns a defensive copy of the current state vector {@code [x, y, theta]}.
     *
     * @return a new {@link SimpleMatrix} containing the current state.
     */
    public synchronized SimpleMatrix getStateCopy() {
        return state.copy();
    }

    /**
     * Returns a defensive copy of the current state error covariance matrix P (3×3).
     *
     * @return a new {@link SimpleMatrix} containing the current covariance.
     */
    public synchronized SimpleMatrix getCovarianceCopy() {
        return covariance.copy();
    }

    /**
     * Wraps an angle in radians to the range [-π, π].
     *
     * @param angle angle in radians, any value.
     * @return equivalent angle in [-π, π].
     */
    private static double wrapAngle(double angle) {
        while (angle > Math.PI) angle -= 2.0 * Math.PI;
        while (angle < -Math.PI) angle += 2.0 * Math.PI;
        return angle;
    }
}
