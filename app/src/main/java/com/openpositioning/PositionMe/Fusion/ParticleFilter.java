package com.openpositioning.PositionMe.Fusion;

import com.openpositioning.PositionMe.Fusion.FusionAlgorithm;

import java.util.Random;

public class ParticleFilter implements FusionAlgorithm {
    private static final int NUM_PARTICLES = 100;
    private float[][] particles = new float[NUM_PARTICLES][2];
    private float[] weights = new float[NUM_PARTICLES];
    private Random random = new Random();

    @Override
    public void init(float[] initialPos) {
        for (int i = 0; i < NUM_PARTICLES; i++) {
            particles[i][0] = initialPos[0] + random.nextFloat() - 0.5f;
            particles[i][1] = initialPos[1] + random.nextFloat() - 0.5f;
            weights[i] = 1.0f / NUM_PARTICLES;
        }
    }

    @Override
    public void predict(float[] delta) {
        for (int i = 0; i < NUM_PARTICLES; i++) {
            particles[i][0] += delta[0];
            particles[i][1] += delta[1];
        }
    }

    @Override
    public void updateFromGnss(float[] gnssPos) {
        for (int i = 0; i < NUM_PARTICLES; i++) {
            float dx = particles[i][0] - gnssPos[0];
            float dy = particles[i][1] - gnssPos[1];
            weights[i] = 1.0f / (1.0f + dx * dx + dy * dy);
        }
        normalizeWeights();
        resample();
    }

    @Override
    public void updateFromWifi(float[] wifiPos) {
        updateFromGnss(wifiPos);
    }

    @Override
    public float[] getFusedPosition() {
        float x = 0, y = 0;
        for (int i = 0; i < NUM_PARTICLES; i++) {
            x += particles[i][0] * weights[i];
            y += particles[i][1] * weights[i];
        }
        return new float[]{x, y};
    }

    private void normalizeWeights() {
        float sum = 0;
        for (float w : weights) sum += w;
        for (int i = 0; i < weights.length; i++) weights[i] /= sum;
    }

    private void resample() {
        float[][] newParticles = new float[NUM_PARTICLES][2];
        for (int i = 0; i < NUM_PARTICLES; i++) {
            int index = i; // simple resampling
            newParticles[i] = particles[index].clone();
        }
        particles = newParticles;
    }
}