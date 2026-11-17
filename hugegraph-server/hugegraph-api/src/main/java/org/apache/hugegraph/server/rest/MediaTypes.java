/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You under
 * the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hugegraph.server.rest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.ws.rs.core.MediaType;

/**
 * Reading, matching and combining media types the way the original runtime did.
 *
 * Two rules here are easy to get wrong and are what the answers hang on: the parameters of a
 * concrete type a client will take are carried into the answer's content type, while the
 * parameters of a wildcard are not; and a quality value that is not a number makes the whole
 * request bad rather than ignoring the entry.
 */
public final class MediaTypes {

    /** Thrown when a header holds something that is not a media type list. */
    public static final class BadMediaTypeHeader extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public BadMediaTypeHeader(String message) {
            super(message);
        }
    }

    /** Thrown when a quality value is not a number, which makes the whole request bad. */
    public static final class BadQuality extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public BadQuality(String message) {
            super(message);
        }
    }

    /** One entry of an Accept header: a media type and the quality it was offered with. */
    public static final class Accepted {

        private final MediaType type;
        private final double quality;
        private final int order;

        Accepted(MediaType type, double quality, int order) {
            this.type = type;
            this.quality = quality;
            this.order = order;
        }

        public MediaType type() {
            return this.type;
        }

        public double quality() {
            return this.quality;
        }
    }

    private static final Comparator<Accepted> BY_PREFERENCE = (a, b) -> {
        int cmp = Double.compare(b.quality, a.quality);
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(specificity(b.type), specificity(a.type));
        if (cmp != 0) {
            return cmp;
        }
        return Integer.compare(a.order, b.order);
    };

    private MediaTypes() {
    }

    private static int specificity(MediaType type) {
        if (type.isWildcardType()) {
            return 0;
        }
        return type.isWildcardSubtype() ? 1 : 2;
    }

    /** Reads one media type, keeping its parameters in the order they were written. */
    public static MediaType parse(String value) {
        if (value == null) {
            throw new BadMediaTypeHeader("null media type");
        }
        String text = value.trim();
        if (text.isEmpty()) {
            throw new BadMediaTypeHeader("empty media type");
        }
        String[] parts = text.split(";");
        String full = parts[0].trim();
        int slash = full.indexOf('/');
        String type;
        String subtype;
        if (slash < 0) {
            /*
             * Text with no separator in it is read as a type whose subtype was left out, which
             * matches nothing a resource method writes; only an empty part is a bad header.
             */
            type = full;
            subtype = "*".equals(full) ? "*" : "";
        } else {
            type = full.substring(0, slash).trim();
            subtype = full.substring(slash + 1).trim();
            if (type.isEmpty() || subtype.isEmpty()) {
                throw new BadMediaTypeHeader("not a media type: " + text);
            }
        }
        if (type.isEmpty()) {
            throw new BadMediaTypeHeader("not a media type: " + text);
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 1; i < parts.length; i++) {
            String param = parts[i].trim();
            if (param.isEmpty()) {
                continue;
            }
            int equals = param.indexOf('=');
            if (equals < 0) {
                params.put(param.toLowerCase(), "");
                continue;
            }
            String name = param.substring(0, equals).trim().toLowerCase();
            String paramValue = param.substring(equals + 1).trim();
            if (paramValue.length() > 1 && paramValue.startsWith("\"") && paramValue.endsWith("\"")) {
                paramValue = paramValue.substring(1, paramValue.length() - 1);
            }
            params.put(name, paramValue);
        }
        return new MediaType(type, subtype, params);
    }

    /**
     * Reads the types a client will take, most wanted first. An absent or empty header means the
     * client will take anything.
     */
    public static List<Accepted> accepted(List<String> headers) {
        List<Accepted> accepted = new ArrayList<>();
        int order = 0;
        boolean sawEntry = false;
        if (headers != null) {
            for (String header : headers) {
                for (String entry : splitList(header)) {
                    sawEntry = true;
                    MediaType type = parse(entry);
                    accepted.add(new Accepted(type, quality(type), order++));
                }
            }
        }
        if (accepted.isEmpty() && !sawEntry) {
            accepted.add(new Accepted(MediaType.WILDCARD_TYPE, 1.0d, 0));
        }
        accepted.sort(BY_PREFERENCE);
        return accepted;
    }

    private static double quality(MediaType type) {
        String q = type.getParameters().get("q");
        if (q == null) {
            return 1.0d;
        }
        try {
            return Double.parseDouble(q);
        } catch (NumberFormatException e) {
            throw new BadQuality("quality value is not a number: " + q);
        }
    }

    /**
     * Splits a header on the commas that separate entries, leaving quoted text alone.
     *
     * A header that holds nothing but blanks carries no entry, which is a client that will take
     * anything. An entry that is empty is not a media type, except the one a trailing comma
     * leaves behind, which the original read past.
     */
    public static List<String> splitList(String header) {
        List<String> entries = new ArrayList<>();
        if (header == null || header.trim().isEmpty()) {
            return entries;
        }
        List<String> pieces = new ArrayList<>();
        boolean quoted = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < header.length(); i++) {
            char ch = header.charAt(i);
            if (ch == '"') {
                quoted = !quoted;
                current.append(ch);
            } else if (ch == ',' && !quoted) {
                pieces.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        pieces.add(current.toString().trim());
        if (!pieces.isEmpty() && pieces.get(pieces.size() - 1).isEmpty()) {
            pieces.remove(pieces.size() - 1);
        }
        for (String piece : pieces) {
            if (piece.isEmpty()) {
                throw new BadMediaTypeHeader("not a media type: " + header);
            }
            entries.add(piece);
        }
        return entries;
    }

    public static boolean compatible(MediaType a, MediaType b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.isWildcardType() || b.isWildcardType()) {
            return true;
        }
        if (!a.getType().equalsIgnoreCase(b.getType())) {
            return false;
        }
        if (a.getSubtype().isEmpty() || b.getSubtype().isEmpty()) {
            // A type whose subtype was left out names nothing a resource method writes.
            return false;
        }
        return a.isWildcardSubtype() || b.isWildcardSubtype() ||
               a.getSubtype().equalsIgnoreCase(b.getSubtype());
    }

    /**
     * The content type an answer carries: the type the method writes, with the parameters of the
     * entry the client offered when that entry named a concrete type.
     */
    public static MediaType combine(MediaType produced, MediaType accepted) {
        if (accepted == null || accepted.isWildcardType() || accepted.isWildcardSubtype()) {
            return produced;
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (Map.Entry<String, String> param : accepted.getParameters().entrySet()) {
            if (!"q".equals(param.getKey())) {
                params.put(param.getKey(), param.getValue());
            }
        }
        if (params.isEmpty()) {
            return produced;
        }
        return new MediaType(produced.getType(), produced.getSubtype(), params);
    }

    /** Writes a media type the way the answers carry it: no space after the separator. */
    public static String format(MediaType type) {
        StringBuilder text = new StringBuilder();
        text.append(type.getType()).append('/').append(type.getSubtype());
        for (Map.Entry<String, String> param : type.getParameters().entrySet()) {
            text.append(';').append(param.getKey()).append('=').append(param.getValue());
        }
        return text.toString();
    }
}
