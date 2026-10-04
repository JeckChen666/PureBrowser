package com.example.purebrowser.share;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Pure validation/statistics helpers. Never launches, fetches, grants, persists or plays anything. */
final class Code13SharePolicy {
    static final String LEGACY_PACKAGE = "io.github.jeckchen666.purebrowser";
    static final long MAX_BYTES = 256L * 1024 * 1024;
    static final long TOTAL_MS = 45_000;
    static final long COPY_MS = 20_000;
    static final long AUDIO_MS = 8_000;
    static final long AUDIO_WINDOW_US = 2_000_000;
    static final long MAX_PCM_BYTES = 2L * 1024 * 1024;
    static final int FRAME_EDGE = 320;
    static final int MAX_TRACKS = 16;
    static final int MAX_INPUT_BYTES = 256 * 1024;

    static final class Reject extends Exception {
        final String code;
        Reject(String code) { this.code = code; }
    }

    static Uri uriFrom(Intent intent) throws Reject {
        if (intent == null || (!Intent.ACTION_SEND.equals(intent.getAction()) &&
                !Intent.ACTION_VIEW.equals(intent.getAction()))) throw new Reject("action_rejected");
        int flags = intent.getFlags();
        if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) throw new Reject("read_flag_missing");
        if ((flags & (Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)) != 0) throw new Reject("broad_grant_rejected");
        Object stream;
        try { stream = intent.getParcelableExtra(Intent.EXTRA_STREAM); }
        catch (RuntimeException e) { throw new Reject("malformed_stream"); }
        if (stream != null && !(stream instanceof Uri)) throw new Reject("stream_type_rejected");
        Uri uri = (Uri) stream;
        Uri data = intent.getData();
        if (uri == null) uri = data;
        else if (data != null && !uri.equals(data)) throw new Reject("multiple_uris_rejected");
        ClipData clip = intent.getClipData();
        if (clip != null) {
            if (clip.getItemCount() != 1 || clip.getItemAt(0).getIntent() != null)
                throw new Reject("clip_rejected");
            Uri clipUri = clip.getItemAt(0).getUri();
            if (clipUri == null) throw new Reject("clip_rejected");
            if (uri == null) uri = clipUri;
            else if (!uri.equals(clipUri)) throw new Reject("multiple_uris_rejected");
        }
        if (uri == null || !"content".equals(uri.getScheme()) || uri.getQuery() != null ||
                uri.getFragment() != null || uri.toString().length() > 4096 ||
                uri.getPath() == null || uri.getPath().isEmpty()) throw new Reject("uri_rejected");
        String authority = uri.getAuthority();
        if (!isAllowedAuthority(authority) || !authority.equals(uri.getEncodedAuthority()))
            throw new Reject("authority_rejected");
        return uri;
    }

    static boolean isAllowedAuthority(String authority) {
        return (LEGACY_PACKAGE + ".files").equals(authority) ||
                "media".equals(authority) || "downloads".equals(authority);
    }

    /** Fixed vocabulary: never echo an untrusted provider/codec string into evidence/UI. */
    static String mime(String value) {
        if (value == null) return "unknown";
        switch (value) {
            case "video/mp4": case "video/webm": case "video/3gpp": case "video/avc":
            case "video/hevc": case "video/av01": case "video/x-vnd.on2.vp8":
            case "video/x-vnd.on2.vp9": case "video/mp2t": case "video/mpeg":
            case "audio/mp4": case "audio/aac": case "audio/mp4a-latm": case "audio/mpeg":
            case "audio/opus": case "audio/vorbis": case "audio/raw": case "audio/3gpp":
            case "audio/flac": case "audio/3gpp2": case "application/octet-stream":
                return value;
            default: return "other";
        }
    }

    static String audioCodec(String mime) {
        if ("audio/mp4a-latm".equals(mime)) return "aac";
        if ("audio/vorbis".equals(mime)) return "vorbis";
        return "unsupported";
    }

    /** PCM16 statistics only: no output samples, audio sink, file or waveform. */
    static long[] pcm16(ByteBuffer buffer, int offset, int size) throws Reject {
        if (offset < 0 || size < 0 || (size & 1) != 0 ||
                (long) offset + size > buffer.capacity()) throw new Reject("pcm_buffer_rejected");
        ByteBuffer view = buffer.duplicate().order(ByteOrder.nativeOrder());
        view.clear();
        view.position(offset);
        view.limit(offset + size);
        long nonzero = 0;
        while (view.remaining() >= 2) if (view.getShort() != 0) nonzero++;
        return new long[] { size / 2L, nonzero };
    }

    private Code13SharePolicy() { }
}
