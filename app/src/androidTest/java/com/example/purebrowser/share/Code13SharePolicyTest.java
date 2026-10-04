package com.example.purebrowser.share;

import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;

/** Helper tests only: never launches the receiver or injects a URI as cross-UID/chooser evidence. */
public class Code13SharePolicyTest {
    private static final Uri URI = Uri.parse("content://media/external/video/media/123");

    private static Intent send() {
        return new Intent(Intent.ACTION_SEND).setType("video/mp4")
                .putExtra(Intent.EXTRA_STREAM, URI).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    private static void reject(Intent intent, String expected) throws Exception {
        try {
            Code13SharePolicy.uriFrom(intent);
            fail("Expected fixed rejection code: " + expected);
        } catch (Code13SharePolicy.Reject rejection) { assertEquals(expected, rejection.code); }
    }

    @Test public void acceptsSingleReadOnlyContentSendAndView() throws Exception {
        assertEquals(URI, Code13SharePolicy.uriFrom(send()));
        assertEquals(URI, Code13SharePolicy.uriFrom(new Intent(Intent.ACTION_VIEW).setData(URI)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)));
        Intent clipOnly = new Intent(Intent.ACTION_SEND).setType("video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        clipOnly.setClipData(ClipData.newRawUri("not recorded", URI));
        assertEquals(URI, Code13SharePolicy.uriFrom(clipOnly));
        Intent matching = send().setData(URI);
        matching.setClipData(ClipData.newRawUri("not recorded", URI));
        assertEquals(URI, Code13SharePolicy.uriFrom(matching));
    }

    @Test public void rejectsHttpFileQueryFragmentUnknownAndCrossUserAuthority() throws Exception {
        String[] rejected = {"https://example.invalid/video.mp4", "http://example.invalid/video.mp4",
                "file:///sdcard/video.mp4", "content://media/external/video/media/123?secret=value",
                "content://media/external/video/media/123#secret", "content://media"};
        for (String value : rejected)
            reject(send().putExtra(Intent.EXTRA_STREAM, Uri.parse(value)), "uri_rejected");
        String[] authorities = {"content://untrusted/video/1", "content://10@media/video/1",
                "content://%6dedia/video/1", "content://io.github.jeckchen666.purebrowser.debug.files/video/1"};
        for (String value : authorities)
            reject(send().putExtra(Intent.EXTRA_STREAM, Uri.parse(value)), "authority_rejected");
        assertTrue(Code13SharePolicy.isAllowedAuthority("io.github.jeckchen666.purebrowser.files"));
        assertTrue(Code13SharePolicy.isAllowedAuthority("downloads"));
    }

    @Test public void refusesWritePrefixPersistableMissingReadAndSendMultiple() throws Exception {
        reject(send().setFlags(0), "read_flag_missing");
        int[] broad = {Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION, Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION};
        for (int flag : broad) reject(send().addFlags(flag), "broad_grant_rejected");
        reject(send().setAction(Intent.ACTION_SEND_MULTIPLE), "action_rejected");
        reject(null, "action_rejected");
    }

    @Test public void refusesAmbiguousUriAndNestedClipIntent() throws Exception {
        Uri other = Uri.parse("content://media/external/video/media/124");
        reject(send().setData(other), "multiple_uris_rejected");
        Intent mismatch = send();
        mismatch.setClipData(ClipData.newRawUri("not recorded", other));
        reject(mismatch, "multiple_uris_rejected");
        Intent multiple = send();
        ClipData clip = ClipData.newRawUri("not recorded", URI);
        clip.addItem(new ClipData.Item(other));
        multiple.setClipData(clip);
        reject(multiple, "clip_rejected");
        Intent nested = send();
        nested.setClipData(ClipData.newIntent("not recorded", new Intent(Intent.ACTION_VIEW)));
        reject(nested, "clip_rejected");
    }

    @Test public void mimeUsesFixedVocabularyNeverEchoesProviderStrings() {
        assertEquals("video/mp4", Code13SharePolicy.mime("video/mp4"));
        assertEquals("audio/mp4a-latm", Code13SharePolicy.mime("audio/mp4a-latm"));
        assertEquals("unknown", Code13SharePolicy.mime(null));
        assertEquals("other", Code13SharePolicy.mime("video/secret-token\nhttps://example.invalid"));
    }

    @Test public void audioDecoderWhitelistAcceptsAacVorbisAndPreservesUnsupported() {
        assertEquals("aac", Code13SharePolicy.audioCodec("audio/mp4a-latm"));
        assertEquals("vorbis", Code13SharePolicy.audioCodec("audio/vorbis"));
        assertEquals("unsupported", Code13SharePolicy.audioCodec("audio/opus"));
        assertEquals("unsupported", Code13SharePolicy.audioCodec("audio/raw"));
        assertEquals("unsupported", Code13SharePolicy.audioCodec(null));
    }

    @Test public void silentPcmIsDecodedButNonzeroCountIsNotListeningEvidence() throws Exception {
        assertArrayEquals(new long[] {8, 0}, Code13SharePolicy.pcm16(ByteBuffer.allocate(16), 0, 16));
    }

    @Test public void pcmCountsOnlyRequestedNativeOrderSampleValuesWithoutMutatingBuffer() throws Exception {
        ByteBuffer input = ByteBuffer.allocate(12).order(ByteOrder.nativeOrder());
        input.putShort((short) 99).putShort((short) 0).putShort((short) 1)
                .putShort((short) -1).putShort((short) 0).putShort((short) 99);
        input.position(3);
        input.limit(11);
        assertArrayEquals(new long[] {4, 2}, Code13SharePolicy.pcm16(input, 2, 8));
        assertEquals(3, input.position());
        assertEquals(11, input.limit());
        assertArrayEquals(new long[] {0, 0}, Code13SharePolicy.pcm16(input, 0, 0));
    }

    @Test public void pcmRejectsOddNegativeAndOverflowingRanges() throws Exception {
        int[][] ranges = {{0, 3}, {-1, 2}, {0, -2}, {10, 4}, {Integer.MAX_VALUE, 2}};
        for (int[] range : ranges) {
            try {
                Code13SharePolicy.pcm16(ByteBuffer.allocate(12), range[0], range[1]);
                fail("Expected range rejection");
            } catch (Code13SharePolicy.Reject rejection) { assertEquals("pcm_buffer_rejected", rejection.code); }
        }
    }

    @Test public void audioFailureDoesNotRewriteSuccessfulShareReadOrPermissionStage() throws Exception {
        JSONObject report = new JSONObject().put("activeStage", "audio")
                .put("permissionStage", "passed").put("readStage", "passed")
                .put("shareChecksPassed", true).put("readable", true).put("fullRead", true)
                .put("audio", new JSONObject().put("status", "running").put("decoded", true));
        Code13ShareReceiver.markFailedStage(report, "timed_out");
        assertEquals("passed", report.getString("permissionStage"));
        assertEquals("passed", report.getString("readStage"));
        assertTrue(report.getBoolean("shareChecksPassed"));
        assertTrue(report.getBoolean("readable"));
        assertTrue(report.getBoolean("fullRead"));
        assertEquals("timed_out", report.getJSONObject("audio").getString("status"));
        assertFalse(report.getJSONObject("audio").getBoolean("decoded"));
    }

    @Test public void frameFailureDoesNotRewriteSuccessfulShareStage() throws Exception {
        JSONObject report = new JSONObject().put("activeStage", "frames")
                .put("permissionStage", "passed").put("readStage", "passed")
                .put("shareChecksPassed", true).put("frames", new JSONObject().put("status", "running"));
        Code13ShareReceiver.markFailedStage(report, "timed_out");
        assertEquals("passed", report.getString("permissionStage"));
        assertEquals("passed", report.getString("readStage"));
        assertTrue(report.getBoolean("shareChecksPassed"));
        assertEquals("timed_out", report.getJSONObject("frames").getString("status"));
    }

    @Test public void componentBelongsToTestApkAndUsesPrivateAuditProcess() throws Exception {
        android.content.Context test = InstrumentationRegistry.getInstrumentation().getContext();
        android.content.Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ComponentName component = new ComponentName(test.getPackageName(), Code13ShareReceiver.class.getName());
        ActivityInfo activity = test.getPackageManager().getActivityInfo(component,
                PackageManager.MATCH_DISABLED_COMPONENTS);
        assertTrue(activity.exported);
        assertEquals(test.getPackageName(), activity.packageName);
        assertEquals(test.getPackageName() + ":code13_share_audit", activity.processName);
        assertNotEquals(target.getApplicationInfo().uid, activity.applicationInfo.uid);
        assertTrue(activity.loadLabel(test.getPackageManager()).toString().startsWith("code13 跨UID媒体验收 "));
        // Deliberately no startActivity(), shell command, recipient launch or URI grant here.
    }
}
