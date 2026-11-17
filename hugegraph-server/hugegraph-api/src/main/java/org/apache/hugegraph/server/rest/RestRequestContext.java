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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.core.Variant;

/** The request as the filters and the interceptors of the module see it. */
public final class RestRequestContext implements jakarta.ws.rs.container.ContainerRequestContext {

    private final MultivaluedMap<String, String> headers;
    private final Map<String, Object> properties = new HashMap<>();
    private final RestUriInfo uriInfo;
    private final PeerInfo peer;
    private String method;
    private InputStream entityStream;
    private SecurityContext securityContext;
    private Response abortedWith;

    public RestRequestContext(String method, RestUriInfo uriInfo,
                              MultivaluedMap<String, String> headers,
                              byte[] entity, PeerInfo peer) {
        this.method = method;
        this.uriInfo = uriInfo;
        this.headers = headers;
        this.entityStream = new ByteArrayInputStream(entity == null ? new byte[0] : entity);
        this.peer = peer;
    }

    /** Where the request came from, which the authentication filter reports. */
    public PeerInfo peer() {
        return this.peer;
    }

    /** The answer a filter stopped the request with, or null when none did. */
    public Response abortedWith() {
        return this.abortedWith;
    }

    @Override
    public Object getProperty(String name) {
        return this.properties.get(name);
    }

    @Override
    public Collection<String> getPropertyNames() {
        return Collections.unmodifiableCollection(new ArrayList<>(this.properties.keySet()));
    }

    @Override
    public void setProperty(String name, Object object) {
        this.properties.put(name, object);
    }

    @Override
    public void removeProperty(String name) {
        this.properties.remove(name);
    }

    @Override
    public UriInfo getUriInfo() {
        return this.uriInfo;
    }

    @Override
    public void setRequestUri(URI requestUri) {
        this.uriInfo.requestUri(requestUri);
    }

    @Override
    public void setRequestUri(URI baseUri, URI requestUri) {
        this.uriInfo.requestUri(requestUri);
    }

    @Override
    public Request getRequest() {
        return new RequestView();
    }

    @Override
    public String getMethod() {
        return this.method;
    }

    @Override
    public void setMethod(String method) {
        this.method = method;
    }

    @Override
    public MultivaluedMap<String, String> getHeaders() {
        return this.headers;
    }

    /**
     * The value of a header. A header a request repeats reads as its values joined by commas,
     * which is what a resource method that declares one value sees.
     */
    @Override
    public String getHeaderString(String name) {
        List<String> values = this.headers.get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return String.join(",", values);
    }

    @Override
    public Date getDate() {
        return null;
    }

    @Override
    public Locale getLanguage() {
        String value = this.getHeaderString("Content-Language");
        return value == null ? null : Locale.forLanguageTag(value);
    }

    @Override
    public int getLength() {
        String value = this.getHeaderString("Content-Length");
        if (value == null) {
            return -1;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public MediaType getMediaType() {
        String value = this.getHeaderString("Content-Type");
        return value == null ? null : MediaTypes.parse(value);
    }

    @Override
    public List<MediaType> getAcceptableMediaTypes() {
        List<MediaType> types = new ArrayList<>();
        for (MediaTypes.Accepted accepted : MediaTypes.accepted(this.headers.get("Accept"))) {
            types.add(accepted.type());
        }
        return types;
    }

    @Override
    public List<Locale> getAcceptableLanguages() {
        return Collections.singletonList(Locale.getDefault());
    }

    @Override
    public Map<String, Cookie> getCookies() {
        Map<String, Cookie> cookies = new HashMap<>();
        List<String> values = this.headers.get("Cookie");
        if (values == null) {
            return cookies;
        }
        for (String value : values) {
            for (String pair : value.split(";")) {
                int equals = pair.indexOf('=');
                if (equals > 0) {
                    String name = pair.substring(0, equals).trim();
                    cookies.put(name, new Cookie(name, pair.substring(equals + 1).trim()));
                }
            }
        }
        return cookies;
    }

    @Override
    public boolean hasEntity() {
        try {
            return this.entityStream.available() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public InputStream getEntityStream() {
        return this.entityStream;
    }

    @Override
    public void setEntityStream(InputStream input) {
        this.entityStream = input;
    }

    @Override
    public SecurityContext getSecurityContext() {
        return this.securityContext;
    }

    @Override
    public void setSecurityContext(SecurityContext context) {
        this.securityContext = context;
    }

    @Override
    public void abortWith(Response response) {
        this.abortedWith = response;
    }

    /** Where a request came from, as the authentication filter reports it. */
    public static final class PeerInfo {

        private final String remoteAddress;
        private final int remotePort;
        private final String requestUri;

        public PeerInfo(String remoteAddress, int remotePort, String requestUri) {
            this.remoteAddress = remoteAddress;
            this.remotePort = remotePort;
            this.requestUri = requestUri;
        }

        public String getRemoteAddr() {
            return this.remoteAddress;
        }

        public int getRemotePort() {
            return this.remotePort;
        }

        public String getRequestURI() {
            return this.requestUri;
        }
    }

    /** The small part of the request the specification's own request type exposes. */
    private final class RequestView implements Request {

        @Override
        public String getMethod() {
            return RestRequestContext.this.method;
        }

        @Override
        public Variant selectVariant(List<Variant> variants) {
            return variants.isEmpty() ? null : variants.get(0);
        }

        @Override
        public Response.ResponseBuilder evaluatePreconditions(EntityTag tag) {
            return null;
        }

        @Override
        public Response.ResponseBuilder evaluatePreconditions(Date lastModified) {
            return null;
        }

        @Override
        public Response.ResponseBuilder evaluatePreconditions(Date lastModified, EntityTag tag) {
            return null;
        }

        @Override
        public Response.ResponseBuilder evaluatePreconditions() {
            return null;
        }
    }
}
