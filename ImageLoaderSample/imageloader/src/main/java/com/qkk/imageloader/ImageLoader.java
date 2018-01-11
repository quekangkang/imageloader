package com.qkk.imageloader;

import android.app.PendingIntent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.v4.media.MediaBrowserCompatUtils;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by Administrator on 2018/1/10.
 */
public class ImageLoader {
    private static final String TAG = "ImageLoader";
    private static final int CORE_POOL_SIZE = Runtime.getRuntime().availableProcessors() + 1; //经验值
    private static final int MAX_POOL_SIZE = Runtime.getRuntime().availableProcessors() * 2 + 1;//经验值
    private static final long KEEP_ALIVE = 1;
    private static final int MSG_POST_RESULT = 1;
    private static final int TAG_KEY_URI = R.id.imageloader_uri;
    private static final ThreadFactory sThreadFactory = new ThreadFactory() {
        private AtomicInteger mCount = new AtomicInteger(1);

        @Override
        public Thread newThread(@NonNull Runnable r) {
            return new Thread(r, "ImageLoader # " + mCount.getAndIncrement());
        }
    };
    //图片缓存
    LruCache<String, Bitmap> mImageCache;
    //线程池
    private static final BlockingQueue<Runnable> sPoolWorkerQueue = new LinkedBlockingQueue<>();

    private static final ExecutorService sExecutorService = new ThreadPoolExecutor(CORE_POOL_SIZE, MAX_POOL_SIZE, KEEP_ALIVE, TimeUnit.SECONDS
            , sPoolWorkerQueue, sThreadFactory);


    private Handler mMainHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            LoaderResult result = (LoaderResult) msg.obj;
            Bitmap bitmap = result.mBitmap;
            ImageView imageView = result.mImageView;
            String uri = result.mUri;
            if (uri.equals(imageView.getTag(TAG_KEY_URI))) {
                imageView.setImageBitmap(bitmap);
            }
        }
    };

    public ImageLoader() {
        initImageCache();
    }

    private void initImageCache() {
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 8);
        final int cacheSize = maxMemory / 4;
        mImageCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getRowBytes() * value.getHeight() / 1024;
            }
        };
    }

    public void displayImage(final String url, final ImageView imageView) {
        if (TextUtils.isEmpty(url)) {
            return;
        }
        imageView.setTag(TAG_KEY_URI, url);
        Bitmap bitmap = mImageCache.get(url);

        if (bitmap != null) {
            imageView.setImageBitmap(bitmap);
            return;
        }

        sExecutorService.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.i(TAG, "run: start");
                    final Bitmap bitmap = downloadImage(url);
                    SystemClock.sleep(200);
                    LoaderResult result = new LoaderResult(url, bitmap, imageView);
                    mMainHandler.obtainMessage(MSG_POST_RESULT, result).sendToTarget();
                    mImageCache.put(url, bitmap);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private Bitmap downloadImage(String imageUrl) {
        Bitmap bitmap = null;
        try {
            URL url = new URL(imageUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            bitmap = BitmapFactory.decodeStream(conn.getInputStream());
            conn.disconnect();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return bitmap;
    }

    private static class LoaderResult {
        private String mUri;
        private Bitmap mBitmap;
        private ImageView mImageView;

        public LoaderResult(String uri, Bitmap bitmap, ImageView imageView) {
            mUri = uri;
            mBitmap = bitmap;
            mImageView = imageView;
        }
    }
}
