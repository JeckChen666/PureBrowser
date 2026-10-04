package com.example.purebrowser.share;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/**
 * Opt-in Activity in the independent TEST APK UID, not instrumentation's target-app process.
 * Entry MUST be selected in the real code13 native share chooser by the mainline operator.
 * Receipt/URI grants alone cannot attest that UI provenance: external chooser evidence is required.
 * No launcher/instrumentation method injects a URI here. No grants are requested, persisted or forwarded.
 * Safe report: run-as <debug-test-package> cat files/code13-share-report.json (mainline only).
 */
public final class Code13ShareReceiver extends Activity {
    public static final String REPORT_FILE = "code13-share-report.json";
    private static final String SNAPSHOT_FILE = "code13-share-snapshot.bin";
    private static final AtomicBoolean ACTIVE_PROBE = new AtomicBoolean();
    private final AtomicBoolean terminal = new AtomicBoolean();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "code13-share-watchdog");
        thread.setDaemon(true);
        return thread;
    });
    private TextView text;
    private long startedAt;
    private String lastPublishedReport;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // Protect report UI from screenshots by other apps; mainline can export only the safe JSON.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        text = new TextView(this);
        text.setTextSize(14);
        text.setPadding(24, 24, 24, 24);
        text.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        setContentView(scroll);
        if (state != null || !ACTIVE_PROBE.compareAndSet(false, true)) {
            // Recreation is not a new chooser delivery, and must not duplicate a live probe.
            terminal.set(true);
            watchdog.shutdownNow();
            text.setText("code13 跨UID媒体验收\n恢复/并发 Intent 未处理。请读取上一份安全报告，" +
                    "关闭接收器后重新从旧正式包原生按钮分享。");
            return;
        }
        startedAt = System.currentTimeMillis();
        JSONObject initial = baseReport("pending");
        if (!save(initial)) {
            terminal.set(true);
            display(baseReport("report_write_failed"));
            ACTIVE_PROBE.set(false);
            watchdog.shutdownNow();
            return;
        }
        display(initial); // Replaces stale success immediately, before any provider operation.
        final Intent incoming = getIntent();
        final long deadline = SystemClock.elapsedRealtime() + Code13SharePolicy.TOTAL_MS;
        // Native provider/codec calls can block beyond cooperative deadlines. In that case persist
        // an honest failure then kill ONLY this private test-APK process, releasing all native memory.
        watchdog.schedule(this::timeout, Code13SharePolicy.TOTAL_MS, TimeUnit.MILLISECONDS);
        Thread worker = new Thread(() -> receive(incoming, deadline), "code13-share-probe");
        worker.start();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // singleTask: never run concurrent readers or mix evidence. Finish this Activity before
        // choosing a second native share. We intentionally do not adopt a second Intent/URI.
        text.setText("code13 跨UID媒体验收\n已有一次接收；本次新 Intent 未处理。\n" +
                "请返回关闭接收器，再从旧正式包原生分享按钮打开系统 chooser。\n" +
                "第一次接收的安全报告仍在 files/" + REPORT_FILE);
    }

    private void receive(Intent intent, long deadline) {
        JSONObject report = baseReport("running");
        File snapshot = new File(getCacheDir(), SNAPSHOT_FILE);
        try {
            Uri uri = Code13SharePolicy.uriFrom(intent);
            report.put("action", intent.getAction()).put("intentReadFlag", true);
            PackageManager pm = getPackageManager();
            PackageInfo legacy = pm.getPackageInfo(Code13SharePolicy.LEGACY_PACKAGE, 0);
            long version = Build.VERSION.SDK_INT >= 28 ? legacy.getLongVersionCode() : legacy.versionCode;
            int recipientUid = Process.myUid();
            if (version != 13) throw new Code13SharePolicy.Reject("legacy_code13_required");
            if (legacy.applicationInfo == null || legacy.applicationInfo.uid == recipientUid)
                throw new Code13SharePolicy.Reject("legacy_same_uid_rejected");
            report.put("legacyVersionCode", version).put("legacyUID", legacy.applicationInfo.uid)
                    .put("legacyDifferentUid", true);
            ProviderInfo provider = pm.resolveContentProvider(uri.getAuthority(), 0);
            if (provider == null || provider.applicationInfo == null || !provider.enabled)
                throw new Code13SharePolicy.Reject("provider_missing");
            if ((Code13SharePolicy.LEGACY_PACKAGE + ".files").equals(uri.getAuthority())) {
                if (!Code13SharePolicy.LEGACY_PACKAGE.equals(provider.packageName) ||
                        provider.applicationInfo.uid != legacy.applicationInfo.uid)
                    throw new Code13SharePolicy.Reject("provider_owner_rejected");
            } else if ((provider.applicationInfo.flags &
                    (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0) {
                throw new Code13SharePolicy.Reject("provider_owner_rejected");
            }
            int ownerUid = provider.applicationInfo.uid;
            report.put("ownerUID", ownerUid).put("ownerUidScope", "content_provider")
                    .put("ownerDifferentUid", ownerUid != recipientUid);
            if (ownerUid == recipientUid) throw new Code13SharePolicy.Reject("owner_same_uid_rejected");
            boolean read = checkUriPermission(uri, Process.myPid(), recipientUid,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED;
            boolean write = checkUriPermission(uri, Process.myPid(), recipientUid,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED;
            report.put("readGranted", read).put("writeGranted", write);
            if (!read || write) throw new Code13SharePolicy.Reject("uri_grant_rejected");
            report.put("permissionStage", "passed").put("readStage", "running").put("activeStage", "read");
            checkpoint(report);
            Code13MediaProbe.checkTime(deadline);
            report.put("mime", Code13SharePolicy.mime(getContentResolver().getType(uri)));
            copyAndHash(uri, snapshot, report, Math.min(deadline,
                    SystemClock.elapsedRealtime() + Code13SharePolicy.COPY_MS));
            report.put("readStage", "passed").put("shareChecksPassed", true);
            checkpoint(report);
            Code13MediaProbe.inspect(snapshot, report, deadline, this::checkpoint);
            report.put("status", "complete").put("activeStage", "done");
        } catch (Code13SharePolicy.Reject e) {
            put(report, "status", report.optBoolean("shareChecksPassed") ? "complete" : "rejected");
            markFailedStage(report, e.code);
            put(report, "reason", e.code); // Fixed code, never exception text/URI/path.
            put(report, "mediaChecksPassed", false);
        } catch (SecurityException e) {
            put(report, "status", report.optBoolean("shareChecksPassed") ? "complete" : "rejected");
            markFailedStage(report, "access_denied"); put(report, "reason", "access_denied");
            put(report, "mediaChecksPassed", false);
        } catch (Exception e) {
            put(report, "status", report.optBoolean("shareChecksPassed") ? "complete" : "failed");
            markFailedStage(report, "inspection_failed"); put(report, "reason", "inspection_failed");
            put(report, "mediaChecksPassed", false);
        } finally {
            // Full snapshot is transient, private, bounded, and never returned as evidence.
            snapshot.delete();
        }
        put(report, "elapsedMs", Math.min(Code13SharePolicy.TOTAL_MS,
                Math.max(0, System.currentTimeMillis() - startedAt)));
        if (terminal.compareAndSet(false, true)) {
            watchdog.shutdownNow();
            if (!save(report)) report = baseReport("report_write_failed");
            ACTIVE_PROBE.set(false);
            final JSONObject visible = report;
            runOnUiThread(() -> display(visible));
        }
    }

    private void copyAndHash(Uri uri, File file, JSONObject report, long deadline) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long bytes = 0;
        // Read only the granted URI: no MediaStore queries by filename, redirects or URL resolution.
        ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(uri, "r");
        if (fd == null) throw new Code13SharePolicy.Reject("descriptor_missing");
        try (ParcelFileDescriptor owned = fd;
             FileInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(owned);
             FileOutputStream output = new FileOutputStream(file, false)) {
            long advertised = owned.getStatSize();
            if (advertised > Code13SharePolicy.MAX_BYTES)
                throw new Code13SharePolicy.Reject("byte_limit");
            byte[] buffer = new byte[65536];
            while (true) {
                Code13MediaProbe.checkTime(deadline);
                int count = input.read(buffer);
                if (count == -1) break;
                if (count == 0) continue;
                if (bytes + count > Code13SharePolicy.MAX_BYTES)
                    throw new Code13SharePolicy.Reject("byte_limit");
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                bytes += count;
            }
            Code13MediaProbe.checkTime(deadline);
            if (bytes == 0 || (advertised >= 0 && bytes != advertised))
                throw new Code13SharePolicy.Reject("incomplete_file");
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            int unsigned = value & 255;
            hex.append(Character.forDigit(unsigned >>> 4, 16)).append(Character.forDigit(unsigned & 15, 16));
        }
        report.put("readable", true).put("fullRead", true).put("bytes", bytes).put("sha256", hex.toString());
    }

    private JSONObject baseReport(String status) {
        JSONObject report = new JSONObject();
        put(report, "schema", 1);
        put(report, "status", status);
        put(report, "startedAtEpochMs", startedAt);
        put(report, "recipientUID", Process.myUid());
        put(report, "ownerUidScope", "content_provider");
        put(report, "activeStage", "permission");
        put(report, "permissionStage", "pending");
        put(report, "readStage", "not_started");
        put(report, "metadataStage", "not_started");
        JSONObject frames = new JSONObject();
        put(frames, "status", "not_started");
        put(report, "frames", frames);
        JSONObject audio = new JSONObject();
        put(audio, "status", "not_started");
        put(audio, "decoded", false);
        put(audio, "listening", "not_tested");
        put(report, "audio", audio);
        put(report, "shareChecksPassed", false);
        put(report, "readGranted", false);
        put(report, "readable", false);
        put(report, "fullRead", false);
        put(report, "mediaChecksPassed", false);
        put(report, "chooserEvidence", "external_native_ui_evidence_required");
        put(report, "senderAttribution", "not_attested_by_receiver");
        put(report, "listening", "not_tested");
        return report;
    }

    private synchronized boolean save(JSONObject report) {
        AtomicFile file = new AtomicFile(new File(getFilesDir(), REPORT_FILE));
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(report.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
            lastPublishedReport = report.toString();
            return true;
        } catch (Exception e) {
            if (output != null) file.failWrite(output);
            // Do not leave a stale complete result after a failed report replacement.
            file.delete();
            return false;
        }
    }

    private synchronized void checkpoint(JSONObject report) {
        // Freeze only our own whitelist JSON, not the mutable worker object. A watchdog can then
        // retain a completed read/UID stage even if a later native decoder hangs.
        if (!terminal.get()) save(report);
    }

    private synchronized void timeout() {
        if (!terminal.compareAndSet(false, true)) return;
        JSONObject report;
        try { report = lastPublishedReport == null ? baseReport("timed_out") : new JSONObject(lastPublishedReport); }
        catch (org.json.JSONException impossible) { report = baseReport("timed_out"); }
        put(report, "status", "timed_out");
        put(report, "elapsedMs", Code13SharePolicy.TOTAL_MS);
        put(report, "mediaChecksPassed", false);
        markFailedStage(report, "timed_out");
        save(report);
        new File(getCacheDir(), SNAPSHOT_FILE).delete();
        Process.killProcess(Process.myPid());
    }

    static void markFailedStage(JSONObject report, String reason) {
        switch (report.optString("activeStage")) {
            case "permission": put(report, "permissionStage", reason); break;
            case "read": put(report, "readStage", reason); break;
            case "metadata": put(report, "metadataStage", reason); break;
            case "frames":
                JSONObject frames = report.optJSONObject("frames");
                if (frames != null) put(frames, "status", reason);
                break;
            case "audio":
                JSONObject audio = report.optJSONObject("audio");
                if (audio != null) { put(audio, "status", reason); put(audio, "decoded", false); }
                break;
            default: break;
        }
    }

    private void display(JSONObject report) {
        text.setText("code13 跨UID媒体验收\n只验文件授权/解码，不播放声音；listening=not_tested。\n" +
                "chooser 来源须主线原生 UI 证据；接收报告不单独证明来源。\n\n" + report.toString());
    }

    private static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); }
        catch (org.json.JSONException impossible) { throw new IllegalStateException("Invalid fixed report field"); }
    }
}
