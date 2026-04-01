package com.openpositioning.PositionMe.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Ring buffer for floats that can constantly update values in a fixed sized array.
 *
 * @author Mate Stodulka
 */
public class CircularFloatBuffer {
    // Default capacity for the buffer in case initial capacity is invalid
    private static final int DEFAULT_CAPACITY = 10;

    // Data array and pointers
    private final int capacity;
    private final float[] data;
    private volatile int writeSequence, readSequence;

    public static final class SnapshotStats {
        public static final SnapshotStats EMPTY = new SnapshotStats(0, 0f, 0f);

        public final int count;
        public final float averageAbs;
        public final float peakAbs;

        private SnapshotStats(int count, float averageAbs, float peakAbs) {
            this.count = count;
            this.averageAbs = averageAbs;
            this.peakAbs = peakAbs;
        }
    }

    /**
     * Default constructor for a Circular Float Buffer with a given capacity.
     *
     * @param capacity  size of the array.
     */
    public CircularFloatBuffer(int capacity) {
        int safeCapacity = (capacity < 1) ? DEFAULT_CAPACITY : capacity;
        this.capacity = safeCapacity;
        this.data = new float[safeCapacity];
        this.readSequence = 0;
        this.writeSequence = -1;
    }

    /**
     * Put in a new element to the array.
     * Overwrites the existing values if present already and moves the write head forward.
     *
     * @param element   float value to be added to the array.
     * @return          true if adding to the element was successful.
     */
    public boolean putNewest(float element) {
        int nextWriteSeq = writeSequence + 1;
        data[nextWriteSeq % capacity] = element;
        writeSequence++;
        return true;
    }

    /**
     * Get the oldest element in the array.
     * If empty, return an empty Optional. Moves the read head forward.
     *
     * @return  Optional float of the oldest element.
     *
     * @see Optional
     */
    public Optional<Float> getOldest() {
        if (!isEmpty()) {
            float nextValue = data[readSequence % capacity];
            readSequence++;
            return Optional.of(nextValue);
        }
        return Optional.empty();
    }

    /**
     * Get the capacity of the buffer.
     *
     * @return  int capacity, size of the underlying array.
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * Get the number of elements currently in the buffer.
     *
     * @return  int number of floats in the buffer.
     */
    public int getCurrentSize() {
        return (writeSequence - readSequence) + 1;
    }

    /**
     * Checks if the buffer is empty.
     *
     * @return  true if there are no elements in the buffer, false otherwise
     */
    public boolean isEmpty() {
        return writeSequence < readSequence;
    }

    /**
     * Check if the buffer is full.
     *
     * @return  true if the number of elements in the buffer matches the capacity, false otherwise.
     */
    public boolean isFull() {
        return getCurrentSize() >= capacity;
    }

    /**
     * Get a copy of the buffer as a list starting with the oldest element.
     * If the list is not full return null.
     *
     * @return List of Floats contained in the buffer from oldest to newest.
     */
    public List<Float> getListCopy() {
        if(!isFull()) return null;
        ArrayList<Float> snapshot = new ArrayList<>(capacity);
        for (int i = 0; i < capacity; i++) {
            snapshot.add(this.data[(readSequence + i) % capacity]);
        }
        return snapshot;
    }

    /**
     * Get a snapshot of currently buffered values from oldest to newest.
     * Unlike {@link #getListCopy()}, this also works before the buffer reaches full capacity.
     *
     * @return List of floats currently held in the buffer, or an empty list when no values exist.
     */
    public List<Float> getSnapshot() {
        int size = getCurrentSize();
        if (size <= 0) {
            return java.util.Collections.emptyList();
        }
        int safeSize = Math.min(size, capacity);
        int start = Math.max(readSequence, writeSequence - safeSize + 1);
        ArrayList<Float> snapshot = new ArrayList<>(safeSize);
        for (int i = 0; i < safeSize; i++) {
            snapshot.add(this.data[(start + i) % capacity]);
        }
        return snapshot;
    }

    /**
     * Summarise the current snapshot without allocating intermediate boxed collections.
     *
     * @return Count, average absolute magnitude, and peak absolute magnitude.
     */
    public SnapshotStats getSnapshotStats() {
        int size = getCurrentSize();
        if (size <= 0) {
            return SnapshotStats.EMPTY;
        }

        int safeSize = Math.min(size, capacity);
        int start = Math.max(readSequence, writeSequence - safeSize + 1);
        float sumAbs = 0f;
        float peakAbs = 0f;
        int validCount = 0;

        for (int i = 0; i < safeSize; i++) {
            float sample = this.data[(start + i) % capacity];
            if (Float.isNaN(sample) || Float.isInfinite(sample)) {
                continue;
            }
            float magnitude = Math.abs(sample);
            sumAbs += magnitude;
            peakAbs = Math.max(peakAbs, magnitude);
            validCount++;
        }

        if (validCount == 0) {
            return SnapshotStats.EMPTY;
        }

        return new SnapshotStats(validCount, sumAbs / validCount, peakAbs);
    }

}
