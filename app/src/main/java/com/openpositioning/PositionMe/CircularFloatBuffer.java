package com.openpositioning.PositionMe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CircularFloatBuffer {
    // 默认最小容量
    //Default minimum capacity
    private static final int DEFAULT_CAPACITY = 10;
    private static final int MAX_CAPACITY = 10000;

    // 数据存储数组和指针
    //Data storage arrays and pointers
    private final int capacity;
    private final float[] data;
    private volatile int writeSequence, readSequence;

    /**
     * 构造函数：初始化循环缓冲区
     * Constructor: Initialize the circular buffer
     */
    public CircularFloatBuffer(int capacity) {
        // 限制容量范围，防止过小或过大
        // Limit the capacity range to prevent it from being too small or too large
        this.capacity = Math.max(1, Math.min(capacity, MAX_CAPACITY));

        // 分配存储数组
        // Allocate storage array
        this.data = new float[this.capacity];

        // 初始化读写指针
        // Initialize read and write pointers
        this.readSequence = 0;
        this.writeSequence = 0;
    }

    /**
     * 默认构造函数（使用默认大小）
     * Default constructor (uses default size)
     */
    public CircularFloatBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public boolean putNewest(float element) {
        // 计算写入索引
        // Calculate the write index
        int index = writeSequence % capacity;

        // 写入数据
        // Write data
        data[index] = element;

        // 更新写指针
        // Update the write pointer
        writeSequence++;

        // 确保 readSequence 始终等于缓冲区的大小（最多存 `capacity` 个数据）
        // Make sure readSequence is always equal to the size of the buffer (store at most `capacity` data)
        if (readSequence < capacity) {
            readSequence++;
        }

        return true;
    }

    public float getAverage() {
        int count = Math.min(writeSequence, capacity); // 确保读取所有有效数据 Make sure to read all valid data

        if (count == 0) return 0f; // 避免除以 0 Avoid division by 0

        float sum = 0f;
        for (int i = 0; i < count; i++) {
            sum += Math.abs(data[i]);
        }
        return sum / count;
    }

    /**
     * 获取当前缓冲区中的元素数量
     *
     * @return  当前存储的元素数量
     * Get the number of elements in the current buffer
     *
     * @return The number of elements currently stored
     */
    public int getCurrentSize() {
        return Math.min(writeSequence, capacity);
    }

    /**
     * 判断缓冲区是否为空
     *
     * @return  true：缓冲区为空，false：缓冲区不为空
     * Determine whether the buffer is empty
     *
     * @return true: the buffer is empty, false: the buffer is not empty
     */
    public boolean isEmpty() {
        return getCurrentSize() == 0;
    }

    /**
     * 判断缓冲区是否已满
     *
     * @return  true：缓冲区已满，false：缓冲区未满
     * Determine whether the buffer is full
     *
     * @return true: the buffer is full, false: the buffer is not full
     */
    public boolean isFull() {
        return getCurrentSize() == capacity;
    }


    /**
     * 获取缓冲区所有数据的副本（按时间排序）
     *
     * @return List<Float> 按时间顺序存储的缓冲区数据（从最早到最新）。
     *         如果缓冲区为空，则返回空列表。
     * Get a copy of all the buffer's data (sorted by time)
     *
     * @return List<Float> The buffer's data stored in chronological order (oldest to newest).
     * If the buffer is empty, returns an empty list.
     */
    public List<Float> getListCopy() {
        int size = getCurrentSize();
        if (size == 0) return Collections.emptyList(); // 避免返回 null Avoid returning null

        // 确定起始索引（最早存入的数据）
        // Determine the starting index (the earliest stored data)
        int startIndex = (writeSequence >= capacity) ? (writeSequence % capacity) : 0;

        List<Float> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(this.data[(startIndex + i) % capacity]);
        }
        return list;
    }


}

