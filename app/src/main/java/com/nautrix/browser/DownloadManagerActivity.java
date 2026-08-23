package com.nautrix.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Unified UI for direct Android downloads and the built-in libtorrent session. */
public final class DownloadManagerActivity extends Activity {
    private static final int PICK_TORRENT = 4301;
    private static final int BACKGROUND = Color.rgb(11, 17, 23);
    private static final int SURFACE = Color.rgb(19, 28, 36);
    private static final int TEXT = Color.rgb(243, 247, 250);
    private static final int MUTED = Color.rgb(143, 163, 179);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = this::render;
    private LinearLayout content;
    private DownloadManager systemManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        systemManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        buildInterface();
        TorrentService.wake(this);
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(8), dp(6), dp(8), dp(6));
        toolbar.setBackgroundColor(SURFACE);
        Button back = button("‹");
        back.setContentDescription("Voltar");
        back.setOnClickListener(view -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = text("Downloads", 21, TEXT);
        title.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1f));
        Button settings = button("⚙");
        settings.setContentDescription("Configurações de downloads do Android");
        settings.setOnClickListener(view -> openSystemDownloadSettings());
        toolbar.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(toolbar);

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(dp(8), dp(8), dp(8), dp(4));
        Button magnet = button("Adicionar magnet");
        magnet.setOnClickListener(view -> showMagnetDialog());
        actions.addView(magnet, new LinearLayout.LayoutParams(0, dp(48), 1f));
        Button torrent = button("Abrir .torrent");
        torrent.setOnClickListener(view -> pickTorrentFile());
        LinearLayout.LayoutParams torrentParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        torrentParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(torrent, torrentParams);
        root.addView(actions);

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(4), dp(12), dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private void render() {
        if (isFinishing() || isDestroyed()) return;
        content.removeAllViews();
        renderDirectDownloads();
        renderTorrents();
        renderTorrentFiles();
        handler.removeCallbacks(refresher);
        handler.postDelayed(refresher, 1500L);
    }

    private void renderDirectDownloads() {
        content.addView(sectionTitle("Downloads diretos"));
        List<DownloadRegistry.Entry> entries = DownloadRegistry.list(this);
        if (entries.isEmpty()) {
            content.addView(helper("Arquivos baixados em páginas aparecerão aqui."));
            return;
        }
        for (DownloadRegistry.Entry entry : entries) renderDirectEntry(entry);
    }

    private void renderDirectEntry(DownloadRegistry.Entry entry) {
        LinearLayout card = card();
        card.addView(text(entry.name, 16, TEXT));
        int status = -1;
        long downloaded = 0L;
        long total = -1L;
        String reason = "";
        if (systemManager != null) {
            try (Cursor cursor = systemManager.query(
                    new DownloadManager.Query().setFilterById(entry.id))) {
                if (cursor != null && cursor.moveToFirst()) {
                    status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    total = cursor.getLong(cursor.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    reason = String.valueOf(cursor.getInt(cursor.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_REASON)));
                }
            } catch (Exception ignored) {
            }
        }
        int percent = total > 0 ? (int) Math.min(100L, downloaded * 100L / total) : 0;
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(percent);
        progress.setIndeterminate(total <= 0 && (status == DownloadManager.STATUS_RUNNING
                || status == DownloadManager.STATUS_PENDING));
        card.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(10)));
        card.addView(helper(directStatus(status, percent, downloaded, total, reason)));

        LinearLayout controls = new LinearLayout(this);
        if (status == DownloadManager.STATUS_SUCCESSFUL) {
            Button open = smallButton("Abrir");
            open.setOnClickListener(view -> openDirectDownload(entry));
            controls.addView(open);
        } else if (status == DownloadManager.STATUS_FAILED || status == -1) {
            Button retry = smallButton("Tentar novamente");
            retry.setOnClickListener(view -> retryDirectDownload(entry));
            controls.addView(retry);
        } else {
            Button cancel = smallButton("Cancelar");
            cancel.setOnClickListener(view -> cancelDirectDownload(entry));
            controls.addView(cancel);
        }
        Button forget = smallButton("Remover da lista");
        forget.setOnClickListener(view -> {
            DownloadRegistry.remove(this, entry.id);
            render();
        });
        controls.addView(forget);
        card.addView(controls);
        content.addView(card, cardParams());
    }

    private void renderTorrents() {
        content.addView(sectionTitle("Torrents"));
        List<TorrentService.Snapshot> torrents = TorrentService.snapshots();
        content.addView(helper(TorrentService.message()
                + "\nArquivos: " + TorrentService.downloadsDirectory(this).getAbsolutePath()));
        if (torrents.isEmpty()) {
            content.addView(helper("Cole um magnet ou escolha um arquivo .torrent. "
                    + "Use somente conteúdo que você tem autorização para baixar."));
            return;
        }
        for (TorrentService.Snapshot torrent : torrents) {
            LinearLayout card = card();
            card.addView(text(torrent.name, 16, TEXT));
            ProgressBar progress = new ProgressBar(this, null,
                    android.R.attr.progressBarStyleHorizontal);
            progress.setMax(1000);
            progress.setProgress((int) (torrent.progress * 1000f));
            card.addView(progress, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(10)));
            String state = torrent.finished ? "Concluído" : torrent.paused ? "Pausado" : "Baixando";
            card.addView(helper(String.format(Locale.ROOT,
                    "%s • %.1f%% • %s de %s\n↓ %s • ↑ %s • %d peers",
                    state, torrent.progress * 100f, size(torrent.downloaded), size(torrent.total),
                    rate(torrent.downloadRate), rate(torrent.uploadRate), torrent.peers)));
            LinearLayout controls = new LinearLayout(this);
            Button toggle = smallButton(torrent.paused ? "Retomar" : "Pausar");
            toggle.setOnClickListener(view -> {
                if (torrent.paused) TorrentService.resume(this, torrent.index);
                else TorrentService.pause(this, torrent.index);
                handler.postDelayed(refresher, 400L);
            });
            controls.addView(toggle);
            Button remove = smallButton("Remover");
            remove.setOnClickListener(view -> confirmRemoveTorrent(torrent));
            controls.addView(remove);
            card.addView(controls);
            content.addView(card, cardParams());
        }
    }

    private void renderTorrentFiles() {
        content.addView(sectionTitle("Arquivos de torrents"));
        ArrayList<File> files = new ArrayList<>();
        collectFiles(TorrentService.downloadsDirectory(this), files, 60);
        if (files.isEmpty()) {
            content.addView(helper("Os arquivos concluídos ou parciais aparecerão aqui."));
            return;
        }
        for (File file : files) {
            Button open = button(file.getName() + "  •  " + size(file.length()));
            open.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            open.setOnClickListener(view -> openTorrentFile(file));
            content.addView(open, cardParams());
        }
    }

    private void showMagnetDialog() {
        EditText input = new EditText(this);
        input.setHint("magnet:?xt=urn:btih:…");
        input.setSingleLine(false);
        input.setMinLines(2);
        input.setPadding(dp(16), dp(8), dp(16), dp(8));
        new AlertDialog.Builder(this)
                .setTitle("Adicionar magnet")
                .setMessage("Baixe apenas arquivos que você tem autorização para usar.")
                .setView(input)
                .setPositiveButton("Adicionar", (dialog, which) -> {
                    try {
                        TorrentService.addMagnet(this, input.getText().toString());
                        Toast.makeText(this, "Magnet adicionado", Toast.LENGTH_SHORT).show();
                    } catch (Exception error) {
                        Toast.makeText(this, "Magnet inválido", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void pickTorrentFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/x-bittorrent")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, PICK_TORRENT);
        } catch (Exception error) {
            intent.setType("*/*");
            startActivityForResult(intent, PICK_TORRENT);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_TORRENT || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        TorrentService.addTorrentFile(this, uri);
        Toast.makeText(this, "Arquivo .torrent adicionado", Toast.LENGTH_SHORT).show();
    }

    private void confirmRemoveTorrent(TorrentService.Snapshot torrent) {
        new AlertDialog.Builder(this)
                .setTitle("Remover torrent?")
                .setMessage("A tarefa será removida, mas os arquivos já baixados serão preservados.")
                .setPositiveButton("Remover", (dialog, which) -> {
                    TorrentService.remove(this, torrent.index);
                    handler.postDelayed(refresher, 400L);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void cancelDirectDownload(DownloadRegistry.Entry entry) {
        if (systemManager != null) systemManager.remove(entry.id);
        DownloadRegistry.remove(this, entry.id);
        render();
    }

    private void retryDirectDownload(DownloadRegistry.Entry entry) {
        try {
            if (systemManager != null) systemManager.remove(entry.id);
            DownloadRegistry.remove(this, entry.id);
            DownloadRegistry.retry(this, entry);
            Toast.makeText(this, "Download reiniciado", Toast.LENGTH_SHORT).show();
            render();
        } catch (Exception error) {
            Toast.makeText(this, "Não foi possível reiniciar", Toast.LENGTH_LONG).show();
        }
    }

    private void openDirectDownload(DownloadRegistry.Entry entry) {
        if (systemManager == null) return;
        Uri uri = systemManager.getUriForDownloadedFile(entry.id);
        if (uri == null) {
            Toast.makeText(this, "Arquivo não encontrado", Toast.LENGTH_LONG).show();
            return;
        }
        String mime = systemManager.getMimeTypeForDownloadedFile(entry.id);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri,
                mime == null ? "*/*" : mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(view, "Abrir download"));
        } catch (Exception error) {
            Toast.makeText(this, "Nenhum aplicativo abre este arquivo", Toast.LENGTH_LONG).show();
        }
    }

    private void openTorrentFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
            String mime = URLConnection.guessContentTypeFromName(file.getName());
            Intent view = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime == null ? "*/*" : mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(view, "Abrir arquivo"));
        } catch (Exception error) {
            Toast.makeText(this, "Este arquivo ainda não pode ser aberto", Toast.LENGTH_LONG).show();
        }
    }

    private void openSystemDownloadSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {
        }
    }

    private static String directStatus(int status, int percent, long done, long total, String reason) {
        String amount = total > 0 ? size(done) + " de " + size(total) : size(done);
        if (status == DownloadManager.STATUS_SUCCESSFUL) return "Concluído • " + amount;
        if (status == DownloadManager.STATUS_FAILED) return "Falhou (código " + reason + ")";
        if (status == DownloadManager.STATUS_PAUSED) return "Pausado pelo sistema • " + percent + "%";
        if (status == DownloadManager.STATUS_PENDING) return "Aguardando • " + amount;
        if (status == DownloadManager.STATUS_RUNNING) return "Baixando • " + percent + "% • " + amount;
        return "Tarefa não encontrada no Android";
    }

    private static void collectFiles(File directory, List<File> result, int maximum) {
        if (directory == null || !directory.isDirectory() || result.size() >= maximum) return;
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (result.size() >= maximum) break;
            if (child.isDirectory()) collectFiles(child, result, maximum);
            else result.add(child);
        }
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackgroundColor(SURFACE);
        return card;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(5), 0, dp(5));
        return params;
    }

    private TextView sectionTitle(String value) {
        TextView title = text(value, 19, TEXT);
        title.setPadding(0, dp(16), 0, dp(6));
        return title;
    }

    private TextView helper(String value) {
        TextView helper = text(value, 13, MUTED);
        helper.setPadding(0, dp(5), 0, dp(5));
        return helper;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(TEXT);
        button.setAllCaps(false);
        button.setBackgroundColor(SURFACE);
        return button;
    }

    private Button smallButton(String label) {
        Button button = button(label);
        button.setTextSize(12);
        button.setMinHeight(dp(40));
        button.setMinimumHeight(dp(40));
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String size(long bytes) {
        if (bytes < 0) return "?";
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824f);
        }
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576f);
        }
        if (bytes >= 1024L) return String.format(Locale.ROOT, "%.0f KB", bytes / 1024f);
        return bytes + " B";
    }

    private static String rate(long bytes) {
        return size(bytes) + "/s";
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresher);
        super.onPause();
    }
}
