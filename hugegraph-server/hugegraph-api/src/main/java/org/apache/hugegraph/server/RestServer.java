/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hugegraph.server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.config.ServerOptions;
import org.apache.hugegraph.core.GraphManager;
import org.apache.hugegraph.event.EventHub;
import org.apache.hugegraph.server.rest.Headers;
import org.apache.hugegraph.server.rest.RestApplication;
import org.apache.hugegraph.server.rest.RestDispatcher;
import org.apache.hugegraph.server.rest.RequestTarget;
import org.apache.hugegraph.server.rest.RestRequestContext;
import org.apache.hugegraph.server.rest.RestRuntimeDelegate;
import org.apache.hugegraph.server.rest.StaticFiles;
import org.apache.hugegraph.util.E;
import org.apache.hugegraph.util.Log;
import org.apache.hugegraph.version.ApiVersion;
import org.slf4j.Logger;

import io.helidon.common.configurable.Resource;
import io.helidon.common.http.DataChunk;
import io.helidon.common.pki.KeyConfig;
import io.helidon.common.http.Http;
import io.helidon.common.reactive.Multi;
import io.helidon.webserver.Routing;
import io.helidon.webserver.ServerRequest;
import io.helidon.webserver.ServerResponse;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.WebServerTls;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;

/**
 * The HTTP server the REST API is answered by.
 *
 * The web server reads a request and hands its bytes to the serving layer on a worker thread,
 * because the resource methods reach the graph and block. Everything above the socket - routing,
 * parameter conversion, entity reading and writing, the filters and the error answers - belongs
 * to the serving layer rather than to the web server.
 */
public class RestServer {

    private static final Logger LOG = Log.logger(RestServer.class);

    private static final String SWAGGER_UI = "/swagger-ui";

    /** The size of the pieces an answer without a declared length is written in. */
    private static final int CHUNK_SIZE = 8192;

    private final HugeConfig conf;
    private final EventHub eventHub;
    private WebServer webServer = null;
    private RestApplication application = null;
    private ExecutorService workers = null;

    public RestServer(HugeConfig conf, EventHub hub) {
        this.conf = conf;
        this.eventHub = hub;
    }

    public void start() throws IOException {
        RestRuntimeDelegate.install();
        String url = this.conf.get(ServerOptions.REST_SERVER_URL);
        URI uri = URI.create(url);

        this.application = new RestApplication(this.conf);
        RestDispatcher dispatcher = new RestDispatcher(this.application);
        StaticFiles files = new StaticFiles(SWAGGER_UI.substring(1), SWAGGER_UI);
        /*
         * Option max_worker_threads: the requests are answered by this many threads, and a
         * request that arrives while all of them are busy waits for one in turn. The threads
         * that read the sockets are the web server's own and are not counted against it.
         */
        int maxWorkerThreads = this.conf.get(ServerOptions.MAX_WORKER_THREADS);
        this.workers = Executors.newFixedThreadPool(maxWorkerThreads, new WorkerFactory());

        Routing routing = Routing.builder()
                                 .any((request, response) ->
                                              this.accept(dispatcher, files, request, response))
                                 .build();
        this.webServer = this.configHttpServer(uri, routing);

        GraphManager manager = new GraphManager(this.conf, this.eventHub);
        try {
            manager.init();
        } catch (Throwable e) {
            manager.close();
            throw e;
        }
        this.application.graphManager(manager);

        try {
            this.webServer.start().toCompletableFuture().join();
        } catch (Throwable e) {
            this.webServer.shutdown();
            throw e;
        }
        this.calcMaxWriteThreads();
    }

    private WebServer configHttpServer(URI uri, Routing routing) throws IOException {
        WebServer.Builder builder = WebServer.builder(routing);
        String host = uri.getHost();
        if (host != null) {
            try {
                builder.bindAddress(InetAddress.getByName(host));
            } catch (UnknownHostException e) {
                throw new IOException("Unknown bind address '" + host + "'", e);
            }
        }
        builder.port(uri.getPort() > 0 ? uri.getPort() : 8080);

        // Option keep_alive and transaction timeout
        int idleTimeout = this.conf.get(ServerOptions.CONN_IDLE_TIMEOUT);
        builder.connectionIdleTimeout(idleTimeout);
        int transactionTimeout = this.conf.get(ServerOptions.REQUEST_TIMEOUT);
        builder.timeout(transactionTimeout * 1000L, TimeUnit.MILLISECONDS);

        if ("https".equals(uri.getScheme())) {
            String keystoreFile = this.conf.get(ServerOptions.SSL_KEYSTORE_FILE);
            String keystorePass = this.conf.get(ServerOptions.SSL_KEYSTORE_PASSWORD);
            KeyConfig key = KeyConfig.keystoreBuilder()
                                     .keystore(Resource.create(Paths.get(keystoreFile)))
                                     .keystorePassphrase(keystorePass)
                                     .build();
            builder.tls(WebServerTls.builder().privateKey(key).build());
        }
        return builder.build();
    }

    /** Reads the whole request entity, then answers it away from the reading threads. */
    private void accept(RestDispatcher dispatcher, StaticFiles files,
                        ServerRequest request, ServerResponse response) {
        request.content().as(byte[].class).thenAccept(body -> this.workers.execute(
                () -> this.answer(dispatcher, files, request, response, body)))
               .exceptionally(failure -> {
                   LOG.warn("Failed to read a request entity", failure);
                   response.status(Http.Status.BAD_REQUEST_400).send();
                   return null;
               });
    }

    private void answer(RestDispatcher dispatcher, StaticFiles files, ServerRequest request,
                        ServerResponse response, byte[] body) {
        String rawPath = rawPathOf(request);
        try {
            if (!RequestTarget.acceptable(rawPath)) {
                /*
                 * A target the layer that reads it refuses: nothing is written back, and the
                 * connection is not kept, exactly as the original left it.
                 */
                response.status(Http.Status.INTERNAL_SERVER_ERROR_500);
                response.headers().contentLength(0);
                response.send(new byte[0]);
                return;
            }
            String handlerPath = RequestTarget.handlerPath(rawPath);
            if (handlerPath.equals(files.prefix()) || handlerPath.startsWith(files.prefix() + "/")) {
                this.serveFile(files, handlerPath, request, response);
                return;
            }
            URI baseUri = URI.create((request.isSecure() ? "https://" : "http://") +
                                     hostOf(request) + "/");
            String query = request.query();
            String target = baseUri.toString().replaceAll("/$", "") + rawPath +
                            (query == null || query.isEmpty() ? "" : "?" + query);
            URI requestUri = URI.create(target);
            MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
            for (Map.Entry<String, List<String>> header : request.headers().toMap().entrySet()) {
                headers.addAll(header.getKey(), header.getValue());
            }
            RestRequestContext.PeerInfo peer = new RestRequestContext.PeerInfo(
                    request.remoteAddress(), request.remotePort(), rawPath);
            RestDispatcher.Outcome outcome = dispatcher.dispatch(
                    request.method().name(), baseUri, requestUri, headers, body, peer);
            this.write(response, outcome);
        } catch (Throwable e) {
            LOG.error("Failed to answer a request", e);
            response.status(Http.Status.INTERNAL_SERVER_ERROR_500).send();
        }
    }

    /**
     * The path exactly as the client wrote it. The web server tidies the path it routes on, and
     * an empty segment or a dot segment a client writes has to reach the serving layer as it was
     * written for the answer to be the one the service gives.
     */
    private static String rawPathOf(ServerRequest request) {
        String target = request.uri().toString();
        int scheme = target.indexOf("://");
        if (scheme >= 0) {
            int slash = target.indexOf('/', scheme + 3);
            target = slash < 0 ? "/" : target.substring(slash);
        }
        int query = target.indexOf('?');
        String raw = query < 0 ? target : target.substring(0, query);
        return raw.isEmpty() ? request.path().absolute().toRawString() : raw;
    }

    private static String hostOf(ServerRequest request) {
        return request.headers().first("Host")
                      .orElse(request.localAddress() + ":" + request.localPort());
    }

    private void write(ServerResponse response, RestDispatcher.Outcome outcome) {
        response.status(outcome.reason() == null
                        ? Http.ResponseStatus.create(outcome.status())
                        : Http.ResponseStatus.create(outcome.status(), outcome.reason()));
        for (Map.Entry<String, List<Object>> header : outcome.headers().entrySet()) {
            for (Object value : header.getValue()) {
                response.headers().add(header.getKey(),
                                       Headers.toText(value));
            }
        }
        if (outcome.chunked()) {
            // An answer too long to declare a length for is written in pieces.
            response.headers().add("Transfer-Encoding", "chunked");
            response.send(chunksOf(outcome.body()));
        } else {
            /*
             * The length is the one the serving layer declared, which is the length of the
             * entity even when the request asked only for the headers of the answer.
             */
            Object declared = outcome.headers().getFirst(HttpHeaders.CONTENT_LENGTH);
            response.headers().contentLength(declared == null ? outcome.body().length
                                                              : Long.parseLong(declared.toString()));
            response.send(outcome.body());
        }
    }

    /** Cuts an answer into the pieces it is written as. */
    private static Multi<DataChunk> chunksOf(byte[] body) {
        List<DataChunk> chunks = new ArrayList<>();
        for (int offset = 0; offset < body.length; offset += CHUNK_SIZE) {
            int length = Math.min(CHUNK_SIZE, body.length - offset);
            chunks.add(DataChunk.create(Arrays.copyOfRange(body, offset, offset + length)));
        }
        return Multi.create(chunks);
    }

    private void serveFile(StaticFiles files, String rawPath, ServerRequest request,
                           ServerResponse response) throws IOException {
        StaticFiles.Served served = files.serve(rawPath);
        if (served != null && !"GET".equals(request.method().name())) {
            // A file that is there is handed over for one method and no other.
            response.status(Http.Status.METHOD_NOT_ALLOWED_405);
            response.headers().add("Allow", "GET");
            response.headers().contentLength(0);
            response.send(new byte[0]);
            return;
        }
        boolean headersOnly = "HEAD".equals(request.method().name());
        if (served == null) {
            byte[] page = StaticFiles.notFoundPage(rawPath);
            response.status(Http.Status.NOT_FOUND_404);
            response.headers().add("Content-Type", StaticFiles.notFoundType());
            response.headers().contentLength(page.length);
            response.send(headersOnly ? new byte[0] : page);
            return;
        }
        response.headers().add("Content-Type", served.contentType());
        response.headers().add("Last-Modified",
                               Headers.httpDate(new java.util.Date(served.lastModified())));
        response.headers().add("ETag", "\"" + served.content().length + "-" +
                                       served.lastModified() + "\"");
        response.headers().contentLength(served.content().length);
        response.send(headersOnly ? new byte[0] : served.content());
    }

    public Future<RestServer> shutdown() {
        E.checkNotNull(this.webServer, "http server");
        CompletableFuture<RestServer> future = new CompletableFuture<>();
        this.webServer.shutdown().thenRun(() -> {
            this.closeResources();
            future.complete(this);
        }).exceptionally(failure -> {
            this.closeResources();
            future.completeExceptionally(failure);
            return null;
        });
        return future;
    }

    public void shutdownNow() {
        E.checkNotNull(this.webServer, "http server");
        this.webServer.shutdown().await();
        this.closeResources();
    }

    private void closeResources() {
        if (this.workers != null) {
            this.workers.shutdownNow();
            this.workers = null;
        }
        if (this.application != null) {
            GraphManager manager = this.application.graphManagerOrNull();
            if (manager != null) {
                manager.close();
            }
            this.application = null;
        }
    }

    public static RestServer start(String conf, EventHub hub) throws Exception {
        LOG.info("RestServer starting...");
        ApiVersion.check();

        HugeConfig config = new HugeConfig(conf);
        RestServer server = new RestServer(config, hub);
        server.start();
        LOG.info("RestServer started");

        return server;
    }

    private void calcMaxWriteThreads() {
        int maxWriteThreads = this.conf.get(ServerOptions.MAX_WRITE_THREADS);
        if (maxWriteThreads > 0) {
            // Use the value of MAX_WRITE_THREADS option if it's not 0
            return;
        }

        assert maxWriteThreads == 0;

        int maxWriteRatio = this.conf.get(ServerOptions.MAX_WRITE_RATIO);
        assert maxWriteRatio >= 0 && maxWriteRatio <= 100;
        int maxWorkerThreads = this.conf.get(ServerOptions.MAX_WORKER_THREADS);
        maxWriteThreads = maxWorkerThreads * maxWriteRatio / 100;
        E.checkState(maxWriteThreads >= 0,
                     "Invalid value of maximum batch writing threads '%s'",
                     maxWriteThreads);
        if (maxWriteThreads == 0) {
            E.checkState(maxWriteRatio == 0,
                         "The value of maximum batch writing threads is 0 " +
                         "due to the max_write_ratio '%s' is too small, " +
                         "set to '%s' at least to ensure one thread." +
                         "If you want to disable batch write, " +
                         "please let max_write_ratio be 0", maxWriteRatio,
                         (int) Math.ceil(100.0 / maxWorkerThreads));
        }
        LOG.info("The maximum batch writing threads is {} (total threads {})",
                 maxWriteThreads, maxWorkerThreads);
        // NOTE: addProperty will make exist option's value become List
        this.conf.setProperty(ServerOptions.MAX_WRITE_THREADS.name(),
                              String.valueOf(maxWriteThreads));
    }

    /** Names the threads the resource methods run on, so that a thread dump reads clearly. */
    private static final class WorkerFactory implements ThreadFactory {

        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable,
                                       "rest-worker-" + this.counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
