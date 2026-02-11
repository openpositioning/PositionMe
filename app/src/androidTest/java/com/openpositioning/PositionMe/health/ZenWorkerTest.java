package com.openpositioning.PositionMe.health;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.work.ListenableWorker;
import androidx.work.testing.TestListenableWorkerBuilder;

import org.junit.Before;
import org.junit.Test;

import java.util.Calendar;

import static org.junit.Assert.*;

public class ZenWorkerTest {

    private Context context;
    private SharedPreferences sharedPreferences;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        sharedPreferences = context.getSharedPreferences(ZenWorker.PREFS_NAME, Context.MODE_PRIVATE);
        // Clear any previous test data
        sharedPreferences.edit().clear().apply();
    }

    @Test
    public void testWorker_shouldNotify_whenNoWalkRecorded() throws Exception {
        // --- Test ---
        TestListenableWorkerBuilder<ZenWorker> builder = TestListenableWorkerBuilder.from(context, ZenWorker.class);
        ZenWorker worker = builder.build();
        ListenableWorker.Result result = worker.startWork().get();

        // --- Assert ---
        assertEquals(ListenableWorker.Result.success(), result);
        assertTrue("Worker should decide to notify when no walk is recorded", worker.getShouldNotify());
    }

    @Test
    public void testWorker_shouldNotify_whenLastWalkWasYesterday() throws Exception {
        // --- Setup: Simulate a walk yesterday ---
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DATE, -1); // Go back one day
        long yesterdayTimestamp = calendar.getTimeInMillis();
        sharedPreferences.edit().putLong(ZenWorker.KEY_LAST_WALK_TIMESTAMP, yesterdayTimestamp).apply();

        // --- Test ---
        TestListenableWorkerBuilder<ZenWorker> builder = TestListenableWorkerBuilder.from(context, ZenWorker.class);
        ZenWorker worker = builder.build();
        ListenableWorker.Result result = worker.startWork().get();

        // --- Assert ---
        assertEquals(ListenableWorker.Result.success(), result);
        assertTrue("Worker should notify when the last walk was yesterday", worker.getShouldNotify());
    }

    @Test
    public void testWorker_shouldNotNotify_whenLastWalkWasToday() throws Exception {
        // --- Setup: Simulate a walk today ---
        long todayTimestamp = System.currentTimeMillis();
        sharedPreferences.edit().putLong(ZenWorker.KEY_LAST_WALK_TIMESTAMP, todayTimestamp).apply();

        // --- Test ---
        TestListenableWorkerBuilder<ZenWorker> builder = TestListenableWorkerBuilder.from(context, ZenWorker.class);
        ZenWorker worker = builder.build();
        ListenableWorker.Result result = worker.startWork().get();

        // --- Assert ---
        assertEquals(ListenableWorker.Result.success(), result);
        assertFalse("Worker should not notify when a walk was already recorded today", worker.getShouldNotify());
    }
}
