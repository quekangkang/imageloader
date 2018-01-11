package com.qkk.imageloader;

import java.io.Closeable;
import java.io.IOException;

/**
 * Created by Administrator on 2018/1/12.
 */

public class CloseUtils {
    public static void close(Closeable closeable){
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
