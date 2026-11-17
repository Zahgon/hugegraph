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

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.hugegraph.HugeException;
import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.core.GraphManager;
import org.apache.hugegraph.api.filter.RedirectFilterDynamicFeature;
import org.apache.hugegraph.define.WorkLoad;

import io.swagger.v3.jaxrs2.integration.resources.OpenApiResource;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.DynamicFeature;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.WriterInterceptor;

/**
 * Everything the serving layer answers with: the resource classes, the filters and interceptors
 * the module registers, and the mappers that turn a failure into an answer.
 *
 * The classes come from the same packages the old wiring scanned, so adding a resource or a
 * provider to the module still needs nothing else, and the profile listing still sees the whole
 * set through the application it is given.
 */
public final class RestApplication extends Application {

    private static final String API_PACKAGE = "org.apache.hugegraph.api";

    private final HugeConfig config;
    private final WorkLoad workLoad = new WorkLoad();
    private volatile GraphManager manager;

    private final Set<Class<?>> classes = new HashSet<>();
    private final Set<Class<?>> registered = new HashSet<>();
    private final Map<Class<?>, Object> instances = new LinkedHashMap<>();
    private final ResourceModel model;
    private final List<ContainerRequestFilter> preMatchingFilters = new ArrayList<>();
    private final List<ContainerRequestFilter> requestFilters = new ArrayList<>();
    private final List<ContainerResponseFilter> responseFilters = new ArrayList<>();
    private final List<ReaderInterceptor> readerInterceptors = new ArrayList<>();
    private final List<WriterInterceptor> writerInterceptors = new ArrayList<>();
    private final List<ExceptionMapper<?>> exceptionMappers = new ArrayList<>();
    private final List<DynamicFeature> dynamicFeatures = new ArrayList<>();
    private final Map<ResourceModel.MethodModel, List<ContainerRequestFilter>> boundFilters =
            new java.util.concurrent.ConcurrentHashMap<>();

    public RestApplication(HugeConfig config) {
        this.config = config;
        List<Class<?>> scanned = new ArrayList<>(PackageScanner.scan(API_PACKAGE));
        scanned.add(OpenApiResource.class);
        List<Class<?>> resources = new ArrayList<>();
        for (Class<?> clazz : scanned) {
            if (clazz.isInterface() || clazz.isAnnotation() || clazz.isEnum() ||
                java.lang.reflect.Modifier.isAbstract(clazz.getModifiers())) {
                continue;
            }
            boolean isResource = hasPath(clazz);
            boolean isProvider = clazz.isAnnotationPresent(Provider.class);
            if (!isResource && !isProvider) {
                continue;
            }
            this.classes.add(clazz);
            if (isResource) {
                resources.add(clazz);
            }
            if (isProvider) {
                this.register(clazz);
            }
        }
        /*
         * What the old wiring registered by hand, and where each of them lives now:
         *  - the JSON reader and writer: EntityIo, run through the interceptor chain;
         *  - the role check of the security annotations: RestDispatcher, before a method runs;
         *  - the feature that puts the redirect filter in front of the methods that ask for it:
         *    registered here by name, exactly as the old wiring did;
         *  - the configuration, the graph manager and the work load: contextValue below;
         *  - the timing of the annotated methods: RestDispatcher, round the invocation;
         *  - the generated API documentation resource: the scanned set above.
         */
        this.register(RedirectFilterDynamicFeature.class);
        this.model = ResourceModel.scan(resources);
        for (Class<?> clazz : resources) {
            this.instances.computeIfAbsent(clazz, RestApplication::instantiate);
        }
        this.instances.values().forEach(this::injectFields);
        sortByPriority(this.preMatchingFilters, false);
        sortByPriority(this.requestFilters, false);
        sortByPriority(this.responseFilters, true);
        sortByPriority(this.readerInterceptors, false);
        sortByPriority(this.writerInterceptors, false);
    }

    private static boolean hasPath(Class<?> clazz) {
        if (clazz.isAnnotationPresent(jakarta.ws.rs.Path.class)) {
            return true;
        }
        for (Class<?> iface : clazz.getInterfaces()) {
            if (iface.isAnnotationPresent(jakarta.ws.rs.Path.class)) {
                return true;
            }
        }
        return false;
    }

    private void register(Class<?> clazz) {
        if (!this.registered.add(clazz)) {
            return;
        }
        Object provider = this.instances.computeIfAbsent(clazz, RestApplication::instantiate);
        if (provider instanceof ContainerRequestFilter) {
            if (clazz.isAnnotationPresent(PreMatching.class)) {
                this.preMatchingFilters.add((ContainerRequestFilter) provider);
            } else {
                this.requestFilters.add((ContainerRequestFilter) provider);
            }
        }
        if (provider instanceof ContainerResponseFilter) {
            this.responseFilters.add((ContainerResponseFilter) provider);
        }
        if (provider instanceof ReaderInterceptor) {
            this.readerInterceptors.add((ReaderInterceptor) provider);
        }
        if (provider instanceof WriterInterceptor) {
            this.writerInterceptors.add((WriterInterceptor) provider);
        }
        if (provider instanceof ExceptionMapper) {
            this.exceptionMappers.add((ExceptionMapper<?>) provider);
        }
        if (provider instanceof DynamicFeature) {
            this.dynamicFeatures.add((DynamicFeature) provider);
        }
    }

    /**
     * The request filters a resource method brings into play: the ones registered for everything,
     * and the ones a feature of the module adds in front of that method alone.
     */
    public List<ContainerRequestFilter> requestFiltersFor(ResourceModel.MethodModel model) {
        return this.boundFilters.computeIfAbsent(model, method -> {
            List<ContainerRequestFilter> filters =
                    new ArrayList<>(NameBindings.filter(this.requestFilters, method));
            for (Class<?> clazz : DynamicFeatures.providersFor(this.dynamicFeatures, method)) {
                Object provider = this.instance(clazz);
                if (provider instanceof ContainerRequestFilter) {
                    this.injectFields(provider);
                    filters.add((ContainerRequestFilter) provider);
                }
            }
            sortByPriority(filters, false);
            return filters;
        });
    }

    private static Object instantiate(Class<?> clazz) {
        try {
            return clazz.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new HugeException("Failed to create '%s'", e, clazz.getName());
        }
    }

    /**
     * Request filters run from the lowest priority number up; response filters run the other way
     * round, so that a filter that opened something closes it after the ones it wrapped.
     */
    private static void sortByPriority(List<?> providers, boolean descending) {
        Comparator<Object> byPriority = Comparator.comparingInt(RestApplication::priorityOf)
                                                  .thenComparing(p -> p.getClass().getName());
        providers.sort(descending ? byPriority.reversed() : byPriority);
    }

    private static int priorityOf(Object provider) {
        Priority priority = provider.getClass().getAnnotation(Priority.class);
        return priority == null ? Priorities.USER : priority.value();
    }

    /** Fills the fields a provider or a resource asks the serving layer for. */
    private void injectFields(Object instance) {
        for (Class<?> clazz = instance.getClass(); clazz != null && clazz != Object.class;
             clazz = clazz.getSuperclass()) {
            for (Field field : clazz.getDeclaredFields()) {
                if (!field.isAnnotationPresent(Context.class)) {
                    continue;
                }
                Object value = this.contextValue(field.getType(), field.getGenericType());
                if (value == null) {
                    continue;
                }
                field.setAccessible(true);
                try {
                    field.set(instance, value);
                } catch (IllegalAccessException e) {
                    throw new HugeException("Failed to fill '%s'", e, field);
                }
            }
        }
    }

    /**
     * What a field or a parameter marked as coming from the serving layer is given. A value that
     * is asked for through a provider is resolved when it is read, because the graphs it reaches
     * are only there once the service has started.
     */
    public Object contextValue(Class<?> raw, Type type) {
        if (jakarta.inject.Provider.class.isAssignableFrom(raw)) {
            Type argument = type instanceof ParameterizedType
                            ? ((ParameterizedType) type).getActualTypeArguments()[0] : Object.class;
            Class<?> wanted = argument instanceof Class ? (Class<?>) argument : Object.class;
            return (jakarta.inject.Provider<Object>) () -> this.contextValue(wanted, wanted);
        }
        if (raw == HugeConfig.class) {
            return this.config;
        }
        if (raw == GraphManager.class) {
            return this.graphManager();
        }
        if (raw == WorkLoad.class) {
            return this.workLoad;
        }
        if (raw == Application.class) {
            return this;
        }
        if (raw == ResourceModel.class) {
            return this.model;
        }
        // What is scoped to one request is read from the request the thread is answering.
        RestRequestContext request = RequestScope.current();
        if (request != null) {
            if (raw == jakarta.ws.rs.core.UriInfo.class) {
                return request.getUriInfo();
            }
            if (raw == jakarta.ws.rs.core.HttpHeaders.class) {
                return new RestHttpHeaders(request);
            }
            if (raw == jakarta.ws.rs.core.SecurityContext.class) {
                return request.getSecurityContext();
            }
            if (raw == jakarta.ws.rs.container.ContainerRequestContext.class) {
                return request;
            }
        }
        return null;
    }

    /** The graph manager, which is there only after the service has finished starting. */
    public GraphManager graphManager() {
        GraphManager current = this.manager;
        if (current == null) {
            throw new IllegalStateException("Please wait for the server to initialize");
        }
        return current;
    }

    /** The graph manager if the service has one, without waiting for it. */
    public GraphManager graphManagerOrNull() {
        return this.manager;
    }

    public void graphManager(GraphManager manager) {
        this.manager = manager;
    }

    public HugeConfig config() {
        return this.config;
    }

    public ResourceModel model() {
        return this.model;
    }

    public Object instance(Class<?> clazz) {
        return this.instances.computeIfAbsent(clazz, RestApplication::instantiate);
    }

    public List<ContainerRequestFilter> preMatchingFilters() {
        return this.preMatchingFilters;
    }

    public List<ContainerRequestFilter> requestFilters() {
        return this.requestFilters;
    }

    public List<ContainerResponseFilter> responseFilters() {
        return this.responseFilters;
    }

    public List<ReaderInterceptor> readerInterceptors() {
        return this.readerInterceptors;
    }

    public List<WriterInterceptor> writerInterceptors() {
        return this.writerInterceptors;
    }

    /** The mapper for a failure: the one whose type is the nearest the failure can be read as. */
    public ExceptionMapper<Throwable> mapperFor(Throwable failure) {
        ExceptionMapper<?> best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (ExceptionMapper<?> mapper : this.exceptionMappers) {
            Class<?> handled = handledType(mapper.getClass());
            if (handled == null || !handled.isInstance(failure)) {
                continue;
            }
            int distance = distance(failure.getClass(), handled);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = mapper;
            }
        }
        @SuppressWarnings("unchecked")
        ExceptionMapper<Throwable> mapper = (ExceptionMapper<Throwable>) best;
        return mapper;
    }

    private static Class<?> handledType(Class<?> mapperClass) {
        for (Class<?> current = mapperClass; current != null; current = current.getSuperclass()) {
            for (Type iface : current.getGenericInterfaces()) {
                if (iface instanceof ParameterizedType) {
                    ParameterizedType parameterized = (ParameterizedType) iface;
                    if (parameterized.getRawType() == ExceptionMapper.class) {
                        Type argument = parameterized.getActualTypeArguments()[0];
                        return argument instanceof Class ? (Class<?>) argument : Throwable.class;
                    }
                }
            }
        }
        return null;
    }

    private static int distance(Class<?> thrown, Class<?> handled) {
        int distance = 0;
        for (Class<?> current = thrown; current != null; current = current.getSuperclass()) {
            if (current == handled) {
                return distance;
            }
            distance++;
        }
        return Integer.MAX_VALUE - 1;
    }

    @Override
    public Set<Class<?>> getClasses() {
        return this.classes;
    }
}
