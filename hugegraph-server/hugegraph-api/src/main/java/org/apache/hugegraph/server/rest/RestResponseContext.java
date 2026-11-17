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

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.io.OutputStream;
import java.net.URI;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

/** The answer as the response filters of the module see it, before it is written. */
public final class RestResponseContext implements jakarta.ws.rs.container.ContainerResponseContext {

    private int status;
    private String reason;
    private Object entity;
    private Annotation[] annotations;
    private final MultivaluedMap<String, Object> headers;
    private OutputStream entityStream;

    public RestResponseContext(int status, String reason, Object entity,
                               Annotation[] annotations,
                               MultivaluedMap<String, Object> headers) {
        this.status = status;
        this.reason = reason;
        this.entity = entity;
        this.annotations = annotations == null ? new Annotation[0] : annotations;
        this.headers = headers == null ? new MultivaluedHashMap<>() : headers;
    }

    /** The reason phrase the answer carries, or null when the status decides it. */
    public String reason() {
        return this.reason;
    }

    @Override
    public int getStatus() {
        return this.status;
    }

    @Override
    public void setStatus(int code) {
        this.status = code;
        this.reason = null;
    }

    @Override
    public Response.StatusType getStatusInfo() {
        return Response.Status.fromStatusCode(this.status);
    }

    @Override
    public void setStatusInfo(Response.StatusType statusInfo) {
        this.status = statusInfo.getStatusCode();
        this.reason = statusInfo.getReasonPhrase();
    }

    @Override
    public MultivaluedMap<String, Object> getHeaders() {
        return this.headers;
    }

    @Override
    public MultivaluedMap<String, String> getStringHeaders() {
        MultivaluedMap<String, String> strings = new MultivaluedHashMap<>();
        for (Map.Entry<String, List<Object>> header : this.headers.entrySet()) {
            for (Object value : header.getValue()) {
                strings.add(header.getKey(), Headers.toText(value));
            }
        }
        return strings;
    }

    @Override
    public String getHeaderString(String name) {
        List<Object> values = this.headers.get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (Object value : values) {
            if (text.length() > 0) {
                text.append(',');
            }
            text.append(Headers.toText(value));
        }
        return text.toString();
    }

    @Override
    public Set<String> getAllowedMethods() {
        Set<String> allowed = new LinkedHashSet<>();
        List<Object> values = this.headers.get("Allow");
        if (values != null) {
            for (Object value : values) {
                for (String method : Headers.toText(value).split(",")) {
                    allowed.add(method.trim().toUpperCase());
                }
            }
        }
        return allowed;
    }

    @Override
    public Date getDate() {
        return null;
    }

    @Override
    public Locale getLanguage() {
        return null;
    }

    @Override
    public int getLength() {
        Object value = this.headers.getFirst("Content-Length");
        return value == null ? -1 : Integer.parseInt(Headers.toText(value));
    }

    @Override
    public MediaType getMediaType() {
        Object value = this.headers.getFirst("Content-Type");
        if (value == null) {
            return null;
        }
        return value instanceof MediaType ? (MediaType) value
                                          : MediaTypes.parse(Headers.toText(value));
    }

    @Override
    public Map<String, NewCookie> getCookies() {
        return new java.util.HashMap<>();
    }

    @Override
    public EntityTag getEntityTag() {
        return null;
    }

    @Override
    public Date getLastModified() {
        return null;
    }

    @Override
    public URI getLocation() {
        Object value = this.headers.getFirst("Location");
        return value == null ? null : URI.create(Headers.toText(value));
    }

    @Override
    public Set<Link> getLinks() {
        return new HashSet<>();
    }

    @Override
    public boolean hasLink(String relation) {
        return false;
    }

    @Override
    public Link getLink(String relation) {
        return null;
    }

    @Override
    public Link.Builder getLinkBuilder(String relation) {
        return null;
    }

    @Override
    public boolean hasEntity() {
        return this.entity != null;
    }

    @Override
    public Object getEntity() {
        return this.entity;
    }

    @Override
    public Class<?> getEntityClass() {
        return this.entity == null ? null : this.entity.getClass();
    }

    @Override
    public Type getEntityType() {
        return this.getEntityClass();
    }

    @Override
    public void setEntity(Object entity) {
        this.entity = entity;
    }

    @Override
    public void setEntity(Object entity, Annotation[] annotations, MediaType mediaType) {
        this.entity = entity;
        this.annotations = annotations == null ? new Annotation[0] : annotations;
        if (mediaType != null) {
            this.headers.putSingle("Content-Type", mediaType);
        }
    }

    /** The annotations the resource method carried, which the status filter reads. */
    @Override
    public Annotation[] getEntityAnnotations() {
        return this.annotations;
    }

    @Override
    public OutputStream getEntityStream() {
        return this.entityStream;
    }

    @Override
    public void setEntityStream(OutputStream outputStream) {
        this.entityStream = outputStream;
    }
}
