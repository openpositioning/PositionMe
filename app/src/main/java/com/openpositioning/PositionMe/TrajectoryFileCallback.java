package com.openpositioning.PositionMe;  // 或者放到你喜欢的合适包下

import java.io.File;

public interface TrajectoryFileCallback {
    /**
     * 当临时文件准备好时调用
     * @param file 临时文件，包含下载的轨迹数据
     */
    void onFileReady(File file);



    /**
     * 当发生错误时调用
     * @param errorMessage 错误信息
     */
    void onError(String errorMessage);
}

