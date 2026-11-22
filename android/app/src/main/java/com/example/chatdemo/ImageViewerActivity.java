package com.example.chatdemo;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.InputStream;

/** 全屏图片查看：点任意位置或返回键关闭。 */
public class ImageViewerActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        Uri uri = getIntent().getData();
        Bitmap bitmap = uri == null ? null : decode(uri);
        if (bitmap == null) {
            TextView error = new TextView(this);
            error.setText(R.string.open_failed);
            error.setTextColor(Color.WHITE);
            error.setGravity(Gravity.CENTER);
            root.addView(error, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setImageBitmap(bitmap);
            root.addView(image, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        root.setOnClickListener(view -> finish());
    }

    private Bitmap decode(Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= metrics.widthPixels
                    && bounds.outHeight / (sample * 2) >= metrics.heightPixels) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, options);
            }
        } catch (Exception | OutOfMemoryError error) {
            return null;
        }
    }
}
