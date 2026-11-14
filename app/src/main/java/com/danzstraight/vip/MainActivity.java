package com.danzstraight.vip;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;

public class MainActivity extends Activity {

    private static final String DEFAULT_GIST_ID      = "ISI_GIST_ID_DISINI";
    private static final String DEFAULT_GITHUB_TOKEN = "ISI_GITHUB_TOKEN_DISINI";
    private static final String MASTER_DEV_KEY       = "DANZ-MASTER-DEV";

    private static final String PKG_FF_TH  = "com.dts.freefireth";
    private static final String PKG_FF_MAX = "com.dts.freefiremax";

    private SharedPreferences prefs;
    private FrameLayout contentContainer;
    private FrameLayout loadingOverlay;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private static final int COLOR_BG       = Color.parseColor("#090304");
    private static final int COLOR_CARD     = Color.parseColor("#16070A");
    private static final int COLOR_BORDER   = Color.parseColor("#3E1119");
    private static final int COLOR_RED      = Color.parseColor("#E51928");
    private static final int COLOR_GREEN    = Color.parseColor("#00E676");
    private static final int COLOR_TEXT_SUB = Color.parseColor("#A69296");

    private final Shizuku.OnRequestPermissionResultListener shizukuPermListener =
            (requestCode, grantResult) -> {
                boolean ok = grantResult == PackageManager.PERMISSION_GRANTED;
                Toast.makeText(this, ok ? "Shizuku Aktif (Siap Injeksi ke Sistem FF!)" : "Izin Shizuku Ditolak", Toast.LENGTH_SHORT).show();
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("DANZSTRAIGHT_CFG", MODE_PRIVATE);

        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermListener);
        } catch (Throwable ignored) {}

        String activeKey = prefs.getString("active_key", null);
        if (activeKey == null) {
            renderLoginScreen();
        } else if (MASTER_DEV_KEY.equals(activeKey)) {
            renderMainApp(true, "DEVELOPER MASTER", "PERMANENT");
        } else {
            verifyStoredKeyAndLaunch(activeKey);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermListener);
        } catch (Throwable ignored) {}
    }

    public boolean isShizukuReady() {
        try {
            if (!Shizuku.pingBinder()) return false;
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return true;
            Shizuku.requestPermission(1001);
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    public String execShizukuOrShell(String command) {
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Method newProc = Shizuku.class.getDeclaredMethod(
                        "newProcess", String[].class, String[].class, String.class);
                newProc.setAccessible(true);
                ShizukuRemoteProcess proc = (ShizukuRemoteProcess) newProc.invoke(
                        null, new String[]{"sh", "-c", command}, null, null);
                BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                StringBuilder out = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) out.append(line).append("\n");
                proc.waitFor();
                proc.destroy();
                return "SHIZUKU_OK: " + out.toString().trim();
            }
        } catch (Throwable ignored) {}

        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) out.append(line).append("\n");
            p.waitFor();
            p.destroy();
            return "SHELL_OK: " + out.toString().trim();
        } catch (Exception e) {
            return "ERR: " + e.getMessage();
        }
    }

    private void runFFSystemCommands(String toastSuccess, String... commands) {
        showLoading(true);
        new Thread(() -> {
            for (String cmd : commands) {
                execShizukuOrShell(cmd);
            }
            uiHandler.post(() -> {
                showLoading(false);
                Toast.makeText(this, toastSuccess, Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private String getDeviceHwid() {
        String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        String raw = (androidId != null ? androidId : "NOID") + "_" + Build.BOARD + "_" + Build.MODEL;
        return Integer.toHexString(raw.hashCode()).toUpperCase(Locale.US) + "-" + Build.HARDWARE.toUpperCase(Locale.US);
    }

    private String getGistId() {
        return prefs.getString("gist_id", DEFAULT_GIST_ID).trim();
    }

    private String getGithubToken() {
        return prefs.getString("github_token", DEFAULT_GITHUB_TOKEN).trim();
    }

    private JSONObject fetchLicenseDb() throws Exception {
        String gistId = getGistId();
        String token = getGithubToken();
        if (gistId.isEmpty() || gistId.startsWith("ISI_")) {
            String localJson = prefs.getString("local_license_db", "{}");
            return new JSONObject(localJson);
        }

        URL url = new URL("https://api.github.com/gists/" + gistId);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        if (!token.isEmpty() && !token.startsWith("ISI_")) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);

        InputStream is = conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream();
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();

        JSONObject root = new JSONObject(sb.toString());
        JSONObject files = root.getJSONObject("files");
        if (files.has("licenses.json")) {
            String content = files.getJSONObject("licenses.json").getString("content");
            return new JSONObject(content);
        }
        return new JSONObject();
    }

    private boolean saveLicenseDb(JSONObject dbJson) {
        prefs.edit().putString("local_license_db", dbJson.toString()).apply();

        String gistId = getGistId();
        String token = getGithubToken();
        if (gistId.isEmpty() || gistId.startsWith("ISI_") || token.isEmpty() || token.startsWith("ISI_")) {
            return true;
        }

        try {
            URL url = new URL("https://api.github.com/gists/" + gistId);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("PATCH");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);

            JSONObject fileContent = new JSONObject();
            fileContent.put("content", dbJson.toString(2));
            JSONObject filesObj = new JSONObject();
            filesObj.put("licenses.json", fileContent);
            JSONObject payload = new JSONObject();
            payload.put("files", filesObj);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        }
    }

    private void renderLoginScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(COLOR_BG);
        root.setPadding(64, 64, 64, 64);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("DANZSTRAIGHT");
        tvTitle.setTextColor(COLOR_RED);
        tvTitle.setTextSize(30f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setGravity(Gravity.CENTER);

        TextView tvSub = new TextView(this);
        tvSub.setText("DIRECT FREE FIRE ENGINE • ZERO SCREEN RESIZE\n1 Key 1 Device Protection System");
        tvSub.setTextColor(COLOR_TEXT_SUB);
        tvSub.setTextSize(12f);
        tvSub.setGravity(Gravity.CENTER);
        tvSub.setPadding(0, 12, 0, 48);

        LinearLayout card = createCardLayout();
        TextView tvHwid = new TextView(this);
        tvHwid.setText("Device HWID: " + getDeviceHwid());
        tvHwid.setTextColor(COLOR_GREEN);
        tvHwid.setTextSize(12f);
        tvHwid.setPadding(0, 0, 0, 24);
        card.addView(tvHwid);

        final EditText etKey = new EditText(this);
        etKey.setHint("Masukkan License Key (Contoh: DANZ-XXXX-XXXX)");
        etKey.setTextColor(Color.WHITE);
        etKey.setHintTextColor(COLOR_TEXT_SUB);
        etKey.setSingleLine(true);
        card.addView(etKey);

        Button btnLogin = createButton("VERIFIKASI & MASUK", true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 115);
        lp.setMargins(0, 32, 0, 0);
        btnLogin.setLayoutParams(lp);

        btnLogin.setOnClickListener(v -> {
            String key = etKey.getText().toString().trim();
            if (key.isEmpty()) {
                Toast.makeText(this, "Masukkan key terlebih dahulu!", Toast.LENGTH_SHORT).show();
                return;
            }
            if (MASTER_DEV_KEY.equals(key)) {
                prefs.edit().putString("active_key", key).apply();
                Toast.makeText(this, "Welcome Master Developer!", Toast.LENGTH_SHORT).show();
                renderMainApp(true, "MASTER DEVELOPER", "PERMANENT");
                return;
            }
            authenticateUserKey(key);
        });
        card.addView(btnLogin);

        root.addView(tvTitle);
        root.addView(tvSub);
        root.addView(card);
        setContentView(root);
    }

    private void verifyStoredKeyAndLaunch(String key) {
        authenticateUserKey(key);
    }

    private void authenticateUserKey(String key) {
        new Thread(() -> {
            try {
                JSONObject db = fetchLicenseDb();
                if (!db.has(key)) {
                    uiHandler.post(() -> {
                        prefs.edit().remove("active_key").apply();
                        Toast.makeText(this, "Key Tidak Terdaftar / Sudah Dicopot!", Toast.LENGTH_LONG).show();
                        renderLoginScreen();
                    });
                    return;
                }

                JSONObject item = db.getJSONObject(key);
                String status = item.optString("status", "active");
                if (!"active".equals(status)) {
                    uiHandler.post(() -> {
                        prefs.edit().remove("active_key").apply();
                        Toast.makeText(this, "Key telah dinonaktifkan oleh Developer!", Toast.LENGTH_LONG).show();
                        renderLoginScreen();
                    });
                    return;
                }

                int days = item.optInt("days", 1);
                long expAt = item.optLong("expires_at", 0L);
                String boundHwid = item.optString("hwid", "");
                String reseller = item.optString("reseller", "Official");
                String myHwid = getDeviceHwid();

                if (boundHwid.isEmpty()) {
                    boundHwid = myHwid;
                    item.put("hwid", myHwid);
                    if (days > 0) {
                        expAt = System.currentTimeMillis() + (days * 86_400_000L);
                        item.put("expires_at", expAt);
                    }
                    db.put(key, item);
                    saveLicenseDb(db);
                } else if (!boundHwid.equals(myHwid)) {
                    uiHandler.post(() -> {
                        prefs.edit().remove("active_key").apply();
                        Toast.makeText(this, "DITOLAK: Key sudah terikat di HP lain! (1 Key 1 Device)", Toast.LENGTH_LONG).show();
                        renderLoginScreen();
                    });
                    return;
                }

                if (days > 0 && expAt > 0 && System.currentTimeMillis() > expAt) {
                    uiHandler.post(() -> {
                        prefs.edit().remove("active_key").apply();
                        Toast.makeText(this, "Masa aktif Key sudah habis (Expired)!", Toast.LENGTH_LONG).show();
                        renderLoginScreen();
                    });
                    return;
                }

                String expStr = (days == 0) ? "PERMANENT" :
                        new SimpleDateFormat("dd MMM yyyy HH:mm", Locale.US).format(new Date(expAt));

                uiHandler.post(() -> {
                    prefs.edit().putString("active_key", key).apply();
                    renderMainApp(false, reseller, expStr);
                });
            } catch (Exception e) {
                uiHandler.post(() -> {
                    Toast.makeText(this, "Gagal menghubungi server: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    renderLoginScreen();
                });
            }
        }).start();
    }

    private void renderMainApp(boolean isDev, String roleOrReseller, String expText) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BG);

        LinearLayout vertical = new LinearLayout(this);
        vertical.setOrientation(LinearLayout.VERTICAL);

        contentContainer = new FrameLayout(this);
        vertical.addView(contentContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(16, 18, 16, 18);
        GradientDrawable navBg = new GradientDrawable();
        navBg.setColor(Color.parseColor("#130508"));
        navBg.setStroke(2, COLOR_BORDER);
        nav.setBackground(navBg);

        String[] labels = isDev
                ? new String[]{"Home", "GameSpace", "ROG/PC", "Vortex", "DevKey"}
                : new String[]{"Home", "GameSpace", "ROG/PC", "Vortex", "Logout"};

        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            Button b = createButton(labels[i], i == 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 95, 1f);
            lp.setMargins(6, 0, 6, 0);
            b.setLayoutParams(lp);
            b.setTextSize(11f);
            b.setPadding(4, 0, 4, 0);

            b.setOnClickListener(v -> {
                for (int j = 0; j < nav.getChildCount(); j++) {
                    styleButton((Button) nav.getChildAt(j), j == idx);
                }
                if (idx == 0) showHomeTab(roleOrReseller, expText);
                else if (idx == 1) showGameSpaceTab();
                else if (idx == 2) showRogPcGraphicsTab();
                else if (idx == 3) showVortexSensitivityTab();
                else {
                    if (isDev) showDeveloperKeyPanel();
                    else {
                        prefs.edit().remove("active_key").apply();
                        renderLoginScreen();
                    }
                }
            });
            nav.addView(b);
        }
        vertical.addView(nav);
        root.addView(vertical);

        loadingOverlay = new FrameLayout(this);
        loadingOverlay.setBackgroundColor(Color.parseColor("#99000000"));
        loadingOverlay.setVisibility(View.GONE);
        ProgressBar pb = new ProgressBar(this);
        loadingOverlay.addView(pb, new FrameLayout.LayoutParams(150, 150, Gravity.CENTER));
        root.addView(loadingOverlay);

        setContentView(root);
        showHomeTab(roleOrReseller, expText);
    }

    private void showLoading(boolean show) {
        if (loadingOverlay != null) {
            loadingOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private void showHomeTab(String reseller, String expText) {
        contentContainer.removeAllViews();
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, 100);

        col.addView(createTopHeader("DANZSTRAIGHT", "Direct Free Fire System Engine"));

        LinearLayout statCard = createCardLayout();
        boolean shizukuOk = isShizukuReady();
        statCard.addView(createKeyValRow("Shizuku Binder",
                shizukuOk ? "CONNECTED (DIRECT FF MODE)" : "BELUM TERHUBUNG",
                shizukuOk ? COLOR_GREEN : COLOR_RED));
        statCard.addView(createKeyValRow("Target Sistem", "com.dts.freefireth / max", COLOR_GREEN));
        statCard.addView(createKeyValRow("Proteksi Layar HP", "100% AMAN (Tanpa Ubah Layar)", Color.WHITE));
        statCard.addView(createKeyValRow("Device HWID", getDeviceHwid(), Color.WHITE));
        statCard.addView(createKeyValRow("Reseller / Tier", reseller, Color.WHITE));
        statCard.addView(createKeyValRow("Expired", expText, COLOR_GREEN));

        Button btnBindShizuku = createButton("Hubungkan Izin Shizuku & Floating Overlay", true);
        btnBindShizuku.setOnClickListener(v -> {
            isShizukuReady();
            requestOverlayAndWriteSettings();
            showHomeTab(reseller, expText);
        });
        statCard.addView(btnBindShizuku);
        col.addView(statCard);

        LinearLayout ovCard = createCardLayout();
        ovCard.addView(createSwitchItem("Crosshair Aim-Assistant",
                "Titik bidik merah statis di tengah layar untuk bantu pre-aim.", false, checked -> {
                    if (checked && !Settings.canDrawOverlays(this)) {
                        requestOverlayAndWriteSettings();
                        return;
                    }
                    Intent i = new Intent(this, OverlayService.class);
                    i.setAction(OverlayService.ACTION_CROSSHAIR);
                    i.putExtra("enable", checked);
                    startService(i);
                }));
        ovCard.addView(createSwitchItem("ROG Floating FPS & RAM Monitor",
                "Monitor FPS & memori secara langsung di atas layar Free Fire.", false, checked -> {
                    if (checked && !Settings.canDrawOverlays(this)) {
                        requestOverlayAndWriteSettings();
                        return;
                    }
                    Intent i = new Intent(this, OverlayService.class);
                    i.setAction(OverlayService.ACTION_HUD);
                    i.putExtra("enable", checked);
                    startService(i);
                }));
        col.addView(ovCard);

        sv.addView(col);
        contentContainer.addView(sv);
    }

    private void showGameSpaceTab() {
        contentContainer.removeAllViews();
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, 100);

        col.addView(createTopHeader("Game Space FF", "Direct Package Optimization & AOT Speed"));

        LinearLayout aotCard = createCardLayout();
        aotCard.addView(createSwitchItem("AOT Speed Compiler (Khusus Paket Free Fire)",
                "Mengkompilasi binary internal Free Fire (cmd package compile -m speed) agar loading map dan render skin senjata instan tanpa patah-patah.",
                false, checked -> {
                    if (checked) {
                        runFFSystemCommands("Sistem Binary Free Fire Dikompilasi ke Mode SPEED!",
                                "cmd package compile -m speed -f " + PKG_FF_TH,
                                "cmd package compile -m speed -f " + PKG_FF_MAX);
                    }
                }));
        col.addView(aotCard);

        LinearLayout gmCard = createCardLayout();
        gmCard.addView(createSwitchItem("Unlock Prioritas CPU/GPU & Anti-Throttling FF",
                "Memasukkan Free Fire ke daftar putih daya tertinggi (dumpsys deviceidle whitelist) & mengaktifkan mode Performa penuh khusus aplikasi FF.",
                false, checked -> {
                    if (checked) {
                        runFFSystemCommands("Prioritas Penuh Sistem Free Fire Aktif!",
                                "cmd game mode performance " + PKG_FF_TH,
                                "cmd game mode performance " + PKG_FF_MAX,
                                "dumpsys deviceidle whitelist +" + PKG_FF_TH,
                                "dumpsys deviceidle whitelist +" + PKG_FF_MAX,
                                "cmd appops set " + PKG_FF_TH + " RUN_IN_BACKGROUND allow",
                                "cmd appops set " + PKG_FF_MAX + " RUN_IN_BACKGROUND allow",
                                "cmd power set-fixed-performance-mode-enabled true");
                    } else {
                        runFFSystemCommands("Prioritas Sistem Free Fire Dikembalikan ke Standar",
                                "cmd game mode standard " + PKG_FF_TH,
                                "cmd game mode standard " + PKG_FF_MAX,
                                "dumpsys deviceidle whitelist -" + PKG_FF_TH,
                                "dumpsys deviceidle whitelist -" + PKG_FF_MAX,
                                "cmd power set-fixed-performance-mode-enabled false");
                    }
                }));
        col.addView(gmCard);

        LinearLayout ramCard = createCardLayout();
        ramCard.addView(createSwitchItem("Alokasi RAM Khusus Free Fire",
                "Membekukan aplikasi lain yang memakan RAM di latar belakang tanpa mengganggu proses Free Fire.",
                false, checked -> {
                    if (checked) {
                        killBackgroundAppsExceptFF();
                        runFFSystemCommands("RAM Dialokasikan Penuh untuk Free Fire!",
                                "am kill-all",
                                "pm trim-caches 999999999999");
                    }
                }));
        col.addView(ramCard);

        LinearLayout netCard = createCardLayout();
        netCard.addView(createSwitchItem("Esports Focus & Stabilizer Ping In-Game",
                "Memblokir notifikasi mengambang saat di dalam Free Fire dan mematikan pemindaian Wi-Fi/BLE latar belakang.",
                false, checked -> {
                    if (checked) {
                        runFFSystemCommands("Esports Focus & Ping Stabilizer Aktif!",
                                "settings put global heads_up_notifications_enabled 0",
                                "settings put global wifi_scan_always_enabled 0",
                                "settings put global ble_scan_always_enabled 0");
                    } else {
                        runFFSystemCommands("Notifikasi Dikembalikan Normal",
                                "settings put global heads_up_notifications_enabled 1",
                                "settings put global wifi_scan_always_enabled 1");
                    }
                }));
        col.addView(netCard);

        LinearLayout launchCard = createCardLayout();
        Button btnLaunchFF = createButton("INJECT GAME SPACE & BUKA FREE FIRE", true);
        btnLaunchFF.setOnClickListener(v -> {
            killBackgroundAppsExceptFF();
            runFFSystemCommands("Membuka Free Fire dengan Konfigurasi Sistem Penuh...",
                    "cmd game mode performance " + PKG_FF_TH,
                    "cmd game mode performance " + PKG_FF_MAX,
                    "dumpsys deviceidle whitelist +" + PKG_FF_TH,
                    "dumpsys deviceidle whitelist +" + PKG_FF_MAX,
                    "cmd power set-fixed-performance-mode-enabled true");
            launchGamePackage();
        });
        launchCard.addView(btnLaunchFF);
        col.addView(launchCard);

        sv.addView(col);
        contentContainer.addView(sv);
    }

    private void killBackgroundAppsExceptFF() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            PackageManager pm = getPackageManager();
            List<android.content.pm.ApplicationInfo> apps = pm.getInstalledApplications(0);
            for (android.content.pm.ApplicationInfo app : apps) {
                if (!app.packageName.equals(getPackageName())
                        && !app.packageName.equals(PKG_FF_TH)
                        && !app.packageName.equals(PKG_FF_MAX)) {
                    am.killBackgroundProcesses(app.packageName);
                }
            }
        } catch (Exception ignored) {}
    }

    private void showRogPcGraphicsTab() {
        contentContainer.removeAllViews();
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, 100);

        col.addView(createTopHeader("Grafik ROG / PC", "100% Injeksi ke Paket FF (Layar HP Tidak Berubah)"));

        LinearLayout liteCard = createCardLayout();
        liteCard.addView(createSwitchItem("Grafik Ringan Kompetitif FF (Render 70% Khusus In-Game)",
                "Menurunkan beban piksel GPU khusus di dalam Free Fire (downscale=0.70 via game_overlay) tanpa mengubah ukuran layar atau ikon HP.",
                false, checked -> {
                    if (checked) {
                        runFFSystemCommands("Grafik Ringan Khusus Sistem Free Fire Aktif!",
                                "device_config put game_overlay " + PKG_FF_TH + " mode=2,downscaleFactor=0.70,fps=90:mode=3,downscaleFactor=0.60,fps=60",
                                "device_config put game_overlay " + PKG_FF_MAX + " mode=2,downscaleFactor=0.70,fps=90:mode=3,downscaleFactor=0.60,fps=60",
                                "cmd game set --mode 2 --downscale 0.70 --fps 90 " + PKG_FF_TH,
                                "cmd game set --mode 2 --downscale 0.70 --fps 90 " + PKG_FF_MAX);
                    } else {
                        resetFFGraphicsOnly();
                    }
                }));
        col.addView(liteCard);

        LinearLayout ultraLiteCard = createCardLayout();
        ultraLiteCard.addView(createSwitchItem("Grafik Super Ringan ROG Lite (Render 55% Anti-Drop FPS)",
                "Merender buffer grafis internal Free Fire di skala 0.55 untuk perangkat RAM kecil agar FPS tidak drop saat war di akhir zona.",
                false, checked -> {
                    if (checked) {
                        runFFSystemCommands("Grafik Super Ringan (0.55x) Khusus Free Fire Aktif!",
                                "device_config put game_overlay " + PKG_FF_TH + " mode=2,downscaleFactor=0.55,fps=120",
                                "device_config put game_overlay " + PKG_FF_MAX + " mode=2,downscaleFactor=0.55,fps=120",
                                "cmd game set --mode 2 --downscale 0.55 --fps 120 " + PKG_FF_TH,
                                "cmd game set --mode 2 --downscale 0.55 --fps 120 " + PKG_FF_MAX);
                    } else {
                        resetFFGraphicsOnly();
                    }
                }));
        col.addView(ultraLiteCard);

        LinearLayout rogCard = createCardLayout();
        rogCard.addView(createSwitchItem("ROG Production GPU Driver & 120FPS Unlock (Khusus FF)",
                "Memaksa sistem Android memakai Driver GPU Gaming (updatable_driver_production_opt_in_apps) dan membuka limit 120FPS khusus Free Fire.",
                false, checked -> {
                    if (checked) {
                        runFFSystemCommands("Driver GPU Gaming & 120FPS Khusus Free Fire Aktif!",
                                "settings put global updatable_driver_production_opt_in_apps " + PKG_FF_TH + "," + PKG_FF_MAX,
                                "settings put global game_driver_opt_in_apps " + PKG_FF_TH + "," + PKG_FF_MAX,
                                "device_config put game_overlay " + PKG_FF_TH + " mode=2,downscaleFactor=0.85,fps=120,loadingBoost=99999999",
                                "device_config put game_overlay " + PKG_FF_MAX + " mode=2,downscaleFactor=0.85,fps=120,loadingBoost=99999999",
                                "cmd game set --mode 2 --downscale 0.85 --fps 120 " + PKG_FF_TH,
                                "cmd game set --mode 2 --downscale 0.85 --fps 120 " + PKG_FF_MAX);
                    } else {
                        resetFFGraphicsOnly();
                    }
                }));
        col.addView(rogCard);

        LinearLayout pcCard = createCardLayout();
        pcCard.addView(createSwitchItem("Engine Grafik PC (ANGLE / Vulkan Driver Khusus FF)",
                "Mengaktifkan layer grafis ANGLE (OpenGL ES di atas Vulkan) khusus untuk com.dts.freefireth/max agar tekstur dan gerakan karakter tajam & responsif seperti PC tanpa mengubah DPI layar HP.",
                false, checked -> {
                    if (checked) {
                        runFFSystemCommands("Engine Grafik PC (ANGLE Vulkan) Diterapkan ke Sistem FF!",
                                "settings put global angle_gl_driver_selection_pkgs " + PKG_FF_TH + "," + PKG_FF_MAX,
                                "settings put global angle_gl_driver_selection_values angle,angle",
                                "device_config put game_overlay " + PKG_FF_TH + " mode=2,downscaleFactor=1.0,fps=120,loadingBoost=99999999",
                                "device_config put game_overlay " + PKG_FF_MAX + " mode=2,downscaleFactor=1.0,fps=120,loadingBoost=99999999",
                                "cmd game set --mode 2 --downscale 1.0 --fps 120 " + PKG_FF_TH,
                                "cmd game set --mode 2 --downscale 1.0 --fps 120 " + PKG_FF_MAX);
                    } else {
                        runFFSystemCommands("Driver ANGLE Free Fire Dinonaktifkan",
                                "settings delete global angle_gl_driver_selection_pkgs",
                                "settings delete global angle_gl_driver_selection_values");
                        resetFFGraphicsOnly();
                    }
                }));
        col.addView(pcCard);

        LinearLayout resetCard = createCardLayout();
        Button btnResetFF = createButton("KEMBALIKAN SISTEM GRAFIK FF KE STANDAR", false);
        btnResetFF.setOnClickListener(v -> resetFFGraphicsOnly());
        resetCard.addView(btnResetFF);
        col.addView(resetCard);

        sv.addView(col);
        contentContainer.addView(sv);
    }

    private void resetFFGraphicsOnly() {
        runFFSystemCommands("Konfigurasi Sistem Grafik Free Fire Di-Reset ke Bawaan!",
                "device_config delete game_overlay " + PKG_FF_TH,
                "device_config delete game_overlay " + PKG_FF_MAX,
                "cmd game reset " + PKG_FF_TH,
                "cmd game reset " + PKG_FF_MAX,
                "settings delete global updatable_driver_production_opt_in_apps",
                "settings delete global game_driver_opt_in_apps",
                "settings delete global angle_gl_driver_selection_pkgs",
                "settings delete global angle_gl_driver_selection_values");
    }

    private void showVortexSensitivityTab() {
        contentContainer.removeAllViews();
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, 100);

        col.addView(createTopHeader("Vortex FF Engine", "In-Game Touch Response & Pointer Velocity"));

        LinearLayout slideCard = createCardLayout();
        TextView tvTitle = new TextView(this);
        tvTitle.setText("Sensitivity Definition (LEV 1 - LEV 7)");
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(16f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);

        final TextView tvVal = new TextView(this);
        tvVal.setText("Level Aktif: LEV 1");
        tvVal.setTextColor(COLOR_RED);
        tvVal.setPadding(0, 12, 0, 12);

        SeekBar sb = new SeekBar(this);
        sb.setMax(6);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvVal.setText("Level Aktif: LEV " + (progress + 1));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int lv = seekBar.getProgress() + 1;
                int pressTimeout = Math.max(120, 400 - (lv * 38));
                runFFSystemCommands("Vortex Sensitivity LEV " + lv + " Diterapkan!",
                        "settings put system pointer_speed " + lv,
                        "settings put secure long_press_timeout " + pressTimeout,
                        "settings put secure multi_press_timeout " + pressTimeout);
            }
        });
        slideCard.addView(tvTitle);
        slideCard.addView(tvVal);
        slideCard.addView(sb);
        col.addView(slideCard);

        String[][] vortexFeatures = {
                {"Headtick 80% (Zero Touch Delay)",
                        "Memangkas jeda pembacaan input jari pada saat melakukan drag ke atas di Free Fire.",
                        "settings put system pointer_speed 6; settings put secure tap_duration_threshold 0.0; settings put secure touch_blocking_period 0.0"},
                {"Controller Recoil & Aim Stabilizer",
                        "Mengunci akselerasi kurva pointer agar bidikan tidak goyang ke kiri/kanan saat menahan tombol tembak.",
                        "settings put system pointer_speed 5; settings put secure long_press_timeout 150"},
                {"Drag Headshot 70% (Instant Vertical Flick)",
                        "Meningkatkan kecepatan respons sentuhan vertikal maksimal tanpa mengubah DPI layar HP.",
                        "settings put system pointer_speed 7; settings put secure multi_press_timeout 130"},
                {"High Touch Sampling Boost (Khusus Saat Main FF)",
                        "Membuka prioritas input frame-rate tertinggi pada paket Free Fire.",
                        "cmd game mode performance " + PKG_FF_TH + "; cmd game mode performance " + PKG_FF_MAX + "; settings put system pointer_speed 7"}
        };

        for (String[] feat : vortexFeatures) {
            LinearLayout c = createCardLayout();
            c.addView(createSwitchItem(feat[0], feat[1], false, checked -> {
                if (checked) {
                    runFFSystemCommands(feat[0] + " Aktif!", feat[2]);
                }
            }));
            col.addView(c);
        }

        sv.addView(col);
        contentContainer.addView(sv);
    }

    private void showDeveloperKeyPanel() {
        contentContainer.removeAllViews();
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, 100);

        col.addView(createTopHeader("Developer Panel", "1 Key 1 Device Cloud Key Manager"));

        LinearLayout createCard = createCardLayout();
        TextView tvCreateTitle = new TextView(this);
        tvCreateTitle.setText("Create License Key (1 Key = 1 Device)");
        tvCreateTitle.setTextColor(Color.WHITE);
        tvCreateTitle.setTextSize(16f);
        tvCreateTitle.setTypeface(Typeface.DEFAULT_BOLD);
        createCard.addView(tvCreateTitle);

        final EditText etReseller = new EditText(this);
        etReseller.setHint("Nama Reseller / Pembeli (Contoh: Reseller_Danz)");
        etReseller.setTextColor(Color.WHITE);
        etReseller.setHintTextColor(COLOR_TEXT_SUB);
        createCard.addView(etReseller);

        final String[] durLabels = {
                "1 Hari (1 Day)",
                "3 Hari (3 Days)",
                "7 Hari (7 Days)",
                "15 Hari (15 Days)",
                "30 Hari (30 Days)",
                "PERMANENT (Lifetime)"
        };
        final int[] durValues = {1, 3, 7, 15, 30, 0};

        final Spinner spDur = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, durLabels);
        spDur.setAdapter(adapter);
        createCard.addView(spDur);

        Button btnGenerate = createButton("CREATE KEY SEKARANG", true);
        btnGenerate.setOnClickListener(v -> {
            int days = durValues[spDur.getSelectedItemPosition()];
            String reseller = etReseller.getText().toString().trim();
            if (reseller.isEmpty()) reseller = "MasterDeveloper";
            generateNewKey(days, reseller);
        });
        createCard.addView(btnGenerate);
        col.addView(createCard);

        LinearLayout manageCard = createCardLayout();
        TextView tvManage = new TextView(this);
        tvManage.setText("Kelola Lisensi Aktif (Liat / Copot / Reset HWID)");
        tvManage.setTextColor(Color.WHITE);
        tvManage.setTextSize(16f);
        tvManage.setTypeface(Typeface.DEFAULT_BOLD);
        manageCard.addView(tvManage);

        final EditText etTargetKey = new EditText(this);
        etTargetKey.setHint("Ketik / Paste Key untuk Copot atau Reset HWID");
        etTargetKey.setTextColor(Color.WHITE);
        etTargetKey.setHintTextColor(COLOR_TEXT_SUB);
        manageCard.addView(etTargetKey);

        Button btnLiatKey = createButton("LIAT SEMUA KEY (LIST KEYS)", true);
        btnLiatKey.setOnClickListener(v -> showAllKeysDialog());
        manageCard.addView(btnLiatKey);

        Button btnResetHwid = createButton("RESET DEVICE KEY (Buka Kunci HWID)", false);
        btnResetHwid.setOnClickListener(v -> {
            String target = etTargetKey.getText().toString().trim();
            if (target.isEmpty()) {
                Toast.makeText(this, "Masukkan Key yang ingin di-reset!", Toast.LENGTH_SHORT).show();
                return;
            }
            modifyKeyInDb(target, false);
        });
        manageCard.addView(btnResetHwid);

        Button btnCopotKey = createButton("COPOT KEY (HAPUS / REVOKE KEY)", false);
        btnCopotKey.setOnClickListener(v -> {
            String target = etTargetKey.getText().toString().trim();
            if (target.isEmpty()) {
                Toast.makeText(this, "Masukkan Key yang ingin dicopot!", Toast.LENGTH_SHORT).show();
                return;
            }
            modifyKeyInDb(target, true);
        });
        manageCard.addView(btnCopotKey);
        col.addView(manageCard);

        LinearLayout gistCard = createCardLayout();
        Button btnCfgGist = createButton("Konfigurasi GitHub Gist ID & Token", false);
        btnCfgGist.setOnClickListener(v -> showGistConfigDialog());
        gistCard.addView(btnCfgGist);

        Button btnLogoutDev = createButton("Keluar dari Mode Developer", false);
        btnLogoutDev.setOnClickListener(v -> {
            prefs.edit().remove("active_key").apply();
            renderLoginScreen();
        });
        gistCard.addView(btnLogoutDev);
        col.addView(gistCard);

        sv.addView(col);
        contentContainer.addView(sv);
    }

    private void generateNewKey(int days, String reseller) {
        showLoading(true);
        new Thread(() -> {
            try {
                String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
                SecureRandom rnd = new SecureRandom();
                StringBuilder sb = new StringBuilder("DANZ-");
                for (int i = 0; i < 4; i++) sb.append(chars.charAt(rnd.nextInt(chars.length())));
                sb.append("-");
                for (int i = 0; i < 4; i++) sb.append(chars.charAt(rnd.nextInt(chars.length())));
                String newKey = sb.toString();

                JSONObject db = fetchLicenseDb();
                JSONObject item = new JSONObject();
                item.put("days", days);
                item.put("expires_at", 0L);
                item.put("hwid", "");
                item.put("reseller", reseller);
                item.put("status", "active");
                db.put(newKey, item);

                saveLicenseDb(db);

                uiHandler.post(() -> {
                    showLoading(false);
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("key", newKey));
                    new AlertDialog.Builder(this)
                            .setTitle("Key Berhasil Dibuat & Disalin!")
                            .setMessage("Key: " + newKey + "\nDurasi: " + (days == 0 ? "PERMANENT" : days + " Hari") + "\nReseller: " + reseller + "\nMode: 1 Key 1 Device")
                            .setPositiveButton("OK", null)
                            .show();
                });
            } catch (Exception e) {
                uiHandler.post(() -> {
                    showLoading(false);
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void showAllKeysDialog() {
        showLoading(true);
        new Thread(() -> {
            try {
                JSONObject db = fetchLicenseDb();
                StringBuilder listStr = new StringBuilder();
                Iterator<String> keys = db.keys();
                int count = 0;
                while (keys.hasNext()) {
                    count++;
                    String k = keys.next();
                    JSONObject obj = db.getJSONObject(k);
                    int days = obj.optInt("days", 1);
                    String hwid = obj.optString("hwid", "");
                    String res = obj.optString("reseller", "Admin");
                    String st = obj.optString("status", "active");

                    listStr.append(count).append(". ").append(k)
                            .append("\n   • Durasi: ").append(days == 0 ? "PERMANENT" : days + " Hari")
                            .append(" | Status: ").append(st.toUpperCase())
                            .append("\n   • Reseller: ").append(res)
                            .append("\n   • Device: ").append(hwid.isEmpty() ? "BELUM DIPAKAI" : hwid)
                            .append("\n────────────────────────\n");
                }
                if (count == 0) listStr.append("Belum ada key yang dibuat.");

                final String finalOutput = listStr.toString();
                final int total = db.length();
                uiHandler.post(() -> {
                    showLoading(false);
                    new AlertDialog.Builder(this)
                            .setTitle("Daftar Semua Key (" + total + ")")
                            .setMessage(finalOutput)
                            .setPositiveButton("Tutup", null)
                            .show();
                });
            } catch (Exception e) {
                uiHandler.post(() -> {
                    showLoading(false);
                    Toast.makeText(this, "Gagal memuat daftar key", Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void modifyKeyInDb(String key, boolean deleteKey) {
        showLoading(true);
        new Thread(() -> {
            try {
                JSONObject db = fetchLicenseDb();
                if (!db.has(key)) {
                    uiHandler.post(() -> {
                        showLoading(false);
                        Toast.makeText(this, "Key tidak ditemukan di database!", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                if (deleteKey) {
                    db.remove(key);
                } else {
                    JSONObject item = db.getJSONObject(key);
                    item.put("hwid", "");
                    db.put(key, item);
                }
                saveLicenseDb(db);
                uiHandler.post(() -> {
                    showLoading(false);
                    Toast.makeText(this, deleteKey
                            ? "Key " + key + " berhasil DICOPOT!"
                            : "Device HWID untuk " + key + " berhasil DI-RESET!", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                uiHandler.post(() -> {
                    showLoading(false);
                    Toast.makeText(this, "Gagal: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void showGistConfigDialog() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(48, 32, 48, 16);

        final EditText etGist = new EditText(this);
        etGist.setHint("Gist ID (contoh: 9a8b7c6d...)");
        etGist.setText(prefs.getString("gist_id", ""));

        final EditText etTok = new EditText(this);
        etTok.setHint("GitHub Token (ghp_xxxx...)");
        etTok.setText(prefs.getString("github_token", ""));

        l.addView(etGist);
        l.addView(etTok);

        new AlertDialog.Builder(this)
                .setTitle("Cloud Database GitHub Gist")
                .setView(l)
                .setPositiveButton("Simpan", (d, w) -> {
                    prefs.edit()
                            .putString("gist_id", etGist.getText().toString().trim())
                            .putString("github_token", etTok.getText().toString().trim())
                            .apply();
                    Toast.makeText(this, "Konfigurasi Cloud Gist Disimpan!", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private View createTopHeader(String title, String sub) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(44, 48, 44, 24);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t1 = new TextView(this);
        t1.setText(title);
        t1.setTextColor(Color.WHITE);
        t1.setTextSize(24f);
        t1.setTypeface(Typeface.DEFAULT_BOLD);

        TextView t2 = new TextView(this);
        t2.setText(sub);
        t2.setTextColor(COLOR_RED);
        t2.setTextSize(12f);

        left.addView(t1);
        left.addView(t2);

        TextView badge = new TextView(this);
        badge.setText("DIRECT-FF VIP");
        badge.setTextColor(COLOR_GREEN);
        badge.setTextSize(11f);
        badge.setTypeface(Typeface.DEFAULT_BOLD);

        h.addView(left);
        h.addView(badge);
        return h;
    }

    private LinearLayout createCardLayout() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(36, 32, 36, 32);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_CARD);
        bg.setCornerRadius(26f);
        bg.setStroke(2, COLOR_BORDER);
        c.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(40, 14, 40, 14);
        c.setLayoutParams(lp);
        return c;
    }

    private View createKeyValRow(String k, String v, int valColor) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(8, 14, 8, 14);

        TextView tk = new TextView(this);
        tk.setText(k);
        tk.setTextColor(COLOR_TEXT_SUB);
        tk.setTextSize(13f);
        tk.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tv = new TextView(this);
        tv.setText(v);
        tv.setTextColor(valColor);
        tv.setTextSize(13f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);

        r.addView(tk);
        r.addView(tv);
        return r;
    }

    private interface SwitchListener {
        void onChanged(boolean checked);
    }

    private View createSwitchItem(String title, String desc, boolean init, SwitchListener listener) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, 12, 0, 12);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        textCol.setPadding(0, 0, 24, 0);

        TextView tt = new TextView(this);
        tt.setText(title);
        tt.setTextColor(Color.WHITE);
        tt.setTextSize(15.5f);
        tt.setTypeface(Typeface.DEFAULT_BOLD);

        TextView td = new TextView(this);
        td.setText(desc);
        td.setTextColor(COLOR_TEXT_SUB);
        td.setTextSize(12f);
        td.setPadding(0, 6, 0, 0);

        textCol.addView(tt);
        textCol.addView(td);

        Switch sw = new Switch(this);
        sw.setChecked(init);
        sw.setOnCheckedChangeListener((b, isChecked) -> listener.onChanged(isChecked));

        r.addView(textCol);
        r.addView(sw);
        return r;
    }

    private Button createButton(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 105);
        lp.setMargins(0, 14, 0, 6);
        b.setLayoutParams(lp);
        styleButton(b, primary);
        return b;
    }

    private void styleButton(Button b, boolean primary) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(20f);
        if (primary) {
            bg.setColor(COLOR_RED);
            b.setTextColor(Color.WHITE);
        } else {
            bg.setColor(Color.parseColor("#1D0A0E"));
            bg.setStroke(2, COLOR_BORDER);
            b.setTextColor(COLOR_TEXT_SUB);
        }
        b.setBackground(bg);
    }

    private void requestOverlayAndWriteSettings() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        }
        if (!Settings.System.canWrite(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    private void launchGamePackage() {
        Intent i = getPackageManager().getLaunchIntentForPackage(PKG_FF_TH);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(PKG_FF_MAX);
        if (i != null) startActivity(i);
        else Toast.makeText(this, "Free Fire (TH/MAX) belum terinstall di HP ini", Toast.LENGTH_SHORT).show();
    }
}
