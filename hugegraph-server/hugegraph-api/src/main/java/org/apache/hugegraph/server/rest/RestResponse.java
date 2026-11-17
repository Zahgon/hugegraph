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
import java.net.URI;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

/**
 * The answer a resource method or an exception mapper builds, before the serving layer writes it.
 *
 * Only what the service actually builds is carried: a status, headers, an entity and the
 * annotations the entity was declared with. The annotations matter because the response filters
 * and the writer interceptors read them.
 */
public class RestResponse extends Response {

    private final int status;
    private final String reason;
    private final MultivaluedMap<String, Object> headers;
    private final Object entity;
    private final Annotation[] annotations;

    RestResponse(int status, String reason, MultivaluedMap<String, Object> headers,
                 Object entity, Annotation[] annotations) {
        this.status = status;
        this.reason = reason;
        this.headers = headers;
        this.entity = entity;
        this.annotations = annotations;
    }

    public Annotation[] entityAnnotations() {
        return this.annotations;
    }

    @Override
    public int getStatus() {
        return this.status;
    }

    @Override
    public StatusType getStatusInfo() {
        Status known = Status.fromStatusCode(this.status);
        if (known != null && (this.reason == null || this.reason.equals(known.getReasonPhrase()))) {
            return known;
        }
        return new StatusType() {

            @Override
            public int getStatusCode() {
                return RestResponse.this.status;
            }

            @Override
            public Status.Family getFamily() {
                return Status.Family.familyOf(RestResponse.this.status);
            }

            @Override
            public String getReasonPhrase() {
                return RestResponse.this.reason;
            }
        };
    }

    @Override
    public Object getEntity() {
        return this.entity;
    }

    @Override
    public <T> T readEntity(Class<T> entityType) {
        return read(this.entity, entityType, this.getMediaType());
    }

    /** Reads an entity still held as bytes as the type a caller asks for. */
    static <T> T read(Object entity, Class<T> entityType, jakarta.ws.rs.core.MediaType type) {
        if (entity == null || entityType.isInstance(entity)) {
            return entityType.cast(entity);
        }
        byte[] bytes = entity instanceof byte[]
                       ? (byte[]) entity
                       : entity.toString().getBytes(EntityIo.charset(type));
        if (entityType == String.class) {
            return entityType.cast(new String(bytes, EntityIo.charset(type)));
        }
        try {
            return EntityIo.mapper().readValue(bytes, entityType);
        } catch (java.io.IOException e) {
            throw new jakarta.ws.rs.ProcessingException(e);
        }
    }

    @Override
    public <T> T readEntity(GenericType<T> entityType) {
        throw new UnsupportedOperationException("reading a typed entity");
    }

    @Override
    public <T> T readEntity(Class<T> entityType, Annotation[] annotations) {
        return read(this.entity, entityType, this.getMediaType());
    }

    @Override
    public <T> T readEntity(GenericType<T> entityType, Annotation[] annotations) {
        throw new UnsupportedOperationException("reading a typed entity");
    }

    @Override
    public boolean hasEntity() {
        return this.entity != null;
    }

    @Override
    public boolean bufferEntity() {
        return false;
    }

    @Override
    public void close() {
        // The entity is already in memory, so there is nothing to release.
    }

    @Override
    public MediaType getMediaType() {
        Object type = this.headers.getFirst("Content-Type");
        if (type == null) {
            return null;
        }
        return type instanceof MediaType ? (MediaType) type : MediaTypes.parse(type.toString());
    }

    @Override
    public Locale getLanguage() {
        Object value = this.headers.getFirst("Content-Language");
        return value == null ? null : Locale.forLanguageTag(value.toString());
    }

    @Override
    public int getLength() {
        Object value = this.headers.getFirst("Content-Length");
        return value == null ? -1 : Integer.parseInt(value.toString());
    }

    @Override
    public Set<String> getAllowedMethods() {
        Set<String> allowed = new LinkedHashSet<>();
        List<Object> values = this.headers.get("Allow");
        if (values != null) {
            for (Object value : values) {
                for (String method : value.toString().split(",")) {
                    allowed.add(method.trim().toUpperCase());
                }
            }
        }
        return allowed;
    }

    @Override
    public Map<String, NewCookie> getCookies() {
        return Collections.emptyMap();
    }

    @Override
    public EntityTag getEntityTag() {
        return null;
    }

    @Override
    public Date getDate() {
        return null;
    }

    @Override
    public Date getLastModified() {
        return null;
    }

    @Override
    public URI getLocation() {
        Object value = this.headers.getFirst("Location");
        return value == null ? null : URI.create(value.toString());
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
    public MultivaluedMap<String, Object> getMetadata() {
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
}
