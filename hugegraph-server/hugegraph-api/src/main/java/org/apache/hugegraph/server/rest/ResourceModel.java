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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

/**
 * The set of resource classes the service serves, in the shape the request dispatcher and the
 * profile listing both read.
 *
 * A root resource is one annotated class. Its own methods are the ones that carry an HTTP method
 * annotation and no path of their own; every other annotated method belongs to the child resource
 * named by its path. Child resources are kept in a plain hash map, which is also the order the
 * profile listing walks them in.
 */
public final class ResourceModel {

    private final List<RootResource> resources;

    private ResourceModel(List<RootResource> resources) {
        this.resources = resources;
    }

    public static ResourceModel scan(Collection<Class<?>> classes) {
        List<RootResource> resources = new ArrayList<>();
        for (Class<?> clazz : classes) {
            String path = pathOf(clazz);
            if (path == null) {
                continue;
            }
            resources.add(new RootResource(clazz, path));
        }
        resources.sort((a, b) -> UriTemplate.COMPARATOR.compare(a.template(), b.template()));
        return new ResourceModel(resources);
    }

    private static String pathOf(Class<?> clazz) {
        jakarta.ws.rs.Path path = clazz.getAnnotation(jakarta.ws.rs.Path.class);
        if (path != null) {
            return path.value();
        }
        for (Class<?> iface : clazz.getInterfaces()) {
            jakarta.ws.rs.Path inherited = iface.getAnnotation(jakarta.ws.rs.Path.class);
            if (inherited != null) {
                return inherited.value();
            }
        }
        return null;
    }

    /** Every root resource, most specific template first. */
    public List<RootResource> resources() {
        return this.resources;
    }

    /**
     * The methods of a class in the order reflection reports them, walking the class and then its
     * super classes, which is the order the profile listing is written in.
     */
    static List<Method> declaredMethods(Class<?> clazz) {
        List<Method> methods = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Class<?> current = clazz; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.isSynthetic() || method.isBridge() ||
                    Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                StringBuilder key = new StringBuilder(method.getName());
                for (Class<?> type : method.getParameterTypes()) {
                    key.append('|').append(type.getName());
                }
                if (seen.add(key.toString())) {
                    methods.add(method);
                }
            }
        }
        return methods;
    }

    static String httpMethodOf(Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            HttpMethod http = annotation.annotationType().getAnnotation(HttpMethod.class);
            if (http != null) {
                return http.value();
            }
        }
        return null;
    }

    /** One annotated class: its own methods and the child resources its paths name. */
    public static final class RootResource {

        private final Class<?> handler;
        private final String path;
        private final UriTemplate template;
        private final List<MethodModel> methods = new ArrayList<>();
        private final Map<String, ChildResource> children = new HashMap<>();
        private final List<ChildResource> sortedChildren;

        RootResource(Class<?> handler, String path) {
            this.handler = handler;
            this.path = path;
            this.template = UriTemplate.compile(path);
            for (Method method : declaredMethods(handler)) {
                String httpMethod = httpMethodOf(method);
                if (httpMethod == null) {
                    continue;
                }
                jakarta.ws.rs.Path own = method.getAnnotation(jakarta.ws.rs.Path.class);
                if (own == null) {
                    this.methods.add(new MethodModel(this, null, method, httpMethod));
                } else {
                    ChildResource child = this.children.get(own.value());
                    if (child == null) {
                        child = new ChildResource(this, own.value());
                        this.children.put(own.value(), child);
                    }
                    child.methods.add(new MethodModel(this, child, method, httpMethod));
                }
            }
            this.sortedChildren = new ArrayList<>(this.children.values());
            this.sortedChildren.sort((a, b) -> UriTemplate.COMPARATOR.compare(a.template, b.template));
        }

        public Class<?> handler() {
            return this.handler;
        }

        /** The path exactly as the class writes it, which the profile listing reports. */
        public String path() {
            return this.path;
        }

        public UriTemplate template() {
            return this.template;
        }

        public List<MethodModel> methods() {
            return this.methods;
        }

        /** The child resources in hash order, which the profile listing reports. */
        public Collection<ChildResource> children() {
            return this.children.values();
        }

        /** The child resources most specific first, which the dispatcher matches in. */
        public List<ChildResource> sortedChildren() {
            return this.sortedChildren;
        }
    }

    /** A path below a root resource, with the methods that answer on it. */
    public static final class ChildResource {

        private final RootResource parent;
        private final String path;
        private final UriTemplate template;
        private final List<MethodModel> methods = new ArrayList<>();

        ChildResource(RootResource parent, String path) {
            this.parent = parent;
            this.path = path;
            this.template = UriTemplate.compile(path);
        }

        public RootResource parent() {
            return this.parent;
        }

        public String path() {
            return this.path;
        }

        public UriTemplate template() {
            return this.template;
        }

        public List<MethodModel> methods() {
            return this.methods;
        }
    }

    /** One resource method: what it answers to, what it reads and writes, and its parameters. */
    public static final class MethodModel {

        private final RootResource root;
        private final ChildResource child;
        private final Method method;
        private final String httpMethod;
        private final List<MediaType> produces;
        private final List<MediaType> consumes;
        private final List<ParamModel> params;

        MethodModel(RootResource root, ChildResource child, Method method, String httpMethod) {
            this.root = root;
            this.child = child;
            this.method = method;
            this.httpMethod = httpMethod;
            this.produces = mediaTypes(method, Produces.class);
            this.consumes = mediaTypes(method, Consumes.class);
            this.params = params(method);
        }

        private static List<MediaType> mediaTypes(Method method, Class<? extends Annotation> type) {
            String[] values = null;
            Annotation own = method.getAnnotation(type);
            if (own == null) {
                own = method.getDeclaringClass().getAnnotation(type);
            }
            if (own instanceof Produces) {
                values = ((Produces) own).value();
            } else if (own instanceof Consumes) {
                values = ((Consumes) own).value();
            }
            if (values == null) {
                return Collections.emptyList();
            }
            List<MediaType> types = new ArrayList<>(values.length);
            for (String value : values) {
                types.add(MediaTypes.parse(value));
            }
            return types;
        }

        private static List<ParamModel> params(Method method) {
            List<ParamModel> params = new ArrayList<>();
            Parameter[] parameters = method.getParameters();
            Type[] generics = method.getGenericParameterTypes();
            for (int i = 0; i < parameters.length; i++) {
                params.add(new ParamModel(parameters[i], generics[i]));
            }
            return params;
        }

        public RootResource root() {
            return this.root;
        }

        public ChildResource child() {
            return this.child;
        }

        public Method method() {
            return this.method;
        }

        public String httpMethod() {
            return this.httpMethod;
        }

        public List<MediaType> produces() {
            return this.produces;
        }

        public List<MediaType> consumes() {
            return this.consumes;
        }

        public List<ParamModel> params() {
            return this.params;
        }

        /** The path this method answers on, as the profile listing writes it. */
        public String listedUrl() {
            if (this.child == null) {
                return this.root.path();
            }
            return this.root.path() + "/" + this.child.path();
        }
    }

    /** Where one method parameter comes from, and what it is converted to. */
    public static final class ParamModel {

        /** The places a parameter is read from. */
        public enum Source { PATH, QUERY, HEADER, CONTEXT, ENTITY }

        private final Source source;
        private final String sourceName;
        private final String defaultValue;
        private final Class<?> rawType;
        private final Type type;

        ParamModel(Parameter parameter, Type generic) {
            this.rawType = parameter.getType();
            this.type = generic;
            DefaultValue fallback = parameter.getAnnotation(DefaultValue.class);
            this.defaultValue = fallback == null ? null : fallback.value();
            PathParam path = parameter.getAnnotation(PathParam.class);
            QueryParam query = parameter.getAnnotation(QueryParam.class);
            HeaderParam header = parameter.getAnnotation(HeaderParam.class);
            if (path != null) {
                this.source = Source.PATH;
                this.sourceName = path.value();
            } else if (query != null) {
                this.source = Source.QUERY;
                this.sourceName = query.value();
            } else if (header != null) {
                this.source = Source.HEADER;
                this.sourceName = header.value();
            } else if (parameter.getAnnotation(Context.class) != null) {
                this.source = Source.CONTEXT;
                this.sourceName = null;
            } else {
                this.source = Source.ENTITY;
                this.sourceName = null;
            }
        }

        public Source source() {
            return this.source;
        }

        public String sourceName() {
            return this.sourceName;
        }

        public String defaultValue() {
            return this.defaultValue;
        }

        public Class<?> rawType() {
            return this.rawType;
        }

        public Type type() {
            return this.type;
        }

        /** The name of the type as the profile listing writes it. */
        public String typeName() {
            return this.type.getTypeName();
        }
    }

    /** The resource classes in a stable order, for the listings that walk all of them. */
    public static Map<Class<?>, RootResource> byClass(ResourceModel model) {
        Map<Class<?>, RootResource> byClass = new LinkedHashMap<>();
        for (RootResource resource : model.resources()) {
            byClass.put(resource.handler(), resource);
        }
        return byClass;
    }
}
