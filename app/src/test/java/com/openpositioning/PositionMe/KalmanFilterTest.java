package com.openpositioning.PositionMe;

import com.google.android.gms.common.internal.Asserts;
import com.openpositioning.PositionMe.sensors.filters.KalmanFilter;
import com.openpositioning.PositionMe.sensors.filters.KalmanFilterAdapter;

import org.ejml.simple.SimpleMatrix;
import org.junit.Assert;
import org.junit.Test;

public class KalmanFilterTest {
    @Test
    public void basicTest() {
        double[] initPos = {21, 37};
        SimpleMatrix initCov = new SimpleMatrix(new double[][]{
                {1  , 0.5},
                {0.5, 0.9}
        });
        KalmanFilter filter = new KalmanFilter(initPos, initCov);
        double[] kalmanPos = filter.getPosition();
        SimpleMatrix kalmanCov = filter.getCovariance();

        Assert.assertArrayEquals(initPos, kalmanPos, 1e-6);

        for (int i = 0; i < 4; i++) {
            Assert.assertEquals(
                    kalmanCov.get(i / 2, i % 2), initCov.get(i / 2, i % 2),
                    1e-6);
        }

        double[] newPos = {2, 1};
        SimpleMatrix newCov = new SimpleMatrix(new double[][]{
                {2  , 1.5},
                {1.5, 1.9}
        });
        filter.resetState(newPos, newCov);
        kalmanPos = filter.getPosition();
        kalmanCov = filter.getCovariance();

        Assert.assertArrayEquals(newPos, kalmanPos, 1e-6);

        for (int i = 0; i < 4; i++) {
            Assert.assertEquals(
                    kalmanCov.get(i / 2, i % 2), newCov.get(i / 2, i % 2),
                    1e-6);
        }
    }

    /**
     * A basic kalman filter test for the following motion and observation model
     * x_t+1 = x_t + delta_t * v
     * z_t   = x_t
     */
    @Test
    public void basicStepTest() {
        double[] initPos = {1, 2};
        SimpleMatrix initCov = new SimpleMatrix(new double[][]{
                {0.5, 0},
                {0  , 0.5}}
        );
        KalmanFilter filter = new KalmanFilter(initPos, initCov);

        double delta_t = 0.1;
        double[] velocity = {10, 15};
        // {2, 3.5}
        double[] predPos = {initPos[0] + delta_t * velocity[0], initPos[1] + delta_t * velocity[1]};
        double[] observation = {1.8, 4};
        SimpleMatrix motionUpdateCov = new SimpleMatrix(new double[][]{
                {0.5, 0  },
                {0  , 0.5}
        });
        SimpleMatrix observationCov = new SimpleMatrix(new double[][]{
                {0.1, 0  },
                {0  , 0.1}
        });
        SimpleMatrix F = SimpleMatrix.identity(2);
        SimpleMatrix B = new SimpleMatrix(new double[][]{
                {delta_t, 0      },
                {0      , delta_t}
        });
        SimpleMatrix H = SimpleMatrix.identity(2);

        filter.step(velocity, observation, F, B, H, motionUpdateCov, observationCov);
        double[] kalmanPos1 = filter.getPosition();

        // Kalman position should be between the motion-predicted position and the observation
        Assert.assertTrue(kalmanPos1[0] > observation[0] && kalmanPos1[0] < predPos[0]);
        Assert.assertTrue(kalmanPos1[1] < observation[1] && kalmanPos1[1] > predPos[1]);

        // Now, let's put more confidence into motion update
        filter.resetState(initPos, initCov);
        motionUpdateCov = new SimpleMatrix(new double[][]{
                {0.01, 0   },
                {0   , 0.01}
        });
        observationCov = new SimpleMatrix(new double[][]{
                {0.5, 0  },
                {0  , 0.5}
        });
        filter.step(velocity, observation, F, B, H, motionUpdateCov, observationCov);
        double[] kalmanPos2 = filter.getPosition();

        // Kalman position should be closer to the motion-predicted position than before
        Assert.assertTrue(Math.abs(kalmanPos2[0] - predPos[0]) <
                Math.abs(kalmanPos1[0] - predPos[0]));
        Assert.assertTrue(Math.abs(kalmanPos2[1] - predPos[1]) <
                Math.abs(kalmanPos1[1] - predPos[1]));
    }

    @Test
    public void kalmanAdapterTest() {
        SimpleMatrix initCov = new SimpleMatrix(new double[][]{
                {1.0, 0  },
                {0  , 1.0}
        });
        SimpleMatrix pdrCov = new SimpleMatrix(new double[][]{
                {2.0, 0  },
                {0  , 0.1}
        });
        SimpleMatrix obsCov = new SimpleMatrix(new double[][]{
                {0.5, 0  },
                {0  , 0.5}
        });
        KalmanFilterAdapter kalmanAdapter = new KalmanFilterAdapter(new double[]{0, 0},
                initCov, 0.0, pdrCov);

        double[] pdrData = {2.0, -5.0};
        double timestamp = 5.5;
        double[] obs = {2.5, -3.5};

        Assert.assertTrue(kalmanAdapter.update(pdrData, obs, timestamp, obsCov));

        double[] kalmanPos = kalmanAdapter.getPos();
        SimpleMatrix kalmanCov = kalmanAdapter.getCovariance();

        Assert.assertTrue(kalmanPos[0] < obs[0] && kalmanPos[0] > pdrData[0]);
        Assert.assertTrue(kalmanPos[1] < obs[1] && kalmanPos[1] > pdrData[1]);
        Assert.assertTrue(Math.abs(kalmanCov.get(0, 1)) > 0);
    }
}
