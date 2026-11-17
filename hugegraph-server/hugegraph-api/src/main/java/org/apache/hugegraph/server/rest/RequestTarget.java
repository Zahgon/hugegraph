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

/**
 * Whether a request target is one the service will look at at all.
 *
 * The original refused a target before anything matched it, with nothing written back, in three
 * cases: an escape it could not read, an escape that stands for a path separator or for a zero
 * byte, and a path whose dot segments either end it or would walk above its root. Those answers
 * come from the layer that reads the target rather than from the API, so they are reproduced
 * here, where the target is first seen.
 */
public final class RequestTarget {

    private RequestTarget() {
    }

    /**
     * The path the layer under the API picks a handler by: the escapes resolved and the dot
     * segments walked. The API itself still sees the target as it was written, which is why the
     * two are not the same string.
     */
    public static String handlerPath(String rawPath) {
        String[] segments = rawPath.split("/", -1);
        java.util.List<String> kept = new java.util.ArrayList<>();
        for (int i = 1; i < segments.length; i++) {
            String segment = RestUriInfo.decode(segments[i]);
            if (segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (!kept.isEmpty()) {
                    kept.remove(kept.size() - 1);
                }
                continue;
            }
            kept.add(segment);
        }
        return "/" + String.join("/", kept);
    }

    /** Whether the service will look at this raw path. */
    public static boolean acceptable(String rawPath) {
        return !escapeRefused(rawPath) && !dotSegmentRefused(rawPath);
    }

    /**
     * An escape that cannot be read, or one that stands for a path separator or a zero byte. Such
     * a target names a segment the path could not have held in the first place.
     */
    private static boolean escapeRefused(String rawPath) {
        for (int i = 0; i < rawPath.length(); i++) {
            if (rawPath.charAt(i) != '%') {
                continue;
            }
            if (i + 2 >= rawPath.length()) {
                return true;
            }
            int high = Character.digit(rawPath.charAt(i + 1), 16);
            int low = Character.digit(rawPath.charAt(i + 2), 16);
            if (high < 0 || low < 0) {
                return true;
            }
            int value = high * 16 + low;
            if (value == '/' || value == 0) {
                return true;
            }
            i += 2;
        }
        return false;
    }

    /**
     * A path that ends in a dot segment, or whose dot segments would walk above its root.
     *
     * The ending is read from the target as it was written, while the walk is read from the
     * segments once their escapes have been resolved, which is how the original read them.
     */
    private static boolean dotSegmentRefused(String rawPath) {
        if (rawPath.equals("/.") || rawPath.equals("/..") ||
            rawPath.endsWith("/.") || rawPath.endsWith("/..")) {
            return true;
        }
        String[] segments = rawPath.split("/", -1);
        int depth = 0;
        for (int i = 1; i < segments.length; i++) {
            String segment = RestUriInfo.decode(segments[i]);
            if (segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                depth--;
                if (depth < 0) {
                    return true;
                }
            } else {
                depth++;
            }
        }
        return false;
    }
}
