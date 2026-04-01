package com.openpositioning.PositionMe.utils;

import android.graphics.PointF;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class WallCollisionCorrectorTest {

    @Test
    public void stubCorrectionReturnsCandidate() {
        PointF prev = new PointF(0f, 0f);
        PointF cand = new PointF(5f, 5f);
        PointF w1 = new PointF(10f, 0f);
        PointF w2 = new PointF(10f, 10f);

        PointF corrected = WallCollisionCorrector.correct(prev, cand, Arrays.asList(Arrays.asList(w1, w2)));

        assertEquals(cand.x, corrected.x, 0.0001f);
        assertEquals(cand.y, corrected.y, 0.0001f);
    }

    @Test
    public void crossingWallReturnsPrevious() {
        PointF prev = new PointF(0f, 0f);
        PointF cand = new PointF(10f, 0f);
        List<PointF> wall = Arrays.asList(new PointF(5f, -5f), new PointF(5f, 5f));

        PointF corrected = WallCollisionCorrector.correct(prev, cand, Arrays.asList(wall));

        assertSame(prev, corrected);
    }
}
