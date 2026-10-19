package com.example.licensing.common.codec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A minimal, dependency-free JSON reader/writer covering exactly what the
 * license wire format needs: flat objects whose values are strings,
 * integers, string arrays, or null.
 *
 * <p>This is deliberately not a general-purpose JSON library. The signed
 * payload format has no external parsing dependency that could change
 * behavior underneath an already-deployed signature scheme, and the whole
 * project builds with nothing beyond the JDK - fitting for a component
 * whose client side has to run fully offline anyway.
 *
 * <p>Keys are always written in sorted order, and so are string-array
 * elements, so the same field values always produce the same bytes
 * regardless of the order fields were set. That determinism is what the
 * signature is computed over - see PayloadCodec.
 */
final class MiniJson {

    private MiniJson() {
    }

    static String writeObject(Map<String, Object> fields) {
        Map<String, Object> sorted = new TreeMap<>(fields);
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(quote(e.getKey())).append(':');
            writeValue(sb, e.getValue());
        }
        return sb.append('}').toString();
    }

    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            sb.append(quote(s));
        } else if (value instanceof Integer || value instanceof Long) {
            sb.append(value);
        } else if (value instanceof Collection<?> c) {
            List<String> items = c.stream().map(String::valueOf).sorted().toList();
            sb.append('[');
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(quote(items.get(i)));
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("unsupported JSON value type: " + value.getClass());
        }
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    // ---- parsing ----

    static Map<String, Object> parseObject(String json) {
        Parser p = new Parser(json);
        p.skipWhitespace();
        Map<String, Object> result = p.parseObjectValue();
        p.skipWhitespace();
        if (!p.atEnd()) {
            throw new IllegalArgumentException("trailing content after JSON object");
        }
        return result;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        void skipWhitespace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        char peek() {
            if (pos >= s.length()) {
                throw new IllegalArgumentException("unexpected end of JSON");
            }
            return s.charAt(pos);
        }

        void expect(char c) {
            if (peek() != c) {
                throw new IllegalArgumentException("expected '" + c + "' at position " + pos);
            }
            pos++;
        }

        Map<String, Object> parseObjectValue() {
            expect('{');
            skipWhitespace();
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            if (peek() == '}') {
                pos++;
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                Object value = parseValue();
                result.put(key, value);
                skipWhitespace();
                char next = peek();
                if (next == ',') {
                    pos++;
                } else if (next == '}') {
                    pos++;
                    break;
                } else {
                    throw new IllegalArgumentException("expected ',' or '}' at position " + pos);
                }
            }
            return result;
        }

        Object parseValue() {
            char c = peek();
            if (c == '"') {
                return parseString();
            }
            if (c == '[') {
                return parseArray();
            }
            if (c == 'n') {
                expectLiteral("null");
                return null;
            }
            if (c == '-' || Character.isDigit(c)) {
                return parseNumber();
            }
            throw new IllegalArgumentException("unexpected character '" + c + "' at position " + pos);
        }

        List<String> parseArray() {
            expect('[');
            skipWhitespace();
            List<String> items = new ArrayList<>();
            if (peek() == ']') {
                pos++;
                return items;
            }
            while (true) {
                skipWhitespace();
                items.add(parseString());
                skipWhitespace();
                char next = peek();
                if (next == ',') {
                    pos++;
                } else if (next == ']') {
                    pos++;
                    break;
                } else {
                    throw new IllegalArgumentException("expected ',' or ']' at position " + pos);
                }
            }
            return items;
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw new IllegalArgumentException("unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    break;
                }
                if (c == '\\') {
                    if (pos >= s.length()) {
                        throw new IllegalArgumentException("unterminated escape");
                    }
                    char esc = s.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > s.length()) {
                                throw new IllegalArgumentException("truncated unicode escape");
                            }
                            String hex = s.substring(pos, pos + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("invalid escape: \\" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Long parseNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                pos++;
            }
            return Long.parseLong(s.substring(start, pos));
        }

        void expectLiteral(String literal) {
            if (pos + literal.length() > s.length() || !s.regionMatches(pos, literal, 0, literal.length())) {
                throw new IllegalArgumentException("expected '" + literal + "' at position " + pos);
            }
            pos += literal.length();
        }
    }
}
