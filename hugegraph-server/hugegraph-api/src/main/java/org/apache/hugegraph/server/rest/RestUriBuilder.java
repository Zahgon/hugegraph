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

import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.ws.rs.core.UriBuilder;

/**
 * Builds the request URI the pre-matching filters rewrite a request to.
 *
 * Only the parts of a URI the service writes are carried, and a value handed in is percent
 * encoded the way the original runtime encoded it: the characters a path may hold are left
 * alone and everything else is written as its bytes in hexadecimal.
 */
public final class RestUriBuilder extends UriBuilder {

    private static final String PATH_SAFE =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789" +
            "-._~!$&'()*+,;=:@/%";

    private String scheme;
    private String userInfo;
    private String host;
    private int port = -1;
    private String path = "";
    private String query;
    private String fragment;

    public RestUriBuilder() {
    }

    /** Starts from an existing URI, keeping every part of it. */
    public static RestUriBuilder of(URI uri) {
        RestUriBuilder builder = new RestUriBuilder();
        builder.scheme = uri.getScheme();
        builder.userInfo = uri.getRawUserInfo();
        builder.host = uri.getHost();
        builder.port = uri.getPort();
        builder.path = uri.getRawPath() == null ? "" : uri.getRawPath();
        builder.query = uri.getRawQuery();
        builder.fragment = uri.getRawFragment();
        return builder;
    }

    @Override
    public UriBuilder clone() {
        RestUriBuilder copy = new RestUriBuilder();
        copy.scheme = this.scheme;
        copy.userInfo = this.userInfo;
        copy.host = this.host;
        copy.port = this.port;
        copy.path = this.path;
        copy.query = this.query;
        copy.fragment = this.fragment;
        return copy;
    }

    @Override
    public UriBuilder uri(URI uri) {
        if (uri.getScheme() != null) {
            this.scheme = uri.getScheme();
        }
        if (uri.getRawUserInfo() != null) {
            this.userInfo = uri.getRawUserInfo();
        }
        if (uri.getHost() != null) {
            this.host = uri.getHost();
        }
        if (uri.getPort() != -1) {
            this.port = uri.getPort();
        }
        if (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) {
            this.path = uri.getRawPath();
        }
        if (uri.getRawQuery() != null) {
            this.query = uri.getRawQuery();
        }
        if (uri.getRawFragment() != null) {
            this.fragment = uri.getRawFragment();
        }
        return this;
    }

    @Override
    public UriBuilder uri(String uriTemplate) {
        try {
            return this.uri(new URI(encodePath(uriTemplate)));
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid uri '" + uriTemplate + "'", e);
        }
    }

    @Override
    public UriBuilder scheme(String scheme) {
        this.scheme = scheme;
        return this;
    }

    @Override
    public UriBuilder schemeSpecificPart(String part) {
        throw new UnsupportedOperationException("a scheme specific part");
    }

    @Override
    public UriBuilder userInfo(String userInfo) {
        this.userInfo = userInfo;
        return this;
    }

    @Override
    public UriBuilder host(String host) {
        this.host = host;
        return this;
    }

    @Override
    public UriBuilder port(int port) {
        this.port = port;
        return this;
    }

    @Override
    public UriBuilder replacePath(String path) {
        this.path = path == null ? "" : encodePath(path);
        return this;
    }

    @Override
    public UriBuilder path(String path) {
        if (path == null || path.isEmpty()) {
            return this;
        }
        String encoded = encodePath(path);
        if (this.path.isEmpty()) {
            this.path = encoded;
        } else if (this.path.endsWith("/") || encoded.startsWith("/")) {
            this.path = this.path + encoded;
        } else {
            this.path = this.path + "/" + encoded;
        }
        return this;
    }

    @Override
    public UriBuilder path(Class resource) {
        jakarta.ws.rs.Path annotation =
                (jakarta.ws.rs.Path) resource.getAnnotation(jakarta.ws.rs.Path.class);
        return annotation == null ? this : this.path(annotation.value());
    }

    @Override
    public UriBuilder path(Class resource, String method) {
        for (Method candidate : resource.getMethods()) {
            if (candidate.getName().equals(method)) {
                return this.path(candidate);
            }
        }
        return this;
    }

    @Override
    public UriBuilder path(Method method) {
        jakarta.ws.rs.Path annotation = method.getAnnotation(jakarta.ws.rs.Path.class);
        return annotation == null ? this : this.path(annotation.value());
    }

    @Override
    public UriBuilder segment(String... segments) {
        for (String segment : segments) {
            this.path(segment);
        }
        return this;
    }

    @Override
    public UriBuilder replaceMatrix(String matrix) {
        throw new UnsupportedOperationException("matrix parameters");
    }

    @Override
    public UriBuilder matrixParam(String name, Object... values) {
        throw new UnsupportedOperationException("matrix parameters");
    }

    @Override
    public UriBuilder replaceMatrixParam(String name, Object... values) {
        throw new UnsupportedOperationException("matrix parameters");
    }

    @Override
    public UriBuilder replaceQuery(String query) {
        this.query = query;
        return this;
    }

    @Override
    public UriBuilder queryParam(String name, Object... values) {
        StringBuilder text = new StringBuilder(this.query == null ? "" : this.query);
        for (Object value : values) {
            if (text.length() > 0) {
                text.append('&');
            }
            text.append(name).append('=').append(value);
        }
        this.query = text.toString();
        return this;
    }

    @Override
    public UriBuilder replaceQueryParam(String name, Object... values) {
        List<String> kept = new ArrayList<>();
        if (this.query != null) {
            for (String pair : this.query.split("&")) {
                if (!pair.equals(name) && !pair.startsWith(name + "=")) {
                    kept.add(pair);
                }
            }
        }
        this.query = String.join("&", kept);
        return values == null ? this : this.queryParam(name, values);
    }

    @Override
    public UriBuilder fragment(String fragment) {
        this.fragment = fragment;
        return this;
    }

    @Override
    public UriBuilder resolveTemplate(String name, Object value) {
        return this.resolveOne(name, value);
    }

    @Override
    public UriBuilder resolveTemplate(String name, Object value, boolean encodeSlash) {
        return this.resolveOne(name, value);
    }

    @Override
    public UriBuilder resolveTemplateFromEncoded(String name, Object value) {
        return this.resolveOne(name, value);
    }

    @Override
    public UriBuilder resolveTemplates(Map<String, Object> values) {
        values.forEach(this::resolveOne);
        return this;
    }

    @Override
    public UriBuilder resolveTemplates(Map<String, Object> values, boolean encodeSlash) {
        return this.resolveTemplates(values);
    }

    @Override
    public UriBuilder resolveTemplatesFromEncoded(Map<String, Object> values) {
        return this.resolveTemplates(values);
    }

    private UriBuilder resolveOne(String name, Object value) {
        String replacement = value == null ? "" : value.toString();
        this.path = this.path.replace("{" + name + "}", replacement);
        if (this.query != null) {
            this.query = this.query.replace("{" + name + "}", replacement);
        }
        return this;
    }

    @Override
    public URI build(Object... values) {
        return this.assemble();
    }

    @Override
    public URI build(Object[] values, boolean encodeSlashInPath) {
        return this.assemble();
    }

    @Override
    public URI buildFromEncoded(Object... values) {
        return this.assemble();
    }

    @Override
    public URI buildFromMap(Map<String, ?> values) {
        return this.assemble();
    }

    @Override
    public URI buildFromMap(Map<String, ?> values, boolean encodeSlashInPath) {
        return this.assemble();
    }

    @Override
    public URI buildFromEncodedMap(Map<String, ?> values) {
        return this.assemble();
    }

    @Override
    public String toTemplate() {
        return this.assemble().toString();
    }

    private URI assemble() {
        StringBuilder text = new StringBuilder();
        if (this.scheme != null) {
            text.append(this.scheme).append("://");
            if (this.userInfo != null) {
                text.append(this.userInfo).append('@');
            }
            if (this.host != null) {
                text.append(this.host);
            }
            if (this.port != -1) {
                text.append(':').append(this.port);
            }
        }
        text.append(this.path);
        if (this.query != null && !this.query.isEmpty()) {
            text.append('?').append(this.query);
        }
        if (this.fragment != null && !this.fragment.isEmpty()) {
            text.append('#').append(this.fragment);
        }
        return URI.create(text.toString());
    }

    /** Percent encodes everything a path may not hold, leaving what is already encoded alone. */
    static String encodePath(String value) {
        StringBuilder encoded = new StringBuilder(value.length());
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (byte raw : bytes) {
            char ch = (char) (raw & 0xFF);
            if (PATH_SAFE.indexOf(ch) >= 0) {
                encoded.append(ch);
            } else {
                encoded.append('%');
                encoded.append(Character.toUpperCase(Character.forDigit((raw >> 4) & 0xF, 16)));
                encoded.append(Character.toUpperCase(Character.forDigit(raw & 0xF, 16)));
            }
        }
        return encoded.toString();
    }
}
