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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The files the service ships beside the API, and the page its file server writes when a request
 * names a file it does not have.
 *
 * The page is the one the original's file server wrote, down to its wording and its character
 * set, because a client that asks for a file that is not there reads it.
 */
public final class StaticFiles {

    private static final String STYLE =
            "div.header {font-family:Tahoma,Arial,sans-serif;color:white;" +
            "background-color:#003300;font-size:22px;-moz-border-radius-topleft: 10px;" +
            "border-top-left-radius: 10px;-moz-border-radius-topright: 10px;" +
            "border-top-right-radius: 10px;padding-left: 5px}" +
            "div.body {font-family:Tahoma,Arial,sans-serif;color:black;" +
            "background-color:#FFFFCC;font-size:16px;padding-top:10px;padding-bottom:10px;" +
            "padding-left:10px}" +
            "div.footer {font-family:Tahoma,Arial,sans-serif;color:white;" +
            "background-color:#666633;font-size:14px;" +
            "-moz-border-radius-bottomleft: 10px;border-bottom-left-radius: 10px;" +
            "-moz-border-radius-bottomright: 10px;border-bottom-right-radius: 10px;" +
            "padding-left: 5px}" +
            "BODY {font-family:Tahoma,Arial,sans-serif;color:black;background-color:white;}" +
            "B {font-family:Tahoma,Arial,sans-serif;color:black;}" +
            "A {color : black;}" +
            "HR {color : #999966;}";

    private static final String SERVER = "Grizzly 3.0.1";

    private static final Map<String, String> TYPES = new LinkedHashMap<>();

    static {
        TYPES.put("html", "text/html");
        TYPES.put("htm", "text/html");
        TYPES.put("css", "text/css");
        TYPES.put("js", "text/javascript");
        TYPES.put("json", "application/json");
        TYPES.put("map", "application/json");
        TYPES.put("png", "image/png");
        TYPES.put("gif", "image/gif");
        TYPES.put("jpg", "image/jpeg");
        TYPES.put("jpeg", "image/jpeg");
        TYPES.put("svg", "image/svg+xml");
        TYPES.put("ico", "image/x-icon");
        TYPES.put("txt", "text/plain");
        TYPES.put("xml", "application/xml");
    }

    private final File root;
    private final String prefix;

    public StaticFiles(String root, String prefix) {
        this.root = new File(root);
        this.prefix = prefix;
    }

    /** The path this handler answers below. */
    public String prefix() {
        return this.prefix;
    }

    /** One file of the set, or nothing when the request names no file this holds. */
    public Served serve(String requestPath) throws IOException {
        String relative = requestPath.substring(this.prefix.length());
        while (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        File file = relative.isEmpty() ? this.root : new File(this.root, relative);
        if (file.isDirectory()) {
            /*
             * A directory is answered with the file inside it only when the request named the
             * directory with its separator; without it the request named no file at all.
             */
            if (!requestPath.endsWith("/")) {
                return null;
            }
            file = new File(file, "index.html");
        }
        if (!file.isFile() || !file.getCanonicalPath()
                                   .startsWith(this.root.getCanonicalFile().getPath())) {
            return null;
        }
        return new Served(Files.readAllBytes(file.toPath()), contentType(file.getName()),
                          file.lastModified());
    }

    private static String contentType(String name) {
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
        return TYPES.getOrDefault(extension, "application/octet-stream");
    }

    /** The page a request for a file that is not there is answered with. */
    public static byte[] notFoundPage(String requestPath) {
        String page = "<html><head><title>" + SERVER + "</title><style><!--" + STYLE +
                      "--></style> </head><body><div class=\"header\">Not Found</div>" +
                      "<div class=\"body\">Resource identified by path '" + requestPath +
                      "', does not exist.</div><div class=\"footer\">" + SERVER +
                      "</div></body></html>";
        return page.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** The media type the page a missing file is answered with carries. */
    public static String notFoundType() {
        return "text/html;charset=ISO-8859-1";
    }

    /** One file of the set, with what its answer carries. */
    public static final class Served {

        private final byte[] content;
        private final String contentType;
        private final long lastModified;

        Served(byte[] content, String contentType, long lastModified) {
            this.content = content;
            this.contentType = contentType;
            this.lastModified = lastModified;
        }

        public byte[] content() {
            return this.content;
        }

        public String contentType() {
            return this.contentType;
        }

        public long lastModified() {
            return this.lastModified;
        }
    }
}
