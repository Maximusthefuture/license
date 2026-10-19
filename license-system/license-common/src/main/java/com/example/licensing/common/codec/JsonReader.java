package com.example.licensing.common.codec;

import java.util.Optional;

/**
 * A tiny, dependency-free reader for pulling a single named string field out
 * of an arbitrary JSON document - e.g. extracting "uid" from a Kubernetes
 * API response - without needing a full JSON library on the classpath.
 *
 * <p>This is deliberately not a general JSON parser: it scans for the first
 * occurrence of {@code "key": "value"} (handling standard string escaping)
 * at any nesting depth and returns that value. It is good enough for
 * reading one known field out of a response you don't otherwise need to
 * process; reach for a real JSON library if you need more than that. For
 * the license wire format itself (where exact, canonical bytes matter
 * because they're signed), see {@code PayloadCodec} / the package-private
 * {@code MiniJson} instead - this class is for reading other systems'
 * JSON, not for anything that gets signed or verified.
 */
public final class JsonReader {

    private JsonReader() {
    }

    public static Optional<String> findStringField(String json, String key) {
        String needle = "\"" + key + "\"";
        int searchFrom = 0;
        while (true) {
            int keyIndex = json.indexOf(needle, searchFrom);
            if (keyIndex < 0) {
                return Optional.empty();
            }
            int afterKey = keyIndex + needle.length();
            int colon = indexOfNonWhitespace(json, afterKey);
            if (colon < 0 || json.charAt(colon) != ':') {
                searchFrom = afterKey;
                continue;
            }
            int valueStart = indexOfNonWhitespace(json, colon + 1);
            if (valueStart < 0 || json.charAt(valueStart) != '"') {
                searchFrom = afterKey;
                continue;
            }
            return Optional.of(readQuotedString(json, valueStart));
        }
    }

    private static int indexOfNonWhitespace(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i < s.length() ? i : -1;
    }

    private static String readQuotedString(String s, int quoteIndex) {
        StringBuilder sb = new StringBuilder();
        int i = quoteIndex + 1;
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') {
                break;
            }
            if (c == '\\' && i < s.length()) {
                char esc = s.charAt(i++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 <= s.length()) {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                    }
                    default -> sb.append(esc);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
