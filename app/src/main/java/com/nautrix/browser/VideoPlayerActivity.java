package com.nautrix.browser;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/** Dedicated Media3 player with actionable buffering and network feedback. */
public final class VideoPlayerActivity extends Activity {
    private static final String EXTRA_VIDEO_URL = "video_url";
    private static final String EXTRA_REFERER = "referer";
    private static final String EXTRA_USER_AGENT = "user_agent";
    private static final String EXTRA_COOKIE = "cookie";
    private static final String STATE_POSITION = "position";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PlayerView playerView;
    private TextView statusText;
    private TextView statusDetail;
    private ProgressBar bufferProgress;
    private Button retryButton;
    private ExoPlayer player;
    private Uri videoUri;
    private String referer;
    private String userAgent;
    private String cookie;
    private long resumePositionMs;
    private long bufferingStartedAtMs;
    private boolean buffering;
    private boolean hasPlayed;

    private final Runnable bufferingTicker = new Runnable() {
        @Override
        public void run() {
            if (!buffering || player == null) return;
            updateBufferingStatus();
            handler.postDelayed(this, 500L);
        }
    };

    public static Intent createIntent(Context context, String videoUrl, String referer,
                                      String userAgent, String cookie) {
        return new Intent(context, VideoPlayerActivity.class)
                .putExtra(EXTRA_VIDEO_URL, videoUrl)
                .putExtra(EXTRA_REFERER, referer)
                .putExtra(EXTRA_USER_AGENT, userAgent)
                .putExtra(EXTRA_COOKIE, cookie);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#0B1117"));
        getWindow().setNavigationBarColor(Color.parseColor("#0B1117"));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Intent intent = getIntent();
        String rawUrl = intent == null ? null : intent.getStringExtra(EXTRA_VIDEO_URL);
        videoUri = rawUrl == null ? null : Uri.parse(rawUrl);
        if (videoUri == null || !"https".equalsIgnoreCase(videoUri.getScheme())) {
            Toast.makeText(this, "O player aceita somente vídeos HTTPS", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        referer = cleanHeader(intent.getStringExtra(EXTRA_REFERER));
        userAgent = cleanHeader(intent.getStringExtra(EXTRA_USER_AGENT));
        cookie = cleanHeader(intent.getStringExtra(EXTRA_COOKIE));
        if (savedInstanceState != null) {
            resumePositionMs = savedInstanceState.getLong(STATE_POSITION, 0L);
        }
        buildInterface();
    }

    private void buildInterface() {
        int background = Color.parseColor("#0B1117");
        int surface = Color.parseColor("#131C24");
        int text = Color.parseColor("#F3F7FA");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(6), dp(4), dp(12), dp(4));
        toolbar.setBackgroundColor(surface);

        Button close = new Button(this);
        close.setText("‹");
        close.setTextSize(27f);
        close.setTextColor(text);
        close.setContentDescription("Voltar ao navegador");
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setOnClickListener(view -> finish());
        toolbar.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView title = new TextView(this);
        title.setText("Player de vídeo");
        title.setTextColor(text);
        title.setTextSize(18f);
        title.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1f));
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        playerView = new PlayerView(this);
        playerView.setBackgroundColor(Color.BLACK);
        playerView.setUseController(true);
        playerView.setControllerShowTimeoutMs(3_500);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setKeepScreenOn(true);
        root.addView(playerView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout statusPanel = new LinearLayout(this);
        statusPanel.setOrientation(LinearLayout.VERTICAL);
        statusPanel.setPadding(dp(16), dp(10), dp(16), dp(12));
        statusPanel.setBackgroundColor(surface);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusText = new TextView(this);
        statusText.setText("Preparando player…");
        statusText.setTextColor(text);
        statusText.setTextSize(16f);
        statusRow.addView(statusText, new LinearLayout.LayoutParams(0, dp(40), 1f));

        retryButton = new Button(this);
        retryButton.setText("Tentar novamente");
        retryButton.setAllCaps(false);
        retryButton.setTextColor(text);
        retryButton.setVisibility(View.GONE);
        retryButton.setOnClickListener(view -> retryPlayback());
        statusRow.addView(retryButton, new LinearLayout.LayoutParams(dp(148), dp(40)));
        statusPanel.addView(statusRow);

        statusDetail = new TextView(this);
        statusDetail.setText("Aguardando dados do site");
        statusDetail.setTextColor(Color.parseColor("#A9BAC6"));
        statusDetail.setTextSize(13f);
        statusPanel.addView(statusDetail, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bufferProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bufferProgress.setMax(100);
        bufferProgress.setIndeterminate(true);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        progressParams.setMargins(0, dp(8), 0, 0);
        statusPanel.addView(bufferProgress, progressParams);
        root.addView(statusPanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (videoUri != null) initializePlayer();
    }

    private void initializePlayer() {
        if (player != null) return;
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .dns(hostname -> AutoDnsManager.get(this).resolveAll(hostname))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
        OkHttpDataSource.Factory httpFactory = new OkHttpDataSource.Factory(httpClient);
        if (userAgent != null && !userAgent.isEmpty()) httpFactory.setUserAgent(userAgent);

        Map<String, String> headers = new HashMap<>();
        if (referer != null && !referer.isEmpty()) headers.put("Referer", referer);
        if (cookie != null && !cookie.isEmpty()) headers.put("Cookie", cookie);
        if (!headers.isEmpty()) httpFactory.setDefaultRequestProperties(headers);

        DataSource.Factory dataSourceFactory = VideoCache.get(this).dataSourceFactory(httpFactory);
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 60_000, 1_500, 3_000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSourceFactory))
                .setLoadControl(loadControl)
                .build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                handlePlaybackState(playbackState);
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) {
                    hasPlayed = true;
                    showReadyState("Reproduzindo");
                } else if (player != null && player.getPlaybackState() == Player.STATE_READY) {
                    showReadyState("Pausado");
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                showPlaybackError(error);
            }
        });
        playerView.setPlayer(player);
        player.setMediaItem(buildMediaItem(videoUri));
        if (resumePositionMs > 0L) player.seekTo(resumePositionMs);
        player.prepare();
        player.play();
    }

    private MediaItem buildMediaItem(Uri uri) {
        MediaItem.Builder builder = new MediaItem.Builder().setUri(uri);
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        if (path.endsWith(".m3u8")) builder.setMimeType(MimeTypes.APPLICATION_M3U8);
        else if (path.endsWith(".mpd")) builder.setMimeType(MimeTypes.APPLICATION_MPD);
        return builder.build();
    }

    private void handlePlaybackState(int playbackState) {
        if (playbackState == Player.STATE_BUFFERING) {
            beginBuffering();
        } else {
            stopBufferingTicker();
            if (playbackState == Player.STATE_IDLE) {
                setStatus("Preparando player…", "Aguardando o servidor do site", false, false);
            } else if (playbackState == Player.STATE_READY && player != null) {
                showReadyState(player.isPlaying() ? "Reproduzindo" : "Pronto para reproduzir");
            } else if (playbackState == Player.STATE_ENDED) {
                setStatus("Vídeo finalizado", "Toque em reproduzir para assistir novamente", false, false);
                setBufferProgress(100);
            }
        }
    }

    private void beginBuffering() {
        if (!buffering) {
            buffering = true;
            bufferingStartedAtMs = SystemClock.elapsedRealtime();
            handler.removeCallbacks(bufferingTicker);
            handler.post(bufferingTicker);
        }
    }

    private void updateBufferingStatus() {
        if (player == null) return;
        long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - bufferingStartedAtMs);
        int percentage = player.getBufferedPercentage();
        PlaybackStatusPolicy.Status status = PlaybackStatusPolicy.bufferingStatus(
                isOnline(), hasPlayed, elapsed);
        String detail;
        if (status == PlaybackStatusPolicy.Status.OFFLINE) {
            detail = "Verifique o Wi-Fi ou os dados móveis e tente novamente";
        } else if (status == PlaybackStatusPolicy.Status.SLOW_SERVER) {
            detail = "O site está demorando a enviar dados • buffer " + percentage
                    + "% • esperando " + Math.max(1L, elapsed / 1_000L) + " s";
        } else {
            detail = "Buffer " + percentage + "% • aguardando "
                    + Math.max(1L, elapsed / 1_000L) + " s";
        }
        boolean retry = status == PlaybackStatusPolicy.Status.OFFLINE
                || status == PlaybackStatusPolicy.Status.SLOW_SERVER;
        setStatus(PlaybackStatusPolicy.message(status), detail, retry,
                status == PlaybackStatusPolicy.Status.SLOW_SERVER
                        || status == PlaybackStatusPolicy.Status.OFFLINE);
        setBufferProgress(percentage);
    }

    private void showReadyState(String message) {
        stopBufferingTicker();
        int percentage = player == null ? 0 : player.getBufferedPercentage();
        String readyMessage = !isOnline() && hasPlayed ? "Reproduzindo do cache offline" : message;
        setStatus(readyMessage, "Buffer " + percentage + "% • cache local "
                + formatCacheSize(VideoCache.get(this).sizeBytes()), false, false);
        setBufferProgress(percentage);
    }

    private void showPlaybackError(PlaybackException error) {
        stopBufferingTicker();
        String message = isOnline() ? "Erro ao carregar o vídeo" : "Sem conexão com a internet";
        String detail = isOnline()
                ? "O servidor recusou ou interrompeu o vídeo • " + error.getErrorCodeName()
                : "Os trechos já carregados ficam disponíveis; avance somente dentro do cache";
        setStatus(message, detail, true, true);
        bufferProgress.setIndeterminate(false);
        bufferProgress.setProgress(0);
    }

    private void retryPlayback() {
        if (player == null) {
            initializePlayer();
            return;
        }
        bufferingStartedAtMs = SystemClock.elapsedRealtime();
        retryButton.setVisibility(View.GONE);
        setStatus("Tentando novamente…", "Reconectando ao servidor do site", false, false);
        player.prepare();
        player.play();
    }

    private void setStatus(String title, String detail, boolean showRetry, boolean warning) {
        statusText.setText(title);
        statusText.setTextColor(Color.parseColor(warning ? "#FFB86B" : "#F3F7FA"));
        statusDetail.setText(detail);
        retryButton.setVisibility(showRetry ? View.VISIBLE : View.GONE);
    }

    private void setBufferProgress(int percentage) {
        int safePercentage = Math.max(0, Math.min(100, percentage));
        bufferProgress.setIndeterminate(safePercentage == 0 && buffering);
        if (!bufferProgress.isIndeterminate()) bufferProgress.setProgress(safePercentage);
    }

    private void stopBufferingTicker() {
        buffering = false;
        handler.removeCallbacks(bufferingTicker);
    }

    private boolean isOnline() {
        ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (manager == null) return false;
        Network network = manager.getActiveNetwork();
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private static String cleanHeader(String value) {
        if (value == null) return null;
        return value.replace("\r", "").replace("\n", "").trim();
    }

    private static String formatCacheSize(long bytes) {
        if (bytes < 1_024L * 1_024L) return Math.max(0L, bytes / 1_024L) + " KB";
        return (bytes / (1_024L * 1_024L)) + " MB";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (player != null) outState.putLong(STATE_POSITION, player.getCurrentPosition());
        else outState.putLong(STATE_POSITION, resumePositionMs);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onStop() {
        stopBufferingTicker();
        if (player != null) {
            resumePositionMs = player.getCurrentPosition();
            playerView.setPlayer(null);
            player.release();
            player = null;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
