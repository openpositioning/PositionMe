package com.openpositioning.PositionMe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CircularFloatBuffer {

    // Default and maximum buffer capacities
    private static final int DEFAULT_CAPACITY = 10;
    private static final int MAX_CAPACITY = 10000;

    // Data storage and sequence pointers
    private final int capacity;
    private final float[] data;
    private volatile int writeSequence, readSequence;

    /**
     * Constructor: Initializes the circular float buffer with a specified capacity.
     * Ensures the capacity is within safe limits.
     *
     * @param capacity Maximum number of elements to store
     */
    public CircularFloatBuffer(int capacity) {
        // Clamp capacity between 1 and MAX_CAPACITY
        this.capacity = Math.max(1, Math.min(capacity, MAX_CAPACITY));

        // Allocate buffer array
        this.data = new float[this.capacity];

        // Initialize read/write pointers
        this.readSequence = 0;
        this.writeSequence = 0;
    }

    /**
     * Default constructor using DEFAULT_CAPACITY.
     */
    public CircularFloatBuffer() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * Adds a new float value to the buffer (replacing the oldest if full).
     *
     * @param element The float value to store
     * @return true if successfully stored
     */
    public boolean putNewest(float element) {
        // Compute the index to write into
        int index = writeSequence % capacity;

        // Store the new value
        data[index] = element;

        // Increment write counter
        writeSequence++;

        // Update read size (capped at capacity)
        if (readSequence < capacity) {
            readSequence++;
        }

        return true;
    }

    /**
     * Computes the average of all values in the buffer using their absolute values.
     *
     * @return The average of the buffer values, or 0 if empty
     */
    public float getAverage() {
        int count = Math.min(writeSequence, capacity);
        if (count == 0) return 0f;

        float sum = 0f;
        for (int i = 0; i < count; i++) {
            sum += Math.abs(data[i]);
        }
        return sum / count;
    }

    /**
     * Gets the current number of elements in the buffer.
     *
     * @return The size of the buffer (number of stored elements)
     */
    public int getCurrentSize() {
        return Math.min(writeSequence, capacity);
    }

    /**
     * Checks whether the buffer is empty.
     *
     * @return true if the buffer is empty, false otherwise
     */
    public boolean isEmpty() {
        return getCurrentSize() == 0;
    }

    /**
     * Checks whether the buffer is full.
     *
     * @return true if the buffer is full, false otherwise
     */
    public boolean isFull() {
        return getCurrentSize() == capacity;
    }

    /**
     * Retrieves a copy of the buffer's contents in chronological order (oldest to newest).
     *
     * @return A list of floats representing the buffer’s data. Returns an empty list if the buffer is empty.
     */
    public List<Float> getListCopy() {
        int size = getCurrentSize();
        if (size == 0) return Collections.emptyList();

        // Determine the start index of the oldest value
        int startIndex = (writeSequence >= capacity) ? (writeSequence % capacity) : 0;

        List<Float> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(this.data[(startIndex + i) % capacity]);
        }
        return list;
    }
}


