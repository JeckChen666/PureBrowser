package com.example.purebrowser.library;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
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
            if(uri != null && "content".equals(uri.getScheme()) && uri.getQuery()==null && uri.getFragment()==null &&
                ("downloads".equals(uri.getAuthority()) || "media".equals(uri.getAuthority()) ||
                 "io.github.jeckchen666.purebrowser.debug.files".equals(uri.getAuthority()) ||
                 "io.github.jeckchen666.purebrowser.files".equals(uri.getAuthority()))) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long size = 0;
                try(InputStream input=getContentResolver().openInputStream(uri)) {
                    byte[] buffer = new byte[65536]; int count;
                    while((count=input.read(buffer))>=0) {
                        size += count;
                        if(size>256L*1024*1024) throw new IllegalArgumentException("Fixture too big");
                        digest.update(buffer,0,count);
                    }
                }
                StringBuilder hash = new StringBuilder();
                for(byte b:digest.digest()) hash.append(String.format("%02x",b & 255));
                report.put("readable",true).put("uri",uri.toString()).put("size",size).put("sha256",hash.toString());
            }
        } catch(Exception ignored) { /* Honest false report for missing or denied URI. */ }
        try(FileOutputStream output=new FileOutputStream(new File(getFilesDir(),"fixture-file-received.json"))) {
            output.write(report.toString().getBytes(StandardCharsets.UTF_8));
        } catch(Exception ignored) { }
        finish();
    }
}
