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

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.hugegraph.api.filter.AuthenticationFilter;
import org.apache.hugegraph.util.Log;
import org.slf4j.Logger;

import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAcceptableException;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.NotSupportedException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.WriterInterceptor;

/**
 * Turns one request into one answer: the filters round it, the resource method that answers it,
 * the conversion of its parameters, the reading and writing of its entity, and the answer a
 * failure is turned into.
 *
 * The order of the steps is the order the old runtime ran them in, because the module's own
 * filters depend on it: the path filter rewrites a request before anything matches it, the
 * status filter rewrites the status before the access log counts it, and the work load is
 * released after everything else.
 */
public final class RestDispatcher {

    /** The number of bytes an answer may hold before it is written in pieces. */
    private static final int LENGTH_BUFFER = 8192;

    private static final Logger LOG = Log.logger(RestDispatcher.class);

    private final RestApplication application;

    public RestDispatcher(RestApplication application) {
        this.application = application;
    }

    /** One finished answer, ready to be written. */
    public static final class Outcome {

        private final int status;
        private final String reason;
        private final MultivaluedMap<String, Object> headers;
        private final byte[] body;
        private final boolean chunked;

        Outcome(int status, String reason, MultivaluedMap<String, Object> headers,
                byte[] body, boolean chunked) {
            this.status = status;
            this.reason = reason;
            this.headers = headers;
            this.body = body;
            this.chunked = chunked;
        }

        public int status() {
            return this.status;
        }

        public String reason() {
            return this.reason;
        }

        public MultivaluedMap<String, Object> headers() {
            return this.headers;
        }

        public byte[] body() {
            return this.body;
        }

        /** True when the answer is written in pieces rather than with a declared length. */
        public boolean chunked() {
            return this.chunked;
        }
    }

    /** Answers one request. */
    public Outcome dispatch(String method, URI baseUri, URI requestUri,
                            MultivaluedMap<String, String> headers, byte[] body,
                            RestRequestContext.PeerInfo peer) {
        RestUriInfo uriInfo = new RestUriInfo(baseUri, requestUri);
        String wire = method;
        RestRequestContext request = new RestRequestContext(method, uriInfo, headers, body, peer);
        RequestScope.open(request);
        try {
            return this.answer(request, wire);
        } finally {
            // Nothing of the request is left on the thread that answered it.
            RequestScope.close();
        }
    }

    private Outcome answer(RestRequestContext request, String wire) {
        Response response;
        ResourceModel.MethodModel invoked;
        try {
            Invocation invocation = this.runRequest(request, wire);
            invoked = invocation.model;
            response = invocation.response;
        } catch (MediaTypes.BadQuality | MediaTypes.BadMediaTypeHeader failure) {
            /*
             * A header that is not a media type list, or a quality value that is not a number,
             * is refused on its own: no entity, and none of the module's mappers write one.
             * The module's response filters still see the refusal, as they see every answer to
             * a request that was taken in: one of them releases the load the request was
             * counted against, and without it every refused header would hold a worker's share
             * of the load for good.
             */
            RestResponseContext refused = new RestResponseContext(
                    400, null, null, new Annotation[0], new MultivaluedHashMap<>());
            this.runResponseFilters(request, refused, null);
            return new Outcome(400, null, new MultivaluedHashMap<>(), new byte[0], false);
        } catch (Throwable failure) {
            /*
             * The method the request matched is kept: the interceptors it binds still run round
             * the answer, and it is the entity's own annotations - empty for an answer a mapper
             * wrote - that decide what they do with it.
             */
            invoked = this.matched(request, wire);
            response = this.toResponse(failure);
        }
        Annotation[] annotations = response instanceof RestResponse
                                   ? ((RestResponse) response).entityAnnotations()
                                   : new Annotation[0];
        MultivaluedMap<String, Object> responseHeaders = new MultivaluedHashMap<>();
        if (response.getMetadata() != null) {
            for (Map.Entry<String, List<Object>> header : response.getMetadata().entrySet()) {
                responseHeaders.addAll(header.getKey(), header.getValue());
            }
        }
        RestResponseContext context = new RestResponseContext(
                response.getStatus(), reasonOf(response), response.getEntity(),
                annotations, responseHeaders);
        this.runResponseFilters(request, context, invoked);
        return this.writeOutcome(request, context, wire, invoked);
    }

    /** The method a request matched, for the interceptors it binds, or null when none did. */
    private ResourceModel.MethodModel matched(RestRequestContext request, String wire) {
        RestUriInfo uriInfo = (RestUriInfo) request.getUriInfo();
        String method = "HEAD".equals(wire) ? "GET" : wire;
        for (Candidate candidate : this.match(uriInfo.matchPath())) {
            List<ResourceModel.MethodModel> found = candidate.methodsFor(method);
            if (!found.isEmpty()) {
                return found.get(0);
            }
        }
        return null;
    }

    private static String reasonOf(Response response) {
        Response.StatusType info = response.getStatusInfo();
        return info == null ? null : info.getReasonPhrase();
    }

    /** What one request matched and what it answered with. */
    private static final class Invocation {

        private final Response response;
        private final ResourceModel.MethodModel model;

        Invocation(Response response, ResourceModel.MethodModel model) {
            this.response = response;
            this.model = model;
        }
    }

    private Invocation runRequest(RestRequestContext request, String wire) throws Exception {
        for (ContainerRequestFilter filter : this.application.preMatchingFilters()) {
            filter.filter(request);
            if (request.abortedWith() != null) {
                return new Invocation(request.abortedWith(), null);
            }
        }
        String method = "HEAD".equals(wire) ? "GET" : wire;
        RestUriInfo uriInfo = (RestUriInfo) request.getUriInfo();
        List<Candidate> candidates = this.match(uriInfo.matchPath());
        if (candidates.isEmpty()) {
            throw new NotFoundException();
        }
        Candidate chosen = null;
        List<ResourceModel.MethodModel> methods = Collections.emptyList();
        for (Candidate candidate : candidates) {
            List<ResourceModel.MethodModel> found = candidate.methodsFor(method);
            if (!found.isEmpty()) {
                chosen = candidate;
                methods = found;
                break;
            }
        }
        if (chosen == null) {
            Candidate first = candidates.get(0);
            if ("OPTIONS".equals(method)) {
                first.apply(uriInfo);
                return new Invocation(
                        Wadl.options(uriInfo, first.allowed(true), first.root(), first.child(),
                                     MediaTypes.accepted(request.getHeaders()
                                                                .get(HttpHeaders.ACCEPT))),
                        null);
            }
            throw new NotAllowedException(Response.status(405)
                                                  .allow(first.allowed(false))
                                                  .build());
        }
        chosen.apply(uriInfo);
        ResourceModel.MethodModel model = this.select(request, methods);
        for (ContainerRequestFilter filter : this.application.requestFiltersFor(model)) {
            filter.filter(request);
            if (request.abortedWith() != null) {
                return new Invocation(request.abortedWith(), model);
            }
        }
        this.checkRoles(request, model);
        Response response = this.invoke(request, model);
        return new Invocation(response, model);
    }

    /** Every resource whose path matches the request, most specific first. */
    private List<Candidate> match(String path) {
        List<Candidate> candidates = new ArrayList<>();
        for (ResourceModel.RootResource resource : this.application.model().resources()) {
            UriTemplate.Match match = resource.template().match(path);
            if (match == null) {
                continue;
            }
            if (match.remainder().isEmpty() && !resource.methods().isEmpty()) {
                candidates.add(new Candidate(resource, null, match, null));
            }
            for (ResourceModel.ChildResource child : resource.sortedChildren()) {
                UriTemplate.Match childMatch = child.template().match(match.remainder());
                if (childMatch != null && childMatch.remainder().isEmpty()) {
                    candidates.add(new Candidate(resource, child, match, childMatch));
                }
            }
        }
        return candidates;
    }

    /**
     * The method that answers, among the ones the path and the request method allow: the entity
     * the request declares has to be readable, and the answer has to be in a type the client
     * will take.
     */
    private ResourceModel.MethodModel select(RestRequestContext request,
                                             List<ResourceModel.MethodModel> methods) {
        MediaType declared = request.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE) == null
                             ? null : request.getMediaType();
        List<ResourceModel.MethodModel> readable = new ArrayList<>();
        for (ResourceModel.MethodModel model : methods) {
            if (declared == null || model.consumes().isEmpty() ||
                model.consumes().stream().anyMatch(type -> MediaTypes.compatible(type, declared))) {
                readable.add(model);
            }
        }
        if (readable.isEmpty()) {
            throw new NotSupportedException();
        }
        List<MediaTypes.Accepted> accepted =
                MediaTypes.accepted(request.getHeaders().get(HttpHeaders.ACCEPT));
        for (MediaTypes.Accepted wanted : accepted) {
            for (ResourceModel.MethodModel model : readable) {
                if (model.produces().isEmpty()) {
                    return model;
                }
                for (MediaType produced : model.produces()) {
                    if (MediaTypes.compatible(produced, wanted.type())) {
                        return model;
                    }
                }
            }
        }
        throw new NotAcceptableException();
    }

    private void checkRoles(RestRequestContext request, ResourceModel.MethodModel model) {
        Method method = model.method();
        if (method.isAnnotationPresent(DenyAll.class)) {
            throw new ForbiddenException("You are not authorized to access this resource.");
        }
        RolesAllowed roles = method.getAnnotation(RolesAllowed.class);
        if (roles == null && !method.isAnnotationPresent(PermitAll.class)) {
            roles = method.getDeclaringClass().getAnnotation(RolesAllowed.class);
        }
        if (roles == null) {
            return;
        }
        SecurityContext security = request.getSecurityContext();
        if (security != null) {
            for (String role : roles.value()) {
                if (security.isUserInRole(role)) {
                    return;
                }
            }
        }
        throw new ForbiddenException("You are not authorized to access this resource.");
    }

    private Response invoke(RestRequestContext request, ResourceModel.MethodModel model)
            throws Exception {
        Object[] arguments = this.arguments(request, model);
        Object instance = this.application.instance(model.method().getDeclaringClass());
        Object result;
        try {
            result = model.method().invoke(instance, arguments);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw (Error) cause;
        }
        Annotation[] annotations = model.method().getDeclaredAnnotations();
        if (result instanceof Response) {
            Response response = (Response) result;
            if (response instanceof RestResponse &&
                ((RestResponse) response).entityAnnotations().length == 0) {
                return new RestResponseBuilder().status(response.getStatus())
                                                .replaceAll(response.getMetadata())
                                                .entity(response.getEntity(), annotations)
                                                .build();
            }
            return response;
        }
        int status = result == null ? 204 : 200;
        return new RestResponseBuilder().status(status)
                                        .entity(result, annotations)
                                        .type(this.contentType(request, model))
                                        .build();
    }

    /** The content type the answer carries, from what the method writes and what was asked for. */
    private MediaType contentType(RestRequestContext request, ResourceModel.MethodModel model) {
        if (model.produces().isEmpty()) {
            return null;
        }
        List<MediaTypes.Accepted> accepted =
                MediaTypes.accepted(request.getHeaders().get(HttpHeaders.ACCEPT));
        for (MediaTypes.Accepted wanted : accepted) {
            for (MediaType produced : model.produces()) {
                if (MediaTypes.compatible(produced, wanted.type())) {
                    return MediaTypes.combine(produced, wanted.type());
                }
            }
        }
        return model.produces().get(0);
    }

    private Object[] arguments(RestRequestContext request, ResourceModel.MethodModel model)
            throws Exception {
        RestUriInfo uriInfo = (RestUriInfo) request.getUriInfo();
        List<ResourceModel.ParamModel> params = model.params();
        Object[] arguments = new Object[params.size()];
        for (int i = 0; i < params.size(); i++) {
            ResourceModel.ParamModel param = params.get(i);
            switch (param.source()) {
                case PATH:
                    arguments[i] = this.converted(
                            uriInfo.getPathParameters().get(param.sourceName()), param,
                            ParamException.PathParamException::new);
                    break;
                case QUERY:
                    arguments[i] = this.converted(
                            uriInfo.getQueryParameters().get(param.sourceName()), param,
                            ParamException.QueryParamException::new);
                    break;
                case HEADER:
                    arguments[i] = this.converted(
                            request.getHeaders().get(param.sourceName()), param,
                            ParamException.HeaderParamException::new);
                    break;
                case CONTEXT:
                    arguments[i] = this.context(request, param);
                    break;
                default:
                    arguments[i] = this.entity(request, model, param);
                    break;
            }
        }
        return arguments;
    }

    private Object converted(List<String> values, ResourceModel.ParamModel param,
                             java.util.function.Function<Throwable, ParamException> failure) {
        try {
            return ParamConverters.convert(values == null ? Collections.emptyList() : values,
                                           param);
        } catch (WebApplicationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw failure.apply(e);
        }
    }

    private Object context(RestRequestContext request, ResourceModel.ParamModel param) {
        Class<?> raw = param.rawType();
        if (raw == UriInfo.class) {
            return request.getUriInfo();
        }
        if (raw == HttpHeaders.class) {
            return new RestHttpHeaders(request);
        }
        if (raw == SecurityContext.class) {
            return request.getSecurityContext();
        }
        if (raw == jakarta.ws.rs.core.Request.class) {
            return request.getRequest();
        }
        if (raw == jakarta.ws.rs.container.ContainerRequestContext.class) {
            return request;
        }
        return this.application.contextValue(raw, param.type());
    }

    /**
     * Reads the entity a resource method takes.
     *
     * The interceptors the method binds run first, so one that unpacks a content encoding sees the
     * stream even when the request declared no length; only then is it asked whether there is
     * anything to read at all, and only then is a media type nothing reads refused.
     */
    private Object entity(RestRequestContext request, ResourceModel.MethodModel model,
                          ResourceModel.ParamModel param) throws Exception {
        MediaType declared = request.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE) == null
                             ? MediaType.APPLICATION_OCTET_STREAM_TYPE : request.getMediaType();
        List<ReaderInterceptor> interceptors =
                NameBindings.filter(this.application.readerInterceptors(), model);
        try {
            return Interceptors.read(interceptors, request.getEntityStream(), param.type(),
                                     declared, model.method().getDeclaredAnnotations(),
                                     request.getHeaders());
        } catch (EntityIo.NoReader e) {
            throw new NotSupportedException(Response.status(415).build(), e);
        } catch (EntityIo.UnreadableEntity e) {
            throw new WebApplicationException(
                    Response.status(400).type(MediaType.TEXT_PLAIN)
                            .entity(e.getMessage()).build());
        }
    }

    /** Turns a failure into the answer one of the module's mappers writes for it. */
    private Response toResponse(Throwable failure) {
        ExceptionMapper<Throwable> mapper = this.application.mapperFor(failure);
        if (mapper == null) {
            LOG.error("No mapper for the failure of a request", failure);
            return Response.status(500).build();
        }
        return mapper.toResponse(failure);
    }

    private void runResponseFilters(RestRequestContext request, RestResponseContext response,
                                    ResourceModel.MethodModel model) {
        List<ContainerResponseFilter> filters = model == null
                ? NameBindings.unbound(this.application.responseFilters())
                : NameBindings.filter(this.application.responseFilters(), model);
        for (ContainerResponseFilter filter : filters) {
            try {
                filter.filter(request, response);
            } catch (Throwable e) {
                LOG.warn("Failed to run a response filter", e);
            }
        }
    }

    /** Writes the entity, running the writer interceptors the matched method binds. */
    private Outcome writeOutcome(RestRequestContext request, RestResponseContext response,
                                 String wire, ResourceModel.MethodModel model) {
        MultivaluedMap<String, Object> headers = response.getHeaders();
        byte[] body = new byte[0];
        if (response.hasEntity()) {
            ByteArrayOutputStream collected = new ByteArrayOutputStream();
            List<WriterInterceptor> interceptors = model == null
                    ? NameBindings.unbound(this.application.writerInterceptors())
                    : NameBindings.filter(this.application.writerInterceptors(), model);
            try {
                Interceptors.write(interceptors, response.getEntity(), collected,
                                   response.getMediaType(), response.getEntityAnnotations(),
                                   headers);
                body = collected.toByteArray();
            } catch (Throwable e) {
                LOG.error("Failed to write the answer of a request", e);
                MultivaluedMap<String, Object> failed = new MultivaluedHashMap<>();
                return new Outcome(500, null, failed, new byte[0], false);
            }
        }
        boolean chunked = body.length > LENGTH_BUFFER;
        if (!chunked) {
            headers.putSingle(HttpHeaders.CONTENT_LENGTH, String.valueOf(body.length));
        }
        if ("HEAD".equals(wire)) {
            body = new byte[0];
        }
        return new Outcome(response.getStatus(), response.reason(), headers, body, chunked);
    }

    /** One resource the path matched, with the values its templates captured. */
    private static final class Candidate {

        private final ResourceModel.RootResource root;
        private final ResourceModel.ChildResource child;
        private final UriTemplate.Match rootMatch;
        private final UriTemplate.Match childMatch;

        Candidate(ResourceModel.RootResource root, ResourceModel.ChildResource child,
                  UriTemplate.Match rootMatch, UriTemplate.Match childMatch) {
            this.root = root;
            this.child = child;
            this.rootMatch = rootMatch;
            this.childMatch = childMatch;
        }

        List<ResourceModel.MethodModel> methods() {
            return this.child == null ? this.root.methods() : this.child.methods();
        }

        List<ResourceModel.MethodModel> methodsFor(String httpMethod) {
            List<ResourceModel.MethodModel> found = new ArrayList<>();
            for (ResourceModel.MethodModel model : this.methods()) {
                if (model.httpMethod().equals(httpMethod)) {
                    found.add(model);
                }
            }
            return found;
        }

        /** The methods a client may use here, with the ones the serving layer answers itself. */
        Set<String> allowed(boolean withHead) {
            Set<String> allowed = new HashSet<>();
            for (ResourceModel.MethodModel model : this.methods()) {
                allowed.add(model.httpMethod());
            }
            if (withHead && allowed.contains("GET")) {
                allowed.add("HEAD");
            }
            allowed.add("OPTIONS");
            return allowed;
        }

        /** Records the path values of this match, which the filters and the methods read. */
        void apply(RestUriInfo uriInfo) {
            MultivaluedMap<String, String> parameters = new MultivaluedHashMap<>();
            record(parameters, this.rootMatch);
            record(parameters, this.childMatch);
            uriInfo.pathParameters(parameters);
        }

        private static void record(MultivaluedMap<String, String> parameters,
                                   UriTemplate.Match match) {
            if (match == null) {
                return;
            }
            for (int i = 0; i < match.names().size(); i++) {
                String value = match.values().get(i);
                parameters.add(match.names().get(i),
                               value == null ? null : RestUriInfo.decode(value));
            }
        }

        ResourceModel.RootResource root() {
            return this.root;
        }

        ResourceModel.ChildResource child() {
            return this.child;
        }
    }

    /** The matched resource, for the answer the serving layer writes itself. */
    static List<Candidate> candidatesOf(RestDispatcher dispatcher, String path) {
        return dispatcher.match(path);
    }

    /** The authentication filter's own security context type, kept for the role checks. */
    static boolean isAuthorizer(SecurityContext context) {
        return context instanceof AuthenticationFilter.Authorizer;
    }

    /** The date the answers of the serving layer itself carry. */
    static Date now() {
        return new Date();
    }

    /** Writes a stream straight into a collected answer. */
    static OutputStream collect(ByteArrayOutputStream target) {
        return target;
    }
}
