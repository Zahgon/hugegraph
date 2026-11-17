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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;

/**
 * Running the module's own reader and writer interceptors round the entity of a request and of
 * an answer, so that the content encodings they add and expect stay as they were.
 */
public final class Interceptors {

    private Interceptors() {
    }

    /** Runs the reader interceptors that the matched method's annotations bind, then reads. */
    public static Object read(List<ReaderInterceptor> interceptors, InputStream input,
                              Type type, MediaType mediaType, Annotation[] annotations,
                              MultivaluedMap<String, String> headers) throws IOException {
        ReaderChain chain = new ReaderChain(interceptors, input, type, mediaType,
                                            annotations, headers);
        return chain.proceed();
    }

    /** Runs the writer interceptors, then writes the entity onto the stream. */
    public static void write(List<WriterInterceptor> interceptors, Object entity,
                             OutputStream output, MediaType mediaType, Annotation[] annotations,
                             MultivaluedMap<String, Object> headers) throws IOException {
        WriterChain chain = new WriterChain(interceptors, entity, output, mediaType,
                                            annotations, headers);
        chain.proceed();
        chain.finish();
    }

    /** The properties and the entity description both interceptor kinds share. */
    private abstract static class BaseContext {

        private final Map<String, Object> properties = new HashMap<>();
        Annotation[] annotations;
        Class<?> rawType;
        Type type;
        MediaType mediaType;

        BaseContext(Annotation[] annotations, Type type, MediaType mediaType) {
            this.annotations = annotations == null ? new Annotation[0] : annotations;
            this.type = type;
            this.rawType = type instanceof Class ? (Class<?>) type : Object.class;
            this.mediaType = mediaType;
        }

        public Object getProperty(String name) {
            return this.properties.get(name);
        }

        public Collection<String> getPropertyNames() {
            return Collections.unmodifiableCollection(new ArrayList<>(this.properties.keySet()));
        }

        public void setProperty(String name, Object object) {
            this.properties.put(name, object);
        }

        public void removeProperty(String name) {
            this.properties.remove(name);
        }

        public Annotation[] getAnnotations() {
            return this.annotations;
        }

        public void setAnnotations(Annotation[] annotations) {
            this.annotations = annotations;
        }

        public Class<?> getType() {
            return this.rawType;
        }

        public void setType(Class<?> type) {
            this.rawType = type;
        }

        public Type getGenericType() {
            return this.type;
        }

        public void setGenericType(Type genericType) {
            this.type = genericType;
        }

        public MediaType getMediaType() {
            return this.mediaType;
        }

        public void setMediaType(MediaType mediaType) {
            this.mediaType = mediaType;
        }
    }

    /** Walks the reader interceptors and finally reads the entity. */
    private static final class ReaderChain extends BaseContext
            implements ReaderInterceptorContext {

        private final List<ReaderInterceptor> interceptors;
        private final MultivaluedMap<String, String> headers;
        private InputStream input;
        private int next;

        ReaderChain(List<ReaderInterceptor> interceptors, InputStream input, Type type,
                    MediaType mediaType, Annotation[] annotations,
                    MultivaluedMap<String, String> headers) {
            super(annotations, type, mediaType);
            this.interceptors = interceptors;
            this.input = input;
            this.headers = headers;
        }

        @Override
        public Object proceed() throws IOException {
            if (this.next < this.interceptors.size()) {
                return this.interceptors.get(this.next++).aroundReadFrom(this);
            }
            return EntityIo.read(this.input, this.getGenericType(), this.getMediaType());
        }

        @Override
        public InputStream getInputStream() {
            return this.input;
        }

        @Override
        public void setInputStream(InputStream input) {
            this.input = input;
        }

        @Override
        public MultivaluedMap<String, String> getHeaders() {
            return this.headers;
        }
    }

    /** Walks the writer interceptors and finally writes the entity. */
    private static final class WriterChain extends BaseContext
            implements WriterInterceptorContext {

        private final List<WriterInterceptor> interceptors;
        private final MultivaluedMap<String, Object> headers;
        private Object entity;
        private OutputStream output;
        private final OutputStream target;
        private int next;

        WriterChain(List<WriterInterceptor> interceptors, Object entity, OutputStream output,
                    MediaType mediaType, Annotation[] annotations,
                    MultivaluedMap<String, Object> headers) {
            super(annotations, entity == null ? Object.class : entity.getClass(), mediaType);
            this.interceptors = interceptors;
            this.entity = entity;
            this.output = output;
            this.target = output;
            this.headers = headers;
        }

        @Override
        public void proceed() throws IOException {
            if (this.next < this.interceptors.size()) {
                this.interceptors.get(this.next++).aroundWriteTo(this);
                return;
            }
            EntityIo.write(this.entity, this.getMediaType(), this.output);
        }

        /** Closes whatever an interceptor wrapped the stream in, leaving the target open. */
        void finish() throws IOException {
            if (this.output != this.target) {
                this.output.close();
            }
        }

        @Override
        public Object getEntity() {
            return this.entity;
        }

        @Override
        public void setEntity(Object entity) {
            this.entity = entity;
        }

        @Override
        public OutputStream getOutputStream() {
            return this.output;
        }

        @Override
        public void setOutputStream(OutputStream output) {
            this.output = output;
        }

        @Override
        public MultivaluedMap<String, Object> getHeaders() {
            return this.headers;
        }
    }
}
