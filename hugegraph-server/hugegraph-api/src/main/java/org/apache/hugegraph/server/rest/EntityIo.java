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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.StreamingOutput;

/**
 * Reading a request entity and writing an answer entity.
 *
 * A request entity is read straight from its stream, so that the reader decides the character set
 * from the bytes it sees rather than from what the request declared, and so that a stream that
 * ends early still gives the value it had already carried. A failure to read one is answered as
 * plain text carrying the reader's own message, with the place in the stream it reached.
 *
 * An answer entity is written as the bytes of a string, the bytes of a byte array, whatever a
 * streaming entity writes, or the JSON of anything else.
 */
public final class EntityIo {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EntityIo() {
    }

    /** The reader this service writes JSON with, shared because it holds no request state. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** A request entity that is not readable, answered as the JSON reader answered it. */
    public static final class UnreadableEntity extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public UnreadableEntity(String message) {
            super(message);
        }
    }

    /** A request entity in a media type nothing reads, named the way the original named it. */
    public static final class NoReader extends RuntimeException implements ReportedThrowable {

        private static final long serialVersionUID = 1L;

        private final String text;

        public NoReader(MediaType mediaType, Type type) {
            super("");
            String name = "class " + typeName(type);
            this.text = "MessageBodyReader not found for media type=" +
                        MediaTypes.format(mediaType) + ", type=" + name +
                        ", genericType=" + name + ".";
        }

        private static String typeName(Type type) {
            return type instanceof Class ? ((Class<?>) type).getName() : type.getTypeName();
        }

        @Override
        public String reportedName() {
            return "org.glassfish.jersey.message.internal.MessageBodyProviderNotFoundException";
        }

        @Override
        public String getMessage() {
            return this.text;
        }

        @Override
        public String toString() {
            return this.reportedName() + ": " + this.text;
        }
    }

    /**
     * Reads a request entity as the type a resource method declares.
     *
     * @return null when the request carried no entity at all, which is what a resource method
     *         then sees
     */
    public static Object read(InputStream input, Type type, MediaType mediaType)
            throws IOException {
        PushbackInputStream stream = new PushbackInputStream(input, 1);
        int first = stream.read();
        while (first == ' ' || first == '\t' || first == '\r' || first == '\n') {
            first = stream.read();
        }
        if (first < 0) {
            // Nothing but blanks is nothing to read, and the method sees no entity at all.
            return null;
        }
        stream.unread(first);
        if (!isJson(mediaType)) {
            throw new NoReader(mediaType, type);
        }
        try {
            return MAPPER.readValue(stream, TypeFactory.defaultInstance().constructType(type));
        } catch (JsonProcessingException e) {
            throw new UnreadableEntity(e.getMessage());
        }
    }

    /** Whether a media type names JSON, whatever case it is written in. */
    public static boolean isJson(MediaType mediaType) {
        if (mediaType == null) {
            return false;
        }
        return "application".equalsIgnoreCase(mediaType.getType()) &&
               mediaType.getSubtype().toLowerCase().contains("json");
    }

    /** The character set an entity is written with: the declared one, or UTF-8. */
    public static Charset charset(MediaType mediaType) {
        if (mediaType != null) {
            String name = mediaType.getParameters().get("charset");
            if (name != null && !name.isEmpty()) {
                try {
                    return Charset.forName(name);
                } catch (RuntimeException e) {
                    return StandardCharsets.UTF_8;
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    /** Writes an answer entity onto a stream. */
    public static void write(Object entity, MediaType mediaType, OutputStream output)
            throws IOException {
        if (entity == null) {
            return;
        }
        if (entity instanceof byte[]) {
            output.write((byte[]) entity);
            return;
        }
        if (entity instanceof StreamingOutput) {
            ((StreamingOutput) entity).write(output);
            return;
        }
        if (entity instanceof File) {
            // A file is answered with its bytes, not with a description of it.
            Files.copy(((File) entity).toPath(), output);
            return;
        }
        if (entity instanceof String) {
            output.write(((String) entity).getBytes(charset(mediaType)));
            return;
        }
        output.write(MAPPER.writeValueAsBytes(entity));
    }
}
