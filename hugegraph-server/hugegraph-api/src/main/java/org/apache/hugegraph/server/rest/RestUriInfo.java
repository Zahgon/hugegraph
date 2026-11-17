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

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;

/** What the filters and the resource methods read about the URI of the request being answered. */
public final class RestUriInfo implements UriInfo {

    private final URI baseUri;
    private URI requestUri;
    private MultivaluedMap<String, String> pathParameters = new MultivaluedHashMap<>();

    public RestUriInfo(URI baseUri, URI requestUri) {
        this.baseUri = baseUri;
        this.requestUri = requestUri;
    }

    /** Replaces the request a pre-matching filter rewrote. */
    public void requestUri(URI requestUri) {
        this.requestUri = requestUri;
    }

    /** Records the path values the matched template captured. */
    public void pathParameters(MultivaluedMap<String, String> parameters) {
        this.pathParameters = parameters;
    }

    /** The raw path of the request, with the base path removed and no leading separator. */
    public String rawPath() {
        String raw = this.requestUri.getRawPath();
        if (raw == null) {
            return "";
        }
        /*
         * Only the base path is removed. A separator that is left over means the request held an
         * empty segment, which matches no resource, so the answer is the same as for a path that
         * names nothing.
         */
        String base = this.baseUri.getRawPath();
        if (base != null && !base.isEmpty() && raw.startsWith(base)) {
            raw = raw.substring(base.length());
        } else if (raw.startsWith("/")) {
            raw = raw.substring(1);
        }
        return raw;
    }

    /**
     * The path a resource is matched against: the raw path without the matrix parameters a
     * client may write after a segment, which name no resource.
     */
    public String matchPath() {
        String raw = this.rawPath();
        if (raw.indexOf(';') < 0) {
            return raw;
        }
        StringBuilder matched = new StringBuilder();
        for (String segment : raw.split("/", -1)) {
            if (matched.length() > 0 || raw.startsWith("/")) {
                matched.append('/');
            }
            int semicolon = segment.indexOf(';');
            matched.append(semicolon < 0 ? segment : segment.substring(0, semicolon));
        }
        return matched.toString();
    }

    @Override
    public String getPath() {
        return this.getPath(true);
    }

    @Override
    public String getPath(boolean decode) {
        String raw = this.rawPath();
        return decode ? decode(raw) : raw;
    }

    @Override
    public List<PathSegment> getPathSegments() {
        return this.getPathSegments(true);
    }

    @Override
    public List<PathSegment> getPathSegments(boolean decode) {
        List<PathSegment> segments = new ArrayList<>();
        String raw = this.rawPath();
        if (raw.isEmpty()) {
            segments.add(new Segment("", Collections.emptyMap()));
            return segments;
        }
        for (String part : raw.split("/", -1)) {
            String value = part;
            Map<String, List<String>> matrix = new LinkedHashMap<>();
            int semicolon = value.indexOf(';');
            if (semicolon >= 0) {
                String tail = value.substring(semicolon + 1);
                value = value.substring(0, semicolon);
                for (String pair : tail.split(";")) {
                    if (pair.isEmpty()) {
                        continue;
                    }
                    int equals = pair.indexOf('=');
                    String name = equals < 0 ? pair : pair.substring(0, equals);
                    String item = equals < 0 ? "" : pair.substring(equals + 1);
                    matrix.computeIfAbsent(decode ? decode(name) : name,
                                           key -> new ArrayList<>())
                          .add(decode ? decode(item) : item);
                }
            }
            segments.add(new Segment(decode ? decode(value) : value, matrix));
        }
        return segments;
    }

    @Override
    public URI getRequestUri() {
        return this.requestUri;
    }

    @Override
    public UriBuilder getRequestUriBuilder() {
        return RestUriBuilder.of(this.requestUri);
    }

    @Override
    public URI getAbsolutePath() {
        String text = this.requestUri.toString();
        int query = text.indexOf('?');
        return query < 0 ? this.requestUri : URI.create(text.substring(0, query));
    }

    @Override
    public UriBuilder getAbsolutePathBuilder() {
        return RestUriBuilder.of(this.getAbsolutePath());
    }

    @Override
    public URI getBaseUri() {
        return this.baseUri;
    }

    @Override
    public UriBuilder getBaseUriBuilder() {
        return RestUriBuilder.of(this.baseUri);
    }

    @Override
    public MultivaluedMap<String, String> getPathParameters() {
        return this.pathParameters;
    }

    @Override
    public MultivaluedMap<String, String> getPathParameters(boolean decode) {
        return this.pathParameters;
    }

    @Override
    public MultivaluedMap<String, String> getQueryParameters() {
        return this.getQueryParameters(true);
    }

    @Override
    public MultivaluedMap<String, String> getQueryParameters(boolean decode) {
        MultivaluedMap<String, String> parameters = new MultivaluedHashMap<>();
        String query = this.requestUri.getRawQuery();
        if (query == null || query.isEmpty()) {
            return parameters;
        }
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            parameters.add(decode ? decodeQuery(name) : name,
                           decode ? decodeQuery(value) : value);
        }
        return parameters;
    }

    @Override
    public List<String> getMatchedURIs() {
        return Collections.emptyList();
    }

    @Override
    public List<String> getMatchedURIs(boolean decode) {
        return Collections.emptyList();
    }

    @Override
    public List<Object> getMatchedResources() {
        return Collections.emptyList();
    }

    @Override
    public URI resolve(URI uri) {
        return this.baseUri.resolve(uri);
    }

    @Override
    public URI relativize(URI uri) {
        return this.requestUri.relativize(uri);
    }

    /** Percent decoding for a path, where a plus sign is a plus sign. */
    public static String decode(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    /** Percent decoding for a query, where a plus sign is a space. */
    public static String decodeQuery(String value) {
        if (value.indexOf('%') < 0 && value.indexOf('+') < 0) {
            return value;
        }
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /** One path segment with the matrix parameters written after it. */
    private static final class Segment implements PathSegment {

        private final String path;
        private final MultivaluedMap<String, String> matrix = new MultivaluedHashMap<>();

        Segment(String path, Map<String, List<String>> matrix) {
            this.path = path;
            matrix.forEach(this.matrix::addAll);
        }

        @Override
        public String getPath() {
            return this.path;
        }

        @Override
        public MultivaluedMap<String, String> getMatrixParameters() {
            return this.matrix;
        }

        @Override
        public String toString() {
            return this.path;
        }
    }
}
