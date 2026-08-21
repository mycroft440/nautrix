package org.chromium.chrome.browser.nautrix.torrent;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lightweight native manager for Nautrix BitTorrent transfers. */
public final class NautrixTorrentManagerActivity extends Activity {
    private static final int PICK_TORRENT = 0x4E54;
    private static final long REFRESH_MS = 1500L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "NautrixTorrentManager");
        thread.setDaemon(true);
        return thread;
    });

    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            refresh();
            main.postDelayed(this, REFRESH_MS);
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (service instanceof NautrixTorrentService.LocalBinder) {
                binder = (NautrixTorrentService.LocalBinder) service;
                connected = true;
                updatePolicyControls();
                refresh();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            connected = false;
            binder = null;
            renderMessage("Serviço de torrents desconectado.");
        }
    };

    private NautrixTorrentService.LocalBinder binder;
    private LinearLayout list;
    private Switch wifiOnly;
    private Switch seeding;
    private boolean connected;
    private boolean suppressSwitchCallbacks;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        NautrixTorrentService.ensureRunning(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, NautrixTorrentService.class), connection, Context.BIND_AUTO_CREATE);
        main.removeCallbacks(refreshTask);
        main.post(refreshTask);
    }

    @Override
    protected void onStop() {
        main.removeCallbacks(refreshTask);
        if (connected) {
            unbindService(connection);
            connected = false;
            binder = null;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_TORRENT || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        worker.execute(() -> {
            File local = NautrixTorrentEntryActivity.copyTorrentMetadata(this, uri);
            boolean ok = local != null
                    && NautrixTorrentService.enqueueTorrentFile(this, local.getAbsolutePath());
            main.post(() -> toast(ok ? "Torrent adicionado" : "Não foi possível importar o torrent"));
        });
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = vertical();
        root.setPadding(dp(16), dp(18), dp(16), dp(24));
        scroll.addView(root, matchWrap());

        TextView title = text("Torrents", 26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        LinearLayout addRow = horizontal();
        addRow.setPadding(0, dp(12), 0, dp(10));
        addRow.addView(button("+ Magnet", v -> showMagnetDialog()), weighted());
        addRow.addView(button("+ .torrent", v -> pickTorrent()), weighted());
        addRow.addView(button("Limites", v -> showLimitsDialog()), weighted());
        root.addView(addRow, matchWrap());

        wifiOnly = new Switch(this);
        wifiOnly.setText("Somente Wi‑Fi");
        wifiOnly.setOnCheckedChangeListener((v, checked) -> {
            if (suppressSwitchCallbacks || binder == null) return;
            binder.setWifiOnly(checked);
            refresh();
        });
        root.addView(wifiOnly);

        seeding = new Switch(this);
        seeding.setText("Continuar enviando após concluir (seeding)");
        seeding.setOnCheckedChangeListener((v, checked) -> {
            if (suppressSwitchCallbacks || binder == null) return;
            binder.setSeedingEnabled(checked);
            refresh();
        });
        root.addView(seeding);

        TextView hint = text(
                "Torrents concluídos param por padrão. O estado é salvo para continuar depois de reiniciar o app.",
                13);
        hint.setPadding(0, dp(4), 0, dp(14));
        root.addView(hint);

        list = vertical();
        root.addView(list, matchWrap());
        setContentView(scroll);
        renderMessage("Conectando ao serviço de torrents…");
    }

    private void refresh() {
        NautrixTorrentService.LocalBinder current = binder;
        if (current == null) return;
        worker.execute(() -> {
            try {
                String raw = current.statusJson();
                JSONArray torrents = new JSONArray(raw);
                main.post(() -> renderTorrents(torrents));
            } catch (Exception e) {
                main.post(() -> renderMessage("Falha ao atualizar o estado dos torrents."));
            }
        });
    }

    private void updatePolicyControls() {
        NautrixTorrentService.LocalBinder current = binder;
        if (current == null) return;
        suppressSwitchCallbacks = true;
        wifiOnly.setChecked(current.isWifiOnly());
        seeding.setChecked(current.isSeedingEnabled());
        suppressSwitchCallbacks = false;
    }

    private void renderTorrents(JSONArray torrents) {
        if (isFinishing() || list == null) return;
        list.removeAllViews();
        if (torrents.length() == 0) {
            renderMessage("Nenhum torrent ativo. Adicione um magnet ou arquivo .torrent.");
            return;
        }
        for (int i = 0; i < torrents.length(); i++) {
            JSONObject item = torrents.optJSONObject(i);
            if (item != null) list.addView(buildTorrentRow(item), matchWrap());
        }
    }

    private View buildTorrentRow(JSONObject torrent) {
        String id = torrent.optString("id", "");
        String name = torrent.optString("name", "Torrent");
        boolean paused = torrent.optBoolean("paused", false);
        boolean sequential = torrent.optBoolean("sequential", false);
        boolean finished = torrent.optBoolean("finished", false);
        int ppm = torrent.optInt("progressPpm", 0);

        LinearLayout card = vertical();
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams cardParams = matchWrap();
        cardParams.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(cardParams);
        card.setBackgroundResource(android.R.drawable.dialog_holo_dark_frame);

        TextView nameView = text(name.isBlank() ? "Torrent #" + id : name, 17);
        nameView.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(nameView);

        String state = finished ? "Concluído" : paused ? "Pausado" : "Baixando";
        String details = String.format(Locale.ROOT,
                "%s • %.1f%% • ↓ %s • ↑ %s\n%d peers • %d seeds",
                state,
                ppm / 10_000.0,
                humanSpeed(torrent.optLong("downloadRate", 0)),
                humanSpeed(torrent.optLong("uploadRate", 0)),
                torrent.optInt("peers", 0),
                torrent.optInt("seeds", 0));
        TextView detail = text(details, 13);
        detail.setPadding(0, dp(4), 0, dp(8));
        card.addView(detail);

        LinearLayout controls = horizontal();
        controls.addView(button(paused ? "Retomar" : "Pausar", v -> runAction(() -> {
            NautrixTorrentService.LocalBinder b = binder;
            if (b != null) {
                boolean ok = paused ? b.resume(id) : b.pause(id);
                if (!ok && paused) main.post(() -> toast("Conecte ao Wi‑Fi ou desative Somente Wi‑Fi"));
            }
        })), weighted());
        controls.addView(button("Arquivos", v -> showFiles(id)), weighted());
        controls.addView(button(sequential ? "Sequencial ✓" : "Sequencial", v -> runAction(() -> {
            NautrixTorrentService.LocalBinder b = binder;
            if (b != null) b.setSequential(id, !sequential);
        })), weighted());
        controls.addView(button("Remover", v -> showRemoveDialog(id)), weighted());
        card.addView(controls, matchWrap());
        return card;
    }

    private void showFiles(String id) {
        NautrixTorrentService.LocalBinder current = binder;
        if (current == null) return;
        worker.execute(() -> {
            try {
                JSONArray files = new JSONArray(current.filesJson(id));
                main.post(() -> showFilesDialog(id, files));
            } catch (Exception e) {
                main.post(() -> toast("Metadados dos arquivos ainda não estão disponíveis"));
            }
        });
    }

    private void showFilesDialog(String id, JSONArray files) {
        if (files.length() == 0) {
            toast("Aguardando metadados do torrent");
            return;
        }
        String[] labels = new String[files.length()];
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file == null) continue;
            int priority = file.optInt("priority", 0);
            labels[i] = (priority <= 0 ? "☐ " : "☑ ")
                    + file.optString("path", "arquivo")
                    + "  (" + humanBytes(file.optLong("size", 0)) + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle("Arquivos do torrent")
                .setItems(labels, (dialog, which) -> {
                    JSONObject file = files.optJSONObject(which);
                    if (file != null && !file.optBoolean("pad", false)) showPriorityDialog(id, file);
                })
                .setNegativeButton("Fechar", null)
                .show();
    }

    private void showPriorityDialog(String id, JSONObject file) {
        String[] choices = {"Não baixar", "Baixa", "Normal", "Alta"};
        int currentPriority = file.optInt("priority", 0);
        int checked = currentPriority <= 0 ? 0 : currentPriority >= 7 ? 3 : currentPriority <= 2 ? 1 : 2;
        new AlertDialog.Builder(this)
                .setTitle(file.optString("path", "Arquivo"))
                .setSingleChoiceItems(choices, checked, (dialog, which) -> {
                    int value = which == 0 ? 0 : which == 1 ? 1 : which == 2 ? 4 : 7;
                    int index = file.optInt("index", -1);
                    runAction(() -> {
                        NautrixTorrentService.LocalBinder b = binder;
                        if (b != null) b.setFilePriority(id, index, value);
                    });
                    dialog.dismiss();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void showRemoveDialog(String id) {
        new AlertDialog.Builder(this)
                .setTitle("Remover torrent")
                .setItems(new String[] {"Remover e manter arquivos", "Remover e apagar arquivos"},
                        (dialog, which) -> runAction(() -> {
                            NautrixTorrentService.LocalBinder b = binder;
                            if (b != null) b.remove(id, which == 1);
                        }))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void showMagnetDialog() {
        EditText input = new EditText(this);
        input.setHint("magnet:?xt=urn:btih:…");
        input.setSingleLine(false);
        input.setMinLines(3);
        new AlertDialog.Builder(this)
                .setTitle("Adicionar magnet")
                .setView(input)
                .setPositiveButton("Adicionar", (dialog, which) -> {
                    boolean ok = NautrixTorrentService.enqueueMagnet(this, input.getText().toString().trim());
                    toast(ok ? "Magnet adicionado" : "Magnet inválido");
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void pickTorrent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/x-bittorrent");
        try {
            startActivityForResult(intent, PICK_TORRENT);
        } catch (Exception e) {
            intent.setType("*/*");
            startActivityForResult(intent, PICK_TORRENT);
        }
    }

    private void showLimitsDialog() {
        NautrixTorrentService.LocalBinder current = binder;
        if (current == null) return;
        LinearLayout box = vertical();
        box.setPadding(dp(22), 0, dp(22), 0);
        EditText down = numericField("Download KB/s (0 = sem limite)", current.getDownloadLimit() / 1024);
        EditText up = numericField("Upload KB/s (0 = sem limite)", current.getUploadLimit() / 1024);
        EditText connections = numericField("Máximo de conexões (20–2000)", current.getConnectionLimit());
        box.addView(down);
        box.addView(up);
        box.addView(connections);
        new AlertDialog.Builder(this)
                .setTitle("Limites BitTorrent")
                .setView(box)
                .setPositiveButton("Salvar", (dialog, which) -> {
                    int downK = parseInt(down, 0, 0, Integer.MAX_VALUE / 1024);
                    int upK = parseInt(up, 0, 0, Integer.MAX_VALUE / 1024);
                    int peers = parseInt(connections, 200, 20, 2000);
                    current.setRateLimits(downK * 1024, upK * 1024);
                    current.setConnectionLimit(peers);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void runAction(Runnable action) {
        worker.execute(() -> {
            try {
                action.run();
            } finally {
                main.post(this::refresh);
            }
        });
    }

    private void renderMessage(String message) {
        if (list == null) return;
        list.removeAllViews();
        TextView text = text(message, 15);
        text.setGravity(Gravity.CENTER_HORIZONTAL);
        text.setPadding(0, dp(30), 0, dp(30));
        list.addView(text, matchWrap());
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private TextView text(String value, int sp) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        return view;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        return button;
    }

    private EditText numericField(String hint, int value) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(value));
        return input;
    }

    private static int parseInt(EditText view, int fallback, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(view.getText().toString())));
        } catch (Exception e) {
            return fallback;
        }
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    private static String humanSpeed(long value) {
        if (value >= 1024L * 1024L) return String.format(Locale.ROOT, "%.1f MB/s", value / 1048576.0);
        if (value >= 1024L) return String.format(Locale.ROOT, "%.0f KB/s", value / 1024.0);
        return value + " B/s";
    }

    private static String humanBytes(long value) {
        if (value >= 1024L * 1024L * 1024L) return String.format(Locale.ROOT, "%.1f GB", value / 1073741824.0);
        if (value >= 1024L * 1024L) return String.format(Locale.ROOT, "%.1f MB", value / 1048576.0);
        if (value >= 1024L) return String.format(Locale.ROOT, "%.0f KB", value / 1024.0);
        return value + " B";
    }
}
