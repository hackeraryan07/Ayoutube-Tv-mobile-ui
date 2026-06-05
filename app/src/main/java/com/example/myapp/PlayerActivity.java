package com.example.myapp;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import com.example.myapp.extractor.YouTubeExtractorService;
import com.example.myapp.model.VideoItem;
import com.google.android.material.button.MaterialButton;

import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;

import okhttp3.OkHttpClient;

/**
 * Full-screen ExoPlayer activity for mobile.
 *
 * Changes from TV version:
 * - Extends AppCompatActivity (Material 3 compatible)
 * - Removed D-pad / remote key handling (touch controls via ExoPlayer UI)
 * - Kept all Android 9 API-28 compatibility fixes intact
 * - MaterialButton for Retry
 */
@SuppressWarnings("deprecation")   // setSystemUiVisibility on API 28
public class PlayerActivity extends AppCompatActivity {

    public static final String EXTRA_VIDEO = "extra_video";
    private static final String TAG = "PlayerActivity";

    private PlayerView    mPlayerView;
    private View          mLoadingOverlay;
    private View          mErrorOverlay;
    private TextView      mErrorText;
    private MaterialButton mRetryBtn;

    private ExoPlayer mPlayer;
    private VideoItem mVideo;

    private final CompositeDisposable mDisposables = new CompositeDisposable();

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Fullscreen immersive — API 28 compatible (WindowInsetsController is API 30+)
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        );

        setContentView(R.layout.activity_player);

        mPlayerView     = findViewById(R.id.player_view);
        mLoadingOverlay = findViewById(R.id.loading_overlay);
        mErrorOverlay   = findViewById(R.id.error_overlay);
        mErrorText      = findViewById(R.id.error_text);
        mRetryBtn       = findViewById(R.id.retry_button);

        // API 28-safe getSerializableExtra (1-arg version + cast)
        Object extra = getIntent().getSerializableExtra(EXTRA_VIDEO);
        if (!(extra instanceof VideoItem)) { finish(); return; }
        mVideo = (VideoItem) extra;

        mRetryBtn.setOnClickListener(v -> {
            mErrorOverlay.setVisibility(View.GONE);
            loadAndPlay();
        });

        buildPlayer();
        loadAndPlay();
    }

    @Override protected void onStart()   { super.onStart();  if (mPlayer != null) mPlayer.play(); }
    @Override protected void onStop()    { super.onStop();   if (mPlayer != null) mPlayer.pause(); }

    @Override
    protected void onDestroy() {
        mDisposables.clear();
        releasePlayer();
        super.onDestroy();
    }

    // ── Player construction ────────────────────────────────────────────────

    private void buildPlayer() {
        // API 28 fix: OkHttpDataSource.Factory(OkHttpClient) constructor
        OkHttpClient okHttp = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

        OkHttpDataSource.Factory httpFactory = new OkHttpDataSource.Factory(okHttp);
        httpFactory.setUserAgent(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        );

        // API 28 fix: use activity context (this), not applicationContext
        mPlayer = new ExoPlayer.Builder(this)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(httpFactory))
            .build();

        mPlayerView.setPlayer(mPlayer);
        mPlayerView.setUseController(true);
        mPlayerView.setControllerAutoShow(true);
        mPlayerView.setControllerShowTimeoutMs(3000);

        mPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                mLoadingOverlay.setVisibility(
                    state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                if (state == Player.STATE_READY)
                    mErrorOverlay.setVisibility(View.GONE);
            }
            @Override
            public void onPlayerError(PlaybackException e) {
                Log.e(TAG, "Playback error", e);
                mLoadingOverlay.setVisibility(View.GONE);
                mErrorText.setText(getString(R.string.error_load) + "\n" +
                    (e.getMessage() != null ? e.getMessage() : "Unknown error"));
                mErrorOverlay.setVisibility(View.VISIBLE);
            }
        });
    }

    private void loadAndPlay() {
        mLoadingOverlay.setVisibility(View.VISIBLE);
        mErrorOverlay.setVisibility(View.GONE);

        mDisposables.add(
            YouTubeExtractorService.getInstance()
                .getStreamUrl(mVideo.getVideoId())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    this::startPlayback,
                    err -> {
                        Log.e(TAG, "Extraction error", err);
                        mLoadingOverlay.setVisibility(View.GONE);
                        mErrorText.setText(getString(R.string.error_load) + "\n" +
                            (err.getMessage() != null ? err.getMessage() : "Extraction failed"));
                        mErrorOverlay.setVisibility(View.VISIBLE);
                    }
                )
        );
    }

    private void startPlayback(String url) {
        if (mPlayer == null) return;
        Log.d(TAG, "Playing: " + url.substring(0, Math.min(60, url.length())));
        mPlayer.setMediaItem(MediaItem.fromUri(url));
        mPlayer.prepare();
        mPlayer.setPlayWhenReady(true);
    }

    /** API 28 fix: clearVideoSurface() before release prevents Surface leak */
    private void releasePlayer() {
        if (mPlayer != null) {
            mPlayer.clearVideoSurface();
            mPlayer.release();
            mPlayer = null;
        }
    }
}
