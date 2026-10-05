import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * T101 maintainer signing tool for rule-import documents (JDK builtin Ed25519, no third-party
 * crypto). Canonicalization mirrors the app's ImportIntegrity.canonicalJson EXACTLY: object keys
 * sorted, insignificant whitespace dropped, whole numbers printed without a fraction — the
 * signature therefore survives cosmetic JSON edits exactly like the digest flow does.
 *
 * Subcommands:
 *   keygen <keysDir>                          -> writes ed25519-maintainer.private/.public (base64)
 *   sign-file <rules.json> <private> <public> -> rewrites the document embedding the envelope, and
 *                                                writes the detached signature to <rules.json>.sig
 *   verify <rules.json> <public>              -> re-verifies a document carrying the envelope
 *
 * Key id: "pb-k-" + first 8 hex of SHA-256 over the X509 public key bytes.
 */
public final class SignRules {
    public static final String ENVELOPE_KEY = "signature";
    public static final String ALGORITHM = "Ed25519";

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: keygen <dir> | sign-file <json> <private> <public> | verify <json> <public>");
            System.exit(2);
        }
        switch (args[0]) {
            case "keygen" -> keygen(Path.of(args[1]));
            case "sign-file" -> signFile(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
            case "verify" -> {
                boolean ok = verify(Path.of(args[1]), Path.of(args[2]));
                System.out.println(ok ? "VERIFIED" : "REJECTED");
                if (!ok) System.exit(1);
            }
            default -> { System.err.println("unknown subcommand " + args[0]); System.exit(2); }
        }
    }

    private static void keygen(Path dir) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM);
        KeyPair pair = generator.generateKeyPair();
        Files.createDirectories(dir);
        Base64.Encoder b64 = Base64.getEncoder();
        Path priv = dir.resolve("ed25519-maintainer.private");
        Path pub = dir.resolve("ed25519-maintainer.public");
        Files.writeString(priv, b64.encodeToString(pair.getPrivate().getEncoded()) + "\n");
        Files.writeString(pub, b64.encodeToString(pair.getPublic().getEncoded()) + "\n");
        System.out.println("keyid=" + keyId(pair.getPublic()));
        System.out.println("public=" + pub);
        System.out.println("private=" + priv + " (keep secret; gitignored)");
    }

    private static void signFile(Path json, Path privateKeyFile, Path publicKeyFile) throws Exception {
        String text = Files.readString(json, StandardCharsets.UTF_8);
        Object root = Json.parse(text);
        PrivateKey key = readPrivate(privateKeyFile);
        String keyId = keyId(readPublic(publicKeyFile));
        // Rebuild the document without the envelope, canonicalize, sign.
        Map<String, Object> document = asMap(root);
        Map<String, Object> bare = new TreeMap<>(document);
        Object removed = bare.remove(ENVELOPE_KEY);
        String canonical = Canonical.print(bare);
        byte[] sig = sign(canonical.getBytes(StandardCharsets.UTF_8), key);
        String value = Base64.getEncoder().encodeToString(sig);
        Map<String, Object> envelope = new TreeMap<>();
        envelope.put("algorithm", ALGORITHM);
        envelope.put("keyid", keyId);
        envelope.put("value", value);
        Map<String, Object> signed = new TreeMap<>(document);
        signed.put(ENVELOPE_KEY, envelope);
        Files.writeString(json, Json.dump(signed), StandardCharsets.UTF_8);
        Files.writeString(Path.of(json + ".sig"), value + "\n", StandardCharsets.UTF_8);
        System.out.println("keyid=" + keyId);
        System.out.println("signed=" + json + " detached=" + json + ".sig");
        if (removed != null) System.out.println("note: previous envelope replaced");
    }

    private static boolean verify(Path json, Path publicKeyFile) throws Exception {
        String text = Files.readString(json, StandardCharsets.UTF_8);
        Object root = Json.parse(text);
        if (!(root instanceof Map)) return false;
        Map<String, Object> document = (Map<String, Object>) root;
        Object envelopeRaw = document.get(ENVELOPE_KEY);
        if (!(envelopeRaw instanceof Map)) return false;
        Map<String, Object> envelope = (Map<String, Object>) envelopeRaw;
        if (!ALGORITHM.equals(envelope.get("algorithm"))) return false;
        byte[] sig = Base64.getDecoder().decode((String) envelope.get("value"));
        Map<String, Object> bare = new TreeMap<>(document);
        bare.remove(ENVELOPE_KEY);
        byte[] canonical = Canonical.print(bare).getBytes(StandardCharsets.UTF_8);
        Signature verifier = Signature.getInstance(ALGORITHM);
        verifier.initVerify(readPublic(publicKeyFile));
        verifier.update(canonical);
        return verifier.verify(sig);
    }

    // --- crypto helpers ---------------------------------------------------------------

    static byte[] sign(byte[] payload, PrivateKey key) throws Exception {
        Signature signer = Signature.getInstance(ALGORITHM);
        signer.initSign(key);
        signer.update(payload);
        return signer.sign();
    }

    static PrivateKey readPrivate(Path file) throws Exception {
        byte[] pkcs8 = Base64.getDecoder().decode(Files.readString(file).trim());
        return KeyFactory.getInstance(ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
    }

    static PublicKey readPublic(Path file) throws Exception {
        byte[] x509 = Base64.getDecoder().decode(Files.readString(file).trim());
        return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(x509));
    }

    static String keyId(PublicKey key) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < 4; i++) hex.append(String.format("%02x", digest[i]));
        return "pb-k-" + hex;
    }

    private static Map<String, Object> asMap(Object root) {
        if (root instanceof Map) return (Map<String, Object>) root;
        throw new IllegalArgumentException("document must be a JSON object");
    }

    // --- canonical printer mirroring ImportIntegrity.canonicalJson --------------------

    static final class Canonical {
        static String print(Object value) {
            StringBuilder out = new StringBuilder();
            print(value, out);
            return out.toString();
        }

        static void print(Object value, StringBuilder out) {
            if (value == null) { out.append("null"); return; }
            if (value instanceof String s) { printString(s, out); return; }
            if (value instanceof Boolean b) { out.append(b); return; }
            if (value instanceof Double d) {
                if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) {
                    out.append(d.longValue());
                } else out.append(d);
                return;
            }
            if (value instanceof Map) {
                out.append('{');
                List<String> keys = new ArrayList<>(((Map<?, ?>) value).keySet().stream().map(Object::toString).sorted().toList());
                for (int i = 0; i < keys.size(); i++) {
                    if (i > 0) out.append(',');
                    printString(keys.get(i), out);
                    out.append(':');
                    print(((Map<?, ?>) value).get(keys.get(i)), out);
                }
                out.append('}');
                return;
            }
            if (value instanceof List) {
                out.append('[');
                List<?> list = (List<?>) value;
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) out.append(',');
                    print(list.get(i), out);
                }
                out.append(']');
                return;
            }
            out.append("null");
        }

        static StringBuilder printString(String value, StringBuilder out) {
            out.append('"');
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    case '\b' -> out.append("\\b");
                    default -> {
                        if (c < ' ') out.append("\\u").append(String.format("%04x", (int) c));
                        else out.append(c);
                    }
                }
            }
            return out.append('"');
        }
    }

    // --- minimal JSON reader/writer (objects as TreeMap, arrays as ArrayList, numbers as Double) ----

    static final class Json {
        private final String text;
        private int i;

        private Json(String text) { this.text = text; }

        static Object parse(String text) {
            Json parser = new Json(text);
            parser.skip();
            Object value = parser.value();
            parser.skip();
            if (parser.i != text.length()) throw new IllegalArgumentException("trailing content");
            return value;
        }

        static String dump(Object value) {
            StringBuilder out = new StringBuilder();
            dump(value, out, 0);
            return out.toString();
        }

        private static void dump(Object value, StringBuilder out, int depth) {
            if (value == null) { out.append("null"); return; }
            if (value instanceof String s) { Canonical.printString(s, out); return; }
            if (value instanceof Double d) {
                if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) out.append(d.longValue());
                else out.append(d);
                return;
            }
            if (value instanceof Boolean b) { out.append(b); return; }
            String pad = "  ".repeat(depth + 1);
            String closePad = "  ".repeat(depth);
            if (value instanceof Map) {
                out.append("{\n");
                List<String> keys = new ArrayList<>(((Map<?, ?>) value).keySet().stream().map(Object::toString).sorted().toList());
                for (int k = 0; k < keys.size(); k++) {
                    out.append(pad);
                    Canonical.printString(keys.get(k), out).append(": ");
                    dump(((Map<?, ?>) value).get(keys.get(k)), out, depth + 1);
                    if (k < keys.size() - 1) out.append(",");
                    out.append('\n');
                }
                out.append(closePad).append('}');
                return;
            }
            if (value instanceof List) {
                out.append("[\n");
                List<?> list = (List<?>) value;
                for (int k = 0; k < list.size(); k++) {
                    out.append(pad);
                    dump(list.get(k), out, depth + 1);
                    if (k < list.size() - 1) out.append(",");
                    out.append('\n');
                }
                out.append(closePad).append(']');
                return;
            }
            out.append("null");
        }

        private void skip() { while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++; }

        private Object value() {
            if (i >= text.length()) throw new IllegalArgumentException("eof");
            char c = text.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': require("true"); return Boolean.TRUE;
                case 'f': require("false"); return Boolean.FALSE;
                case 'n': require("null"); return null;
                default: return number();
            }
        }

        private Map<String, Object> object() {
            i++;
            Map<String, Object> out = new TreeMap<>();
            while (true) {
                skip();
                if (text.charAt(i) == '}') { i++; return out; }
                if (text.charAt(i) == ',') { i++; continue; }
                String key = string();
                skip();
                if (text.charAt(i) != ':') throw new IllegalArgumentException("expected :");
                i++;
                skip();
                out.put(key, value());
                skip();
                if (i < text.length() && text.charAt(i) == ',') { i++; continue; }
                if (i < text.length() && text.charAt(i) == '}') { i++; return out; }
                throw new IllegalArgumentException("bad object");
            }
        }

        private List<Object> array() {
            i++;
            List<Object> out = new ArrayList<>();
            while (true) {
                skip();
                if (text.charAt(i) == ']') { i++; return out; }
                if (text.charAt(i) == ',') { i++; continue; }
                out.add(value());
                skip();
                if (i < text.length() && text.charAt(i) == ',') { i++; continue; }
                if (i < text.length() && text.charAt(i) == ']') { i++; return out; }
                throw new IllegalArgumentException("bad array");
            }
        }

        private String string() {
            if (text.charAt(i) != '"') throw new IllegalArgumentException("expected string");
            i++;
            StringBuilder out = new StringBuilder();
            while (i < text.length()) {
                char c = text.charAt(i++);
                if (c == '"') return out.toString();
                if (c == '\\') {
                    char e = text.charAt(i++);
                    switch (e) {
                        case '"', '\\', '/' -> out.append(e);
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> { out.append((char) Integer.parseInt(text.substring(i, i + 4), 16)); i += 4; }
                        default -> throw new IllegalArgumentException("bad escape");
                    }
                } else out.append(c);
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private Object number() {
            int start = i;
            while (i < text.length() && "+-0123456789.eE".indexOf(text.charAt(i)) >= 0) i++;
            return Double.parseDouble(text.substring(start, i));
        }

        private void require(String word) {
            if (!text.regionMatches(i, word, 0, word.length())) throw new IllegalArgumentException("bad literal");
            i += word.length();
        }
    }
}
