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
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Variant;

/** Builds the answer a resource method or an exception mapper returns. */
public final class RestResponseBuilder extends Response.ResponseBuilder {

    private int status = 200;
    private String reason;
    private Object entity;
    private Annotation[] annotations = new Annotation[0];
    private MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

    @Override
    public Response build() {
        MultivaluedMap<String, Object> copy = new MultivaluedHashMap<>();
        for (Map.Entry<String, List<Object>> header : this.headers.entrySet()) {
            copy.addAll(header.getKey(), header.getValue());
        }
        return new RestResponse(this.status, this.reason, copy, this.entity, this.annotations);
    }

    @Override
    public Response.ResponseBuilder clone() {
        RestResponseBuilder copy = new RestResponseBuilder();
        copy.status = this.status;
        copy.reason = this.reason;
        copy.entity = this.entity;
        copy.annotations = this.annotations;
        for (Map.Entry<String, List<Object>> header : this.headers.entrySet()) {
            copy.headers.addAll(header.getKey(), header.getValue());
        }
        return copy;
    }

    @Override
    public Response.ResponseBuilder status(int status) {
        this.status = status;
        return this;
    }

    @Override
    public Response.ResponseBuilder status(int status, String reason) {
        this.status = status;
        this.reason = reason;
        return this;
    }

    @Override
    public Response.ResponseBuilder entity(Object entity) {
        this.entity = entity;
        return this;
    }

    @Override
    public Response.ResponseBuilder entity(Object entity, Annotation[] annotations) {
        this.entity = entity;
        this.annotations = annotations == null ? new Annotation[0] : annotations;
        return this;
    }

    @Override
    public Response.ResponseBuilder allow(String... methods) {
        this.headers.remove("Allow");
        if (methods != null && methods.length > 0) {
            // One header, holding the methods in the order the set of them is walked in.
            this.headers.add("Allow", String.join(",", methods));
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder allow(Set<String> methods) {
        return methods == null ? this.allow((String[]) null)
                               : this.allow(methods.toArray(new String[0]));
    }

    @Override
    public Response.ResponseBuilder cacheControl(CacheControl cacheControl) {
        return this.replaceHeader("Cache-Control", cacheControl);
    }

    @Override
    public Response.ResponseBuilder encoding(String encoding) {
        return this.replaceHeader("Content-Encoding", encoding);
    }

    @Override
    public Response.ResponseBuilder header(String name, Object value) {
        if (value == null) {
            this.headers.remove(name);
        } else {
            this.headers.add(name, value);
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder replaceAll(MultivaluedMap<String, Object> headers) {
        this.headers = new MultivaluedHashMap<>();
        if (headers != null) {
            for (Map.Entry<String, List<Object>> header : headers.entrySet()) {
                this.headers.addAll(header.getKey(), header.getValue());
            }
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder language(String language) {
        return this.replaceHeader("Content-Language", language);
    }

    @Override
    public Response.ResponseBuilder language(Locale language) {
        return this.replaceHeader("Content-Language", language);
    }

    @Override
    public Response.ResponseBuilder type(MediaType type) {
        return this.replaceHeader("Content-Type", type);
    }

    @Override
    public Response.ResponseBuilder type(String type) {
        return this.replaceHeader("Content-Type", type == null ? null : MediaTypes.parse(type));
    }

    @Override
    public Response.ResponseBuilder variant(Variant variant) {
        if (variant == null) {
            return this.type((MediaType) null).language((String) null).encoding(null);
        }
        this.type(variant.getMediaType());
        this.language(variant.getLanguage());
        return this.encoding(variant.getEncoding());
    }

    @Override
    public Response.ResponseBuilder contentLocation(URI location) {
        return this.replaceHeader("Content-Location", location);
    }

    @Override
    public Response.ResponseBuilder cookie(NewCookie... cookies) {
        if (cookies == null) {
            this.headers.remove("Set-Cookie");
            return this;
        }
        for (NewCookie cookie : cookies) {
            this.headers.add("Set-Cookie", cookie);
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder expires(Date expires) {
        return this.replaceHeader("Expires", expires);
    }

    @Override
    public Response.ResponseBuilder lastModified(Date lastModified) {
        return this.replaceHeader("Last-Modified", lastModified);
    }

    @Override
    public Response.ResponseBuilder location(URI location) {
        return this.replaceHeader("Location", location);
    }

    @Override
    public Response.ResponseBuilder tag(EntityTag tag) {
        return this.replaceHeader("ETag", tag);
    }

    @Override
    public Response.ResponseBuilder tag(String tag) {
        return this.replaceHeader("ETag", tag);
    }

    @Override
    public Response.ResponseBuilder variants(Variant... variants) {
        return this;
    }

    @Override
    public Response.ResponseBuilder variants(List<Variant> variants) {
        return this;
    }

    @Override
    public Response.ResponseBuilder links(Link... links) {
        this.headers.remove("Link");
        if (links != null) {
            for (Link link : links) {
                this.headers.add("Link", link);
            }
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder link(URI uri, String rel) {
        return this.header("Link", "<" + uri + ">; rel=\"" + rel + "\"");
    }

    @Override
    public Response.ResponseBuilder link(String uri, String rel) {
        return this.header("Link", "<" + uri + ">; rel=\"" + rel + "\"");
    }

    private Response.ResponseBuilder replaceHeader(String name, Object value) {
        this.headers.remove(name);
        if (value != null) {
            this.headers.add(name, value);
        }
        return this;
    }
}
