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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A compiled JAX-RS URI path template.
 *
 * The template language is the one the resource classes are written in: literal text with
 * {@code {name}} variables that match one path segment, and {@code {name:regex}} variables that
 * match whatever their regular expression matches. A template keeps the counts the matching order
 * is decided by, so that two templates that both match a request are ordered the way the original
 * runtime ordered them.
 */
public final class UriTemplate {

    /**
     * More literal characters first, then more variables, then more variables that carry their own
     * regular expression. This is the order the original runtime sorted candidate templates in.
     */
    public static final Comparator<UriTemplate> COMPARATOR = (a, b) -> {
        int cmp = Integer.compare(b.literalChars, a.literalChars);
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(b.names.size(), a.names.size());
        if (cmp != 0) {
            return cmp;
        }
        return Integer.compare(b.explicitRegexCount, a.explicitRegexCount);
    };

    private static final String DEFAULT_REGEX = "[^/]+?";
    private static final Pattern VARIABLE = Pattern.compile("\\{\\s*(\\w[-\\w\\.]*)\\s*(:\\s*((?:[^{}]|\\{[^{}]*\\})*))?\\}");

    private final String template;
    private final Pattern pattern;
    private final List<String> names;
    private final int literalChars;
    private final int explicitRegexCount;

    private UriTemplate(String template, Pattern pattern, List<String> names,
                        int literalChars, int explicitRegexCount) {
        this.template = template;
        this.pattern = pattern;
        this.names = names;
        this.literalChars = literalChars;
        this.explicitRegexCount = explicitRegexCount;
    }

    public static UriTemplate compile(String template) {
        String normalized = normalize(template);
        StringBuilder regex = new StringBuilder();
        List<String> names = new ArrayList<>();
        int literals = 0;
        int explicit = 0;
        Matcher matcher = VARIABLE.matcher(normalized);
        int last = 0;
        while (matcher.find()) {
            String literal = normalized.substring(last, matcher.start());
            literals += literal.length();
            regex.append(Pattern.quote(literal));
            names.add(matcher.group(1));
            String custom = matcher.group(3);
            if (custom == null || custom.trim().isEmpty()) {
                regex.append('(').append(DEFAULT_REGEX).append(')');
            } else {
                explicit++;
                regex.append('(').append(custom.trim()).append(')');
            }
            last = matcher.end();
        }
        String tail = normalized.substring(last);
        literals += tail.length();
        regex.append(Pattern.quote(tail));
        /*
         * The trailing group holds whatever is left of the path, so that a resource can hand the
         * rest of the request to one of its own sub-paths. A template that is empty matches at
         * the root, where the whole path is left over and no separator precedes it.
         */
        regex.append(normalized.isEmpty() ? "(.*)" : "(/.*)?");
        return new UriTemplate(normalized, Pattern.compile(regex.toString()), names,
                               literals, explicit);
    }

    /** Collapses the separators the templates are written with to one leading-slash-free form. */
    private static String normalize(String template) {
        String value = template == null ? "" : template.trim();
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    public String template() {
        return this.template;
    }

    public List<String> names() {
        return this.names;
    }

    public int literalChars() {
        return this.literalChars;
    }

    /**
     * Matches an already normalized path (no leading slash) against this template.
     *
     * @return null when the template does not match, otherwise the match, whose remainder is the
     *         part of the path the template did not consume ("" when it consumed all of it)
     */
    public Match match(String path) {
        Matcher matcher = this.pattern.matcher(path);
        if (!matcher.matches()) {
            return null;
        }
        List<String> values = new ArrayList<>(this.names.size());
        for (int i = 0; i < this.names.size(); i++) {
            values.add(matcher.group(i + 1));
        }
        String remainder = matcher.group(matcher.groupCount());
        if (remainder == null) {
            remainder = "";
        } else if (remainder.startsWith("/")) {
            remainder = remainder.substring(1);
        }
        return new Match(this.names, values, remainder);
    }

    @Override
    public String toString() {
        return this.template;
    }

    /** What one successful template match captured. */
    public static final class Match {

        private final List<String> names;
        private final List<String> values;
        private final String remainder;

        Match(List<String> names, List<String> values, String remainder) {
            this.names = names;
            this.values = values;
            this.remainder = remainder;
        }

        public List<String> names() {
            return this.names;
        }

        public List<String> values() {
            return this.values;
        }

        public String remainder() {
            return this.remainder;
        }
    }
}
