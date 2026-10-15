package com.yuhuo.shengqiao.bridge;
import android.os.ParcelFileDescriptor;
interface IRecorderBridge {
    String status() = 0;
    ParcelFileDescriptor exportMetadata() = 1;
    ParcelFileDescriptor openAudio(String path) = 2;
    long[] fileInfo(String path) = 3;
    void destroy() = 16777114;
}
