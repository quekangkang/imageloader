package com.qkk.imageloadersample;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;

import com.qkk.imageloader.ImageLoader;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final String[] imageUrls = new String[]{
                "http://n.sinaimg.cn/blog/crawl/20170803/HxCz-fyitamv4614405.jpg"
        };
        final ImageLoader imageLoader = new ImageLoader();
        setContentView(R.layout.activity_main);
        final ImageView iv = findViewById(R.id.iv);
        findViewById(R.id.btn_load).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                imageLoader.displayImage(imageUrls[0], iv);
            }
        });
    }
}
