package com.openpositioning.PositionMe.sensors;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BleDataProcessorTest {

    @Test
    public void testBleProcessorInitialisation() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        BleDataProcessor processor = new BleDataProcessor(context);
        assertNotNull("BLE processor should initialise", processor);
    }

    @Test
    public void testObserverRegistration() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        BleDataProcessor processor = new BleDataProcessor(context);

        Observer testObserver = objList -> {
            // Test observer
        };

        processor.registerObserver(testObserver);
        // If no exception, test passes
        assertTrue(true);
    }
}