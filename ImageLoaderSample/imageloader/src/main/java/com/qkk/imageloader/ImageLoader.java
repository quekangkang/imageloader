package com.qkk.imageloader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ResultReceiver;
import android.os.StatFs;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.Buffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
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
    private static final int DISK_CACHE_SIZE = 1024 * 1024 * 50;
    private static final int DISK_CACHE_INDEX = 0;
    private static final int IO_BUFFER_SIZE = 8 * 1024;
    private boolean mDiskLruCacheCreated;
    private DiskLruCache mDiskLruCache;
    private static final ThreadFactory sThreadFactory = new ThreadFactory() {
        private AtomicInteger mCount = new AtomicInteger(1);

        @Override
        public Thread newThread(@NonNull Runnable r) {
            return new Thread(r, "ImageLoader # " + mCount.getAndIncrement());
        }
    };
    //图片缓存
    LruCache<String, Bitmap> mMemoryCache;
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
    private Context mContext;

    public ImageLoader(Context context) {
        initImageCache(context);
    }

    private void initImageCache(Context context) {
        mContext = context;
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 8);
        final int cacheSize = maxMemory / 4;
        mMemoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getRowBytes() * value.getHeight() / 1024;
            }
        };
        File diskCacheDir = getDiskCacheDir(context, "bitmap");
        if (!diskCacheDir.exists()) {
            diskCacheDir.mkdirs();
        }
        if (getUsableSpace(diskCacheDir) > DISK_CACHE_SIZE) {
            try {
                mDiskLruCache = DiskLruCache.open(diskCacheDir, 1, 1, DISK_CACHE_SIZE);
                mDiskLruCacheCreated = true;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private long getUsableSpace(File path) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
            return path.getUsableSpace();
        }
        final StatFs statFs = new StatFs(path.getPath());
        return (long) statFs.getBlockSize() * (long) statFs.getAvailableBlocks();
    }

    private File getDiskCacheDir(Context context, String uniqueName) {
        boolean externalStorageAvailable = Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED);
        String cachePath;
        if (externalStorageAvailable) {
            cachePath = context.getExternalCacheDir().getPath();
        } else {
            cachePath = context.getCacheDir().getPath();
        }
        return new File(cachePath + File.separator + uniqueName);
    }

    public void displayImage(final String url, final ImageView imageView) {
        if (TextUtils.isEmpty(url)) {
            return;
        }
        imageView.setTag(TAG_KEY_URI, url);
        Bitmap bitmap = loadBitmapFromMemCache(url);

        if (bitmap != null) {
            imageView.setImageBitmap(bitmap);
            return;
        }

        sExecutorService.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.i(TAG, "run: start");
                    final Bitmap bitmap = loadBitmap(url);
                    SystemClock.sleep(200);
                    LoaderResult result = new LoaderResult(url, bitmap, imageView);
                    mMainHandler.obtainMessage(MSG_POST_RESULT, result).sendToTarget();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private Bitmap loadBitmapFromMemCache(String url) {
        final String key = hashKeyFromUrl(url);
        return mMemoryCache.get(key);
    }

    private Bitmap loadBitmap(String uri) {
        Bitmap bitmap = loadBitmapFromMemCache(uri);
        if (bitmap != null) {
            return bitmap;
        }

        try {
            bitmap = loadBitmapFromDiskCache(uri);
            if (bitmap != null) {
                return bitmap;
            }
            bitmap = loadBitmapFromHttp(uri);
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (bitmap == null && !mDiskLruCacheCreated) {
            bitmap = downloadBitmapFromUrl(uri);
        }
        return bitmap;
    }

    private Bitmap downloadBitmapFromUrl(String uri) {
        Bitmap bitmap = null;
        HttpURLConnection conn = null;
        BufferedInputStream in = null;

        try {
            URL url = new URL(uri);
            conn = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(conn.getInputStream());
            BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            e.printStackTrace();
        }finally {
            if (conn != null) {
                conn.disconnect();
            }
            CloseUtils.close(in);
        }
        return bitmap;
    }

    private Bitmap loadBitmapFromHttp(String uri) throws IOException {
        if (mDiskLruCache == null) {
            return null;
        }

        String key = hashKeyFromUrl(uri);
        DiskLruCache.Editor edit = mDiskLruCache.edit(key);
        if (edit != null) {
            OutputStream outputStream = edit.newOutputStream(DISK_CACHE_INDEX);
            if (downloadUrlToStream(uri, outputStream)) {
                edit.commit();
            }else{
                edit.abort();
            }
            mDiskLruCache.flush();
        }
        return loadBitmapFromDiskCache(uri);
    }

    private boolean downloadUrlToStream(String uri, OutputStream outputStream) {
        HttpURLConnection conn = null;
        BufferedOutputStream out = null;
        BufferedInputStream in = null;
        try {
            URL url = new URL(uri);
            conn = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(conn.getInputStream());
            out = new BufferedOutputStream(outputStream, IO_BUFFER_SIZE);

            int b;
            while ((b = in.read()) != -1) {
                out.write(b);
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
        }finally {
            if (conn != null) {
                conn.disconnect();
            }
            CloseUtils.close(in);
            CloseUtils.close(out);
        }
        return false;
    }

    private Bitmap loadBitmapFromDiskCache(String uri) throws IOException {
        if (mDiskLruCache == null) {
            return null;
        }

        String key = hashKeyFromUrl(uri);
        DiskLruCache.Snapshot snapshot = mDiskLruCache.get(key);
        Bitmap bitmap = null;
        if (snapshot != null) {
            FileInputStream fileInputStream = (FileInputStream) snapshot.getInputStream(DISK_CACHE_INDEX);
            FileDescriptor fd = fileInputStream.getFD();
            bitmap = BitmapFactory.decodeFileDescriptor(fd);
            if (bitmap != null) {
                addBitmapToMemory(key, bitmap);
            }
        }
        return bitmap;
    }

    private void addBitmapToMemory(String key, Bitmap bitmap) {
        if (loadBitmapFromMemCache(key) == null) {
            mMemoryCache.put(key, bitmap);
        }
    }

    private String hashKeyFromUrl(String uri) {
        String cacheKey;
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            digest.update(uri.getBytes());
            cacheKey = bytesToHexString(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            cacheKey = String.valueOf(uri.hashCode());
        }
        return cacheKey;
    }

    private String bytesToHexString(byte[] bytes) {
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            String hexString = Integer.toHexString(0xFF & bytes[i]);
            if (hexString.length() == 1) {
                stringBuilder.append('0');
            }
            stringBuilder.append(hexString);
        }
        return stringBuilder.toString();
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
