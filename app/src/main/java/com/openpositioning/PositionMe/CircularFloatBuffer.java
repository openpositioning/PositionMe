package com.openpositioning.PositionMe;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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

    /**
     * Default constructor for a Circular Float Buffer with a given capacity.
     *
     * @param capacity  size of the array.
     */
    public CircularFloatBuffer(int capacity) {
        this.capacity = (capacity < 1) ? DEFAULT_CAPACITY : capacity;
        this.data = new float[capacity];
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
        return IntStream.range(readSequence, readSequence + capacity)
                .mapToObj(i -> this.data[i % capacity])
                .collect(Collectors.toList());
    }

}
