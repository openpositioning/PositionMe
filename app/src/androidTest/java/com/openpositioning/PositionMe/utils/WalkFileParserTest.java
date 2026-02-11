package com.openpositioning.PositionMe.utils;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.openpositioning.PositionMe.health.WalkSessionSummary;
import com.openpositioning.PositionMe.health.ZenWorker;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.junit.Assert.*;

/**
 * Unit tests for the {@link WalkFileParser}.
 */
public class WalkFileParserTest {

    private File tempTestFile;
    private WalkFileParser parser;
    private Context context;
    private SharedPreferences sharedPreferences;

    /**
     * Set up a temporary file for testing.
     */
    @Before
    public void setUp() throws IOException {
        context = ApplicationProvider.getApplicationContext();
        parser = new WalkFileParser(context);
        sharedPreferences = context.getSharedPreferences(ZenWorker.PREFS_NAME, Context.MODE_PRIVATE);
        sharedPreferences.edit().clear().apply(); // Clear previous test data

        // Create a temporary directory for test files
        File cacheDir = context.getCacheDir();
        tempTestFile = File.createTempFile("test_walk", ".csv", cacheDir);
    }

    /**
     * Clean up the temporary file after tests.
     */
    @After
    public void tearDown() {
        if (tempTestFile != null) {
            tempTestFile.delete();
        }
        // Clear SharedPreferences after each test
        sharedPreferences.edit().clear().apply();
    }

    /**
     * Writes content to the temporary test file.
     */
    private void writeToFile(String content) throws IOException {
        try (FileWriter writer = new FileWriter(tempTestFile)) {
            writer.write(content);
        }
    }

    @Test
    public void testParseSimpleWalk_and_SavesTimestamp() throws IOException {
        // --- Test Data ---
        long expectedTimestamp = 1678886500000L;
        String csvContent = "51.5074,-0.1278,1678886400000\n" + // Point A
                "51.5084,-0.1278," + expectedTimestamp + "\n";   // Point B (100s later)
        writeToFile(csvContent);

        // --- Parse the file ---
        WalkSessionSummary summary = parser.parseFile(tempTestFile);

        // --- Assertions for Summary ---
        assertNotNull("Summary should not be null", summary);
        assertEquals("Duration should be 100 seconds", 100, summary.getDurationSeconds());
        assertThat("Distance should be approximately 111 meters", summary.getDistanceMeters(), closeTo(111.1, 0.2));
        assertEquals("Timestamp should match the last entry", expectedTimestamp, summary.getTimestampMillis());

        // --- Assertion for SharedPreferences ---
        long savedTimestamp = sharedPreferences.getLong(ZenWorker.KEY_LAST_WALK_TIMESTAMP, 0);
        assertEquals("Parser should save the last walk timestamp to SharedPreferences", expectedTimestamp, savedTimestamp);
    }
}
