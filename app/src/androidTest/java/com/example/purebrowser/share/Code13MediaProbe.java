package com.example.purebrowser.share;

import android.graphics.Bitmap;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.SystemClock;
import java.io.File;
import java.nio.ByteBuffer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded LOCAL snapshot inspection only. No URI/URL passed to a decoder, no AudioTrack/MediaPlayer. */
final class Code13MediaProbe {
    interface Checkpoint { void publish(JSONObject report); }

    static void inspect(File snapshot, JSONObject report, long deadline, Checkpoint checkpoint) throws Exception {
        report.put("activeStage", "metadata").put("metadataStage", "running");
        checkpoint.publish(report);
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(snapshot.getAbsolutePath());
            checkTime(deadline);
            int count = extractor.getTrackCount();
            if (count < 1 || count > Code13SharePolicy.MAX_TRACKS)
                throw new Code13SharePolicy.Reject("track_count_rejected");
            JSONArray tracks = new JSONArray();
            report.put("tracks", tracks);
            int video = -1, audioTrack = -1, supportedAudio = -1;
            long duration = 0;
            for (int i = 0; i < count; i++) {
                checkTime(deadline);
                MediaFormat format = extractor.getTrackFormat(i);
                String rawMime = format.getString(MediaFormat.KEY_MIME);
                long us = format.containsKey(MediaFormat.KEY_DURATION) ?
                        format.getLong(MediaFormat.KEY_DURATION) : 0;
                if (us < 0 || us > 24L * 60 * 60 * 1_000_000)
                    throw new Code13SharePolicy.Reject("duration_rejected");
                duration = Math.max(duration, us);
                JSONObject track = new JSONObject().put("index", i)
                        .put("mime", Code13SharePolicy.mime(rawMime)).put("durationUs", us);
                if (rawMime != null && rawMime.startsWith("video/")) {
                    int width = integer(format, MediaFormat.KEY_WIDTH);
                    int height = integer(format, MediaFormat.KEY_HEIGHT);
                    if (width < 1 || height < 1 || width > 8192 || height > 8192 ||
                            (long) width * height > 33_554_432)
                        throw new Code13SharePolicy.Reject("video_dimensions_rejected");
                    track.put("width", width).put("height", height);
                    if (video < 0) video = i;
                }
                if (rawMime != null && rawMime.startsWith("audio/")) {
                    int channels = integer(format, MediaFormat.KEY_CHANNEL_COUNT);
                    int rate = integer(format, MediaFormat.KEY_SAMPLE_RATE);
                    if (channels < 1 || channels > 8 || rate < 8000 || rate > 192000)
                        throw new Code13SharePolicy.Reject("audio_format_rejected");
                    track.put("channels", channels).put("sampleRate", rate);
                    if (audioTrack < 0) audioTrack = i;
                    if (!"unsupported".equals(Code13SharePolicy.audioCodec(rawMime)) && supportedAudio < 0)
                        supportedAudio = i;
                }
                tracks.put(track);
            }
            report.put("durationUs", duration).put("metadataStage", "passed").put("activeStage", "frames");
            checkpoint.publish(report);
            boolean framesOk;
            try {
                framesOk = frames(snapshot, video >= 0 ? extractor.getTrackFormat(video) : null, report, deadline);
            } catch (Code13SharePolicy.Reject e) {
                report.getJSONObject("frames").put("status", e.code); framesOk = false;
            } catch (Exception e) {
                report.getJSONObject("frames").put("status", "decode_failed"); framesOk = false;
            }
            report.put("activeStage", "audio");
            checkpoint.publish(report);
            // Probe at most one track; report its index, never imply all audio tracks were decoded.
            boolean audioOk = audio(extractor, supportedAudio >= 0 ? supportedAudio : audioTrack, report, deadline);
            checkTime(deadline);
            // Independent from shareChecksPassed. No-audio video is not an audio decode failure.
            boolean selectedAudioDecoded = audioOk && report.getJSONObject("audio").optBoolean("decoded");
            report.put("mediaChecksPassed", video >= 0 && framesOk && (audioTrack < 0 || selectedAudioDecoded))
                    .put("activeStage", "done");
            checkpoint.publish(report);
        } catch (Code13SharePolicy.Reject e) {
            if ("running".equals(report.optString("metadataStage")))
                report.put("metadataStage", "rejected").put("metadataReason", e.code);
            else report.put("mediaReason", e.code);
            report.put("mediaChecksPassed", false);
        } catch (Exception e) {
            if ("running".equals(report.optString("metadataStage")))
                report.put("metadataStage", "failed").put("metadataReason", "inspection_failed");
            else {
                report.put("mediaReason", "inspection_failed");
                if ("audio".equals(report.optString("activeStage")))
                    report.getJSONObject("audio").put("status", "decode_failed");
            }
            report.put("mediaChecksPassed", false);
        } finally {
            try { extractor.release(); }
            catch (RuntimeException e) {
                report.put("metadataCleanup", "release_failed").put("mediaChecksPassed", false);
            }
        }
    }

    private static boolean frames(File file, MediaFormat format, JSONObject report, long deadline)
            throws Exception {
        JSONObject frames = new JSONObject();
        JSONArray samples = new JSONArray();
        frames.put("samples", samples).put("maxEdge", Code13SharePolicy.FRAME_EDGE);
        report.put("frames", frames);
        if (format == null) { frames.put("status", "no_video"); return false; }
        if (Build.VERSION.SDK_INT < 27) {
            // Never fall back to full-resolution getFrameAtTime on API26.
            frames.put("status", "scaled_decode_unavailable"); return false;
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            checkTime(deadline);
            long duration = format.containsKey(MediaFormat.KEY_DURATION) ?
                    format.getLong(MediaFormat.KEY_DURATION) : 0;
            if (duration <= 0) {
                // Some containers expose duration only through the retriever, not per-track format.
                String milliseconds = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (milliseconds != null && milliseconds.length() <= 12) {
                    long ms = Long.parseLong(milliseconds);
                    if (ms > 0 && ms <= 24L * 60 * 60 * 1000) duration = ms * 1000;
                }
            }
            if (duration <= 0) { frames.put("status", "duration_missing"); return false; }
            report.put("durationUs", Math.max(report.optLong("durationUs"), duration));
            int w = integer(format, MediaFormat.KEY_WIDTH), h = integer(format, MediaFormat.KEY_HEIGHT);
            double scale = Math.min(1.0, Code13SharePolicy.FRAME_EDGE / (double) Math.max(w, h));
            int width = Math.max(1, (int) (w * scale)), height = Math.max(1, (int) (h * scale));
            long[] points = {0, duration / 2, Math.max(0, duration - Math.min(100_000, duration / 4))};
            String[] names = {"first", "middle", "tail"};
            boolean ok = true;
            for (int i = 0; i < points.length; i++) {
                checkTime(deadline);
                Bitmap bitmap = null;
                JSONObject sample = new JSONObject().put("position", names[i])
                        .put("requestedUs", points[i]).put("decoded", false);
                samples.put(sample);
                try {
                    bitmap = retriever.getScaledFrameAtTime(points[i],
                            MediaMetadataRetriever.OPTION_CLOSEST, width, height);
                    checkTime(deadline);
                    boolean decoded = bitmap != null && bitmap.getWidth() > 0 && bitmap.getHeight() > 0 &&
                            bitmap.getWidth() <= Code13SharePolicy.FRAME_EDGE &&
                            bitmap.getHeight() <= Code13SharePolicy.FRAME_EDGE;
                    sample.put("decoded", decoded);
                    if (decoded) sample.put("width", bitmap.getWidth()).put("height", bitmap.getHeight());
                    ok &= decoded;
                } catch (Code13SharePolicy.Reject e) { throw e; }
                catch (RuntimeException e) { ok = false; }
                finally { if (bitmap != null) bitmap.recycle(); }
            }
            frames.put("status", ok ? "decoded" : "decode_failed");
            return ok;
        } finally { retriever.release(); }
    }

    private static boolean audio(MediaExtractor extractor, int track, JSONObject report, long deadline)
            throws Exception {
        JSONObject audio = new JSONObject().put("codec", "unknown").put("status", "no_audio_track")
                .put("decoded", false).put("decodedSampleValues", 0).put("nonzeroSampleValues", 0)
                .put("pcmBytes", 0).put("signal", "not_decoded").put("windowUs", Code13SharePolicy.AUDIO_WINDOW_US)
                .put("listening", "not_tested");
        report.put("audio", audio);
        if (track < 0) return false;
        MediaFormat format = extractor.getTrackFormat(track);
        String mime = format.getString(MediaFormat.KEY_MIME);
        String codecKind = Code13SharePolicy.audioCodec(mime);
        audio.put("selectedTrack", track).put("codec", codecKind).put("mime", Code13SharePolicy.mime(mime));
        if ("unsupported".equals(codecKind)) {
            audio.put("status", "unsupported_codec");
            return false;
        }
        if (Build.VERSION.SDK_INT < 28) {
            // getSampleSize preflight is required to bound compressed input before reading it.
            audio.put("status", "sample_size_preflight_unavailable");
            return false;
        }
        MediaCodec codec = null;
        boolean started = false;
        long samples = 0, nonzero = 0, pcmBytes = 0;
        long audioDeadline = Math.min(deadline, SystemClock.elapsedRealtime() + Code13SharePolicy.AUDIO_MS);
        try {
            extractor.selectTrack(track);
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            for (int i = 0; i < 4; i++) {
                ByteBuffer csd = format.getByteBuffer("csd-" + i);
                if (csd != null && csd.remaining() > 65536)
                    throw new Code13SharePolicy.Reject("codec_config_rejected");
            }
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Code13SharePolicy.MAX_INPUT_BYTES);
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            String decoder = new MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format);
            if (decoder == null) { audio.put("status", "decoder_unavailable"); return false; }
            try { codec = MediaCodec.createByCodecName(decoder); }
            catch (Exception unavailable) { audio.put("status", "decoder_unavailable"); return false; }
            codec.configure(format, null, null, 0);
            codec.start();
            started = true;
            int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;
            long firstInputUs = Long.MIN_VALUE, firstOutputUs = Long.MIN_VALUE;
            boolean inputEnded = false, outputEnded = false, limited = false;
            int packets = 0, outputBuffers = 0;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (!outputEnded && !limited) {
                checkTime(audioDeadline);
                if (!inputEnded) {
                    int index = codec.dequeueInputBuffer(10_000);
                    if (index >= 0) {
                        ByteBuffer input = codec.getInputBuffer(index);
                        if (input == null) throw new Code13SharePolicy.Reject("codec_input_missing");
                        input.clear();
                        long pts = extractor.getSampleTime();
                        if (pts > 24L * 60 * 60 * 1_000_000)
                            throw new Code13SharePolicy.Reject("audio_timestamp_rejected");
                        if (firstInputUs == Long.MIN_VALUE && pts >= 0) firstInputUs = pts;
                        if (pts < 0 || packets >= 4096 ||
                                pts - firstInputUs >= Code13SharePolicy.AUDIO_WINDOW_US) {
                            codec.queueInputBuffer(index, 0, 0, Math.max(0, pts), MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEnded = true;
                        } else {
                            long size = extractor.getSampleSize();
                            int overhead = "vorbis".equals(codecKind) ? 4 : 0;
                            if (size < 0 || size + overhead > Code13SharePolicy.MAX_INPUT_BYTES ||
                                    size + overhead > input.capacity())
                                throw new Code13SharePolicy.Reject("audio_packet_rejected");
                            if ((extractor.getSampleFlags() & (MediaExtractor.SAMPLE_FLAG_ENCRYPTED |
                                    MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME)) != 0)
                                throw new Code13SharePolicy.Reject("audio_packet_flags_rejected");
                            // Consume the platform extractor's codec-ready Vorbis packet as-is.
                            // Reserve up to four suffix bytes; never synthesize page/sample counts.
                            int read = extractor.readSampleData(input, 0);
                            if (read < 0 || (read != size && !(overhead == 4 && read == size + overhead)))
                                throw new Code13SharePolicy.Reject("audio_packet_rejected");
                            codec.queueInputBuffer(index, 0, read, pts, 0);
                            extractor.advance();
                            packets++;
                        }
                    }
                }
                int index = codec.dequeueOutputBuffer(info, 10_000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat output = codec.getOutputFormat();
                    pcmEncoding = output.containsKey(MediaFormat.KEY_PCM_ENCODING) ?
                            output.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
                    if (pcmEncoding != AudioFormat.ENCODING_PCM_16BIT)
                        throw new Code13SharePolicy.Reject("pcm_encoding_rejected");
                    int channels = integer(output, MediaFormat.KEY_CHANNEL_COUNT);
                    int rate = integer(output, MediaFormat.KEY_SAMPLE_RATE);
                    if (channels < 1 || channels > 8 || rate < 8000 || rate > 192000)
                        throw new Code13SharePolicy.Reject("pcm_format_rejected");
                    audio.put("channels", channels).put("sampleRate", rate).put("pcmEncoding", "pcm16");
                } else if (index >= 0) {
                    try {
                        if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            if (firstOutputUs == Long.MIN_VALUE) firstOutputUs = info.presentationTimeUs;
                            if (info.presentationTimeUs - firstOutputUs >= Code13SharePolicy.AUDIO_WINDOW_US ||
                                    pcmBytes + info.size > Code13SharePolicy.MAX_PCM_BYTES || outputBuffers >= 512) {
                                limited = true;
                            } else {
                                if (info.size > 256 * 1024 || pcmEncoding != AudioFormat.ENCODING_PCM_16BIT)
                                    throw new Code13SharePolicy.Reject("pcm_buffer_rejected");
                                ByteBuffer output = codec.getOutputBuffer(index);
                                if (output == null) throw new Code13SharePolicy.Reject("codec_output_missing");
                                long[] stats = Code13SharePolicy.pcm16(output, info.offset, info.size);
                                samples += stats[0]; nonzero += stats[1]; pcmBytes += info.size;
                                outputBuffers++;
                            }
                        }
                        outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    } finally { codec.releaseOutputBuffer(index, false); } // Never render/play.
                }
            }
            audio.put("status", samples > 0 ? "decoded_bounded_sample" : "no_pcm_output")
                    .put("stopReason", limited ? "sample_limit" : "end_of_window_or_stream")
                    .put("decoded", samples > 0).put("signal", samples == 0 ? "not_decoded" :
                            nonzero == 0 ? "all_zero_in_sample" : "nonzero_in_sample");
            return samples > 0;
        } catch (Code13SharePolicy.Reject e) {
            audio.put("status", e.code);
            return false;
        } catch (RuntimeException e) {
            audio.put("status", "decode_failed");
            return false;
        } finally {
            audio.put("decodedSampleValues", samples).put("nonzeroSampleValues", nonzero).put("pcmBytes", pcmBytes);
            if (codec != null) {
                boolean released = true;
                try { if (started) codec.stop(); } catch (RuntimeException e) { released = false; }
                finally { try { codec.release(); } catch (RuntimeException e) { released = false; } }
                if (!released) {
                    audio.put("status", "decoder_release_failed").put("decoded", false);
                    report.put("mediaChecksPassed", false);
                }
            }
        }
    }

    static void checkTime(long deadline) throws Code13SharePolicy.Reject {
        if (SystemClock.elapsedRealtime() >= deadline || Thread.currentThread().isInterrupted())
            throw new Code13SharePolicy.Reject("time_limit");
    }

    private static int integer(MediaFormat format, String key) {
        return format.containsKey(key) ? format.getInteger(key) : 0;
    }

    private Code13MediaProbe() { }
}
