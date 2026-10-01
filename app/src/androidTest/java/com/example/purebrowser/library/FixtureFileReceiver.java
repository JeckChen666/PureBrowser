package com.example.purebrowser.library;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Independent test-APK process: deliberately no production-code dependency or storage permission. */
public class FixtureFileReceiver extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = getIntent();
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if(uri == null) uri = intent.getData();
        JSONObject report = new JSONObject();
        try {
            report.put("action",intent.getAction()).put("readable",false);
            long id = uri == null ? -1 : Long.parseLong(uri.getLastPathSegment());
            if(uri != null && id > 0 && "content".equals(uri.getScheme()) && "downloads".equals(uri.getAuthority()) &&
                uri.getQuery() == null && uri.getFragment() == null &&
                (uri.getPath().equals("/all_downloads/"+id) || uri.getPath().equals("/my_downloads/"+id) || uri.getPath().equals("/public_downloads/"+id))) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try(InputStream input=getContentResolver().openInputStream(uri)) {
                    byte[] buffer = new byte[8192]; int count;
                    while((count=input.read(buffer))>=0) { bytes.write(buffer,0,count); if(bytes.size()>1048576) throw new IllegalArgumentException("Fixture too big"); }
                }
                StringBuilder hash = new StringBuilder();
                for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())) hash.append(String.format("%02x",b & 255));
                report.put("readable",true).put("id",id).put("size",bytes.size()).put("sha256",hash.toString());
            }
        } catch(Exception ignored) { /* Honest false report for missing or denied URI. */ }
        try(FileOutputStream output=new FileOutputStream(new File(getFilesDir(),"fixture-file-received.json"))) {
            output.write(report.toString().getBytes(StandardCharsets.UTF_8));
        } catch(Exception ignored) { }
        finish();
    }
}
