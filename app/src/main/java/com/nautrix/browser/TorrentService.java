package com.nautrix.browser;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;

import com.frostwire.jlibtorrent.SessionManager;
import com.frostwire.jlibtorrent.Sha1Hash;
import com.frostwire.jlibtorrent.Sha256Hash;
import com.frostwire.jlibtorrent.TorrentHandle;
import com.frostwire.jlibtorrent.TorrentInfo;
import com.frostwire.jlibtorrent.TorrentStatus;
import com.frostwire.jlibtorrent.swig.torrent_flags_t;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

/** Foreground libtorrent session used by the built-in download manager. */
public final class TorrentService extends Service {
    private static final String CHANNEL_ID = "nautrix_torrents";
    private static final int NOTIFICATION_ID = 7201;
    private static final String PREFS = "nautrix_torrents";
    private static final String KEY_SOURCES = "sources";
    private static final int MAX_TORRENT_FILE_BYTES = 10 * 1024 * 1024;

    private static final String ACTION_ADD_MAGNET = "com.nautrix.browser.ADD_MAGNET";
    private static final String ACTION_ADD_FILE = "com.nautrix.browser.ADD_TORRENT_FILE";
    private static final String ACTION_ADD_URL = "com.nautrix.browser.ADD_TORRENT_URL";
    private static final String ACTION_PAUSE = "com.nautrix.browser.PAUSE_TORRENT";
    private static final String ACTION_RESUME = "com.nautrix.browser.RESUME_TORRENT";
    private static final String ACTION_REMOVE = "com.nautrix.browser.REMOVE_TORRENT";
    private static final String EXTRA_SOURCE = "source";
    private static final String EXTRA_KEY = "torrent_key";
    private static final String EXTRA_USER_AGENT = "user_agent";
    private static final String EXTRA_COOKIE = "cookie";
    private static final String EXTRA_REFERER = "referer";

    private static volatile List<Snapshot> latest = Collections.emptyList();
    private static volatile String serviceMessage = "Nenhum torrent ativo";

    private final Object sourceLock = new Object();
    private final ArrayList<String> sources = new ArrayList<>();
    private final HashSet<String> pausedKeys = new HashSet<>();
    private ScheduledExecutorService worker;
    private SessionManager session;
    private boolean restored;

    public static void addMagnet(Context context, String magnet) {
        if (magnet == null || !magnet.toLowerCase(Locale.ROOT).startsWith("magnet:?")) {
            throw new IllegalArgumentException("Magnet inválido");
        }
        start(context, new Intent(context, TorrentService.class)
                .setAction(ACTION_ADD_MAGNET).putExtra(EXTRA_SOURCE, magnet.trim()));
    }

    public static void wake(Context context) {
        start(context, new Intent(context, TorrentService.class));
    }

    public static void addTorrentFile(Context context, Uri uri) {
        Intent intent = new Intent(context, TorrentService.class)
                .setAction(ACTION_ADD_FILE).putExtra(EXTRA_SOURCE, uri.toString())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        start(context, intent);
    }

    public static void addTorrentUrl(Context context, String url, String userAgent,
                                     String cookie, String referer) {
        Intent intent = new Intent(context, TorrentService.class)
                .setAction(ACTION_ADD_URL)
                .putExtra(EXTRA_SOURCE, url)
                .putExtra(EXTRA_USER_AGENT, userAgent)
                .putExtra(EXTRA_COOKIE, cookie)
                .putExtra(EXTRA_REFERER, referer);
        start(context, intent);
    }

    public static void pause(Context context, String key) {
        command(context, ACTION_PAUSE, key);
    }

    public static void resume(Context context, String key) {
        command(context, ACTION_RESUME, key);
    }

    public static void remove(Context context, String key) {
        command(context, ACTION_REMOVE, key);
    }

    public static List<Snapshot> snapshots() {
        return latest;
    }

    public static String message() {
        return serviceMessage;
    }

    public static File downloadsDirectory(Context context) {
        File base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (base == null) base = new File(context.getFilesDir(), "downloads");
        File directory = new File(base, "Nautrix/Torrents");
        if (!directory.exists()) directory.mkdirs();
        return directory;
    }

    private static void command(Context context, String action, String key) {
        if (key == null || key.isEmpty()) return;
        start(context, new Intent(context, TorrentService.class)
                .setAction(action).putExtra(EXTRA_KEY, key));
    }

    private static void start(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "NautrixTorrent");
            thread.setDaemon(true);
            return thread;
        });
        worker.scheduleAtFixedRate(this::refresh, 1, 2, TimeUnit.SECONDS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification("Preparando torrents…"));
        worker.execute(() -> handleIntent(intent));
        return START_STICKY;
    }

    private void handleIntent(Intent intent) {
        try {
            ensureSession();
            if (intent == null || intent.getAction() == null) return;
            String action = intent.getAction();
            if (ACTION_ADD_MAGNET.equals(action)) {
                addSource(intent.getStringExtra(EXTRA_SOURCE));
            } else if (ACTION_ADD_FILE.equals(action)) {
                Uri uri = Uri.parse(intent.getStringExtra(EXTRA_SOURCE));
                addSource(copyTorrent(uri).getAbsolutePath());
            } else if (ACTION_ADD_URL.equals(action)) {
                String url = intent.getStringExtra(EXTRA_SOURCE);
                File file = fetchTorrent(url, intent.getStringExtra(EXTRA_USER_AGENT),
                        intent.getStringExtra(EXTRA_COOKIE), intent.getStringExtra(EXTRA_REFERER));
                addSource(file.getAbsolutePath());
            } else {
                control(action, intent.getStringExtra(EXTRA_KEY));
            }
            refresh();
        } catch (Exception error) {
            String detail = error.getMessage();
            serviceMessage = "Falha no torrent" + (detail == null ? "" : ": " + detail);
            notifyStatus(serviceMessage);
        }
    }

    private void ensureSession() {
        if (session == null) {
            session = new SessionManager();
            session.start();
        }
        if (!restored) {
            restored = true;
            synchronized (sourceLock) {
                sources.clear();
                sources.addAll(loadSources());
                for (String source : sources) downloadSource(source);
            }
        }
    }

    private void addSource(String source) {
        if (source == null || source.trim().isEmpty()) throw new IllegalArgumentException("Fonte vazia");
        source = source.trim();
        synchronized (sourceLock) {
            if (sources.contains(source)) {
                serviceMessage = "Este torrent já está na lista";
                return;
            }
            downloadSource(source);
            sources.add(source);
            saveSources();
        }
        serviceMessage = "Torrent adicionado";
    }

    private void downloadSource(String source) {
        File destination = downloadsDirectory(this);
        if (source.toLowerCase(Locale.ROOT).startsWith("magnet:?")) {
            session.download(source, destination, new torrent_flags_t());
        } else {
            File torrentFile = new File(source);
            if (!torrentFile.isFile()) return;
            session.download(new TorrentInfo(torrentFile), destination);
        }
    }

    /** Controls a torrent by its info-hash, never by session array position. */
    private void control(String action, String key) {
        TorrentHandle handle = findHandle(key);
        if (handle == null) return;
        if (ACTION_PAUSE.equals(action)) {
            handle.pause();
            pausedKeys.add(key);
            serviceMessage = "Torrent pausado";
        } else if (ACTION_RESUME.equals(action)) {
            handle.resume();
            pausedKeys.remove(key);
            serviceMessage = "Torrent retomado";
        } else if (ACTION_REMOVE.equals(action)) {
            String stableKey = stableHandleKey(handle);
            synchronized (sourceLock) {
                removePersistedSource(stableKey);
                saveSources();
            }
            pausedKeys.remove(stableKey);
            pausedKeys.remove(key);
            session.remove(handle);
            serviceMessage = "Torrent removido; os arquivos baixados foram preservados";
        }
    }

    private TorrentHandle findHandle(String key) {
        if (key == null || key.isEmpty()) return null;
        TorrentHandle[] handles = session.getTorrentHandles();
        for (TorrentHandle handle : handles) {
            if (key.equals(stableHandleKey(handle))) return handle;
        }
        return null;
    }

    private static String stableHandleKey(TorrentHandle handle) {
        if (handle == null) return "";
        try {
            TorrentStatus status = handle.status();
            String v2 = hashKey("v2:", status.infoHashV2());
            if (v2 != null) return v2;
            String v1 = hashKey("v1:", status.infoHashV1());
            return v1 == null ? "" : v1;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private void removePersistedSource(String key) {
        if (key == null || key.isEmpty()) return;
        for (int index = 0; index < sources.size(); index++) {
            if (key.equals(stableSourceKey(sources.get(index)))) {
                sources.remove(index);
                return;
            }
        }
    }

    private static String stableSourceKey(String source) {
        if (source == null || source.isEmpty()) return "";
        try {
            if (source.toLowerCase(Locale.ROOT).startsWith("magnet:?")) {
                return stableMagnetKey(source);
            }
            File file = new File(source);
            if (!file.isFile()) return "";
            TorrentInfo info = new TorrentInfo(file);
            String v2 = hashKey("v2:", info.infoHashV2());
            if (v2 != null) return v2;
            String v1 = hashKey("v1:", info.infoHashV1());
            return v1 == null ? "" : v1;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String stableMagnetKey(String magnet) {
        String encodedQuery = Uri.parse(magnet).getEncodedQuery();
        if (encodedQuery == null || encodedQuery.isEmpty()) return "";
        String v1 = null;
        String v2 = null;
        for (String part : encodedQuery.split("&")) {
            int equals = part.indexOf('=');
            if (equals <= 0) continue;
            String name = Uri.decode(part.substring(0, equals));
            if (!"xt".equalsIgnoreCase(name)) continue;
            String value = Uri.decode(part.substring(equals + 1)).trim();
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.startsWith("urn:btmh:1220")) {
                String hex = lower.substring("urn:btmh:1220".length());
                if (hex.matches("[0-9a-f]{64}")) v2 = "v2:" + hex;
            } else if (lower.startsWith("urn:btih:")) {
                String encodedHash = value.substring("urn:btih:".length());
                if (encodedHash.matches("(?i)[0-9a-f]{40}")) {
                    v1 = "v1:" + encodedHash.toLowerCase(Locale.ROOT);
                } else if (encodedHash.matches("(?i)[a-z2-7]{32}")) {
                    String hex = base32BtihToHex(encodedHash);
                    if (hex != null) v1 = "v1:" + hex;
                }
            }
        }
        return v2 != null ? v2 : v1 == null ? "" : v1;
    }

    private static String hashKey(String prefix, Sha1Hash hash) {
        if (hash == null) return null;
        return normalizedHashKey(prefix, hash.toHex());
    }

    private static String hashKey(String prefix, Sha256Hash hash) {
        if (hash == null) return null;
        return normalizedHashKey(prefix, hash.toHex());
    }

    private static String normalizedHashKey(String prefix, String hex) {
        if (hex == null || hex.isEmpty()) return null;
        String lower = hex.toLowerCase(Locale.ROOT);
        if (lower.matches("0+")) return null;
        return prefix + lower;
    }

    private static String base32BtihToHex(String encoded) {
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        ByteArrayOutputStream output = new ByteArrayOutputStream(20);
        int buffer = 0;
        int bits = 0;
        for (int index = 0; index < encoded.length(); index++) {
            int value = alphabet.indexOf(Character.toUpperCase(encoded.charAt(index)));
            if (value < 0) return null;
            buffer = (buffer << 5) | value;
            bits += 5;
            while (bits >= 8) {
                bits -= 8;
                output.write((buffer >> bits) & 0xff);
                buffer &= bits == 0 ? 0 : (1 << bits) - 1;
            }
        }
        byte[] bytes = output.toByteArray();
        if (bytes.length != 20) return null;
        StringBuilder hex = new StringBuilder(40);
        for (byte value : bytes) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return hex.toString();
    }

    private File copyTorrent(Uri uri) throws Exception {
        File metadata = metadataDirectory();
        File target = new File(metadata, "torrent-" + System.currentTimeMillis() + ".torrent");
        try (InputStream input = getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(target)) {
            if (input == null) throw new IllegalArgumentException("Arquivo inacessível");
            copyLimited(input, output);
        }
        return target;
    }

    private File fetchTorrent(String source, String userAgent, String cookie, String referer)
            throws Exception {
        Uri uri = Uri.parse(source);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("O .torrent remoto precisa usar HTTPS");
        }
        HttpsURLConnection connection = (HttpsURLConnection) new URL(source).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(20_000);
        connection.setInstanceFollowRedirects(true);
        if (userAgent != null) connection.setRequestProperty("User-Agent", userAgent);
        if (cookie != null) connection.setRequestProperty("Cookie", cookie);
        if (referer != null && referer.startsWith("https://")) {
            connection.setRequestProperty("Referer", referer);
        }
        connection.connect();
        if (connection.getResponseCode() / 100 != 2) {
            throw new IllegalArgumentException("Servidor respondeu " + connection.getResponseCode());
        }
        if (!"https".equalsIgnoreCase(connection.getURL().getProtocol())) {
            throw new IllegalArgumentException("Redirecionamento inseguro bloqueado");
        }
        File target = new File(metadataDirectory(),
                "torrent-" + System.currentTimeMillis() + ".torrent");
        try (InputStream input = connection.getInputStream();
             FileOutputStream output = new FileOutputStream(target)) {
            copyLimited(input, output);
        } finally {
            connection.disconnect();
        }
        return target;
    }

    private static void copyLimited(InputStream input, FileOutputStream output) throws Exception {
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > MAX_TORRENT_FILE_BYTES) {
                throw new IllegalArgumentException("Arquivo .torrent maior que 10 MB");
            }
            output.write(buffer, 0, read);
        }
    }

    private File metadataDirectory() {
        File directory = new File(getFilesDir(), "torrent-metadata");
        if (!directory.exists()) directory.mkdirs();
        return directory;
    }

    private List<String> loadSources() {
        ArrayList<String> result = new ArrayList<>();
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_SOURCES, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int index = 0; index < array.length(); index++) {
                String source = array.optString(index, "");
                if (!source.isEmpty()) result.add(source);
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private void saveSources() {
        JSONArray array = new JSONArray();
        for (String source : sources) array.put(source);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_SOURCES, array.toString()).apply();
    }

    private void refresh() {
        SessionManager current = session;
        if (current == null || !current.isRunning()) return;
        try {
            TorrentHandle[] handles = current.getTorrentHandles();
            ArrayList<Snapshot> snapshots = new ArrayList<>();
            long totalRate = 0L;
            for (TorrentHandle handle : handles) {
                TorrentStatus status = handle.status();
                String name = status.name();
                if (name == null || name.trim().isEmpty()) name = "Obtendo metadados…";
                long rate = Math.max(0L, status.downloadRate());
                totalRate += rate;
                String key = stableHandleKey(handle);
                snapshots.add(new Snapshot(key, name, Math.max(0f, Math.min(1f, status.progress())),
                        rate, Math.max(0L, status.uploadRate()), status.numPeers(),
                        Math.max(0L, status.totalDone()), Math.max(0L, status.totalWanted()),
                        !key.isEmpty() && pausedKeys.contains(key), status.isFinished(),
                        String.valueOf(status.state())));
            }
            latest = Collections.unmodifiableList(snapshots);
            if (snapshots.isEmpty()) serviceMessage = "Nenhum torrent ativo";
            else serviceMessage = snapshots.size() + " torrent(s) • " + formatRate(totalRate);
            notifyStatus(serviceMessage);
        } catch (Throwable error) {
            serviceMessage = "Atualizando estado dos torrents…";
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                    "Downloads torrent", NotificationManager.IMPORTANCE_LOW));
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, DownloadManagerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Nautrix • Torrents")
                .setContentText(text)
                .setContentIntent(pending)
                .setOngoing(!latest.isEmpty())
                .setOnlyAlertOnce(true)
                .build();
    }

    private void notifyStatus(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private static String formatRate(long bytesPerSecond) {
        if (bytesPerSecond >= 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f MB/s", bytesPerSecond / 1048576f);
        }
        return String.format(Locale.ROOT, "%.0f KB/s", bytesPerSecond / 1024f);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        serviceMessage = "Torrent pausado pelo limite de segundo plano do Android";
        stopForeground(STOP_FOREGROUND_DETACH);
        stopSelf(startId);
    }

    @Override
    public void onDestroy() {
        ScheduledExecutorService executor = worker;
        if (executor != null) executor.shutdownNow();
        SessionManager current = session;
        session = null;
        if (current != null) new Thread(current::stop, "NautrixTorrentStop").start();
        latest = Collections.emptyList();
        super.onDestroy();
    }

    public static final class Snapshot {
        public final String key;
        public final String name;
        public final float progress;
        public final long downloadRate;
        public final long uploadRate;
        public final int peers;
        public final long downloaded;
        public final long total;
        public final boolean paused;
        public final boolean finished;
        public final String state;

        Snapshot(String key, String name, float progress, long downloadRate, long uploadRate,
                 int peers, long downloaded, long total, boolean paused, boolean finished,
                 String state) {
            this.key = key;
            this.name = name;
            this.progress = progress;
            this.downloadRate = downloadRate;
            this.uploadRate = uploadRate;
            this.peers = peers;
            this.downloaded = downloaded;
            this.total = total;
            this.paused = paused;
            this.finished = finished;
            this.state = state;
        }
    }
}
