/**
 * Copyright (c) 2018, biezhi 王爵 nice (biezhi.me@gmail.com)
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.blade.server.netty;

import com.blade.exception.NotFoundException;
import com.blade.mvc.LocalContext;
import com.blade.mvc.WebContext;
import com.blade.mvc.handler.ExceptionHandler;
import com.blade.mvc.http.HttpRequest;
import com.blade.mvc.http.HttpResponse;
import com.blade.mvc.http.Request;
import com.blade.mvc.http.Response;
import com.blade.mvc.route.Route;
import com.blade.mvc.route.RouteMatcher;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.concurrent.FastThreadLocal;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * Http Server Handler
 *
 * @author biezhi
 * 2018/10/15
 */
@Slf4j
@ChannelHandler.Sharable
public class HttpServerHandler extends SimpleChannelInboundHandler<HttpObject> {

    private static final FastThreadLocal<LocalContext> LOCAL_CONTEXT = new FastThreadLocal<>();

    private final StaticFileHandler  staticFileHandler  = new StaticFileHandler(WebContext.blade());
    private final RouteMethodHandler routeMethodHandler = new RouteMethodHandler();
    private final RouteMatcher       routeMatcher       = WebContext.blade().routeMatcher();
    private final ExecutorService    logicExecutor      = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);

    private static final boolean allowCost = WebContext.blade().allowCost();

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        super.channelUnregistered(ctx);
        if (LOCAL_CONTEXT.get() != null && LOCAL_CONTEXT.get().hasDecoder()) {
            LOCAL_CONTEXT.get().decoder().cleanFiles();
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
        String  remoteAddress = ctx.channel().remoteAddress().toString();
        Request request       = HttpRequest.build(remoteAddress, msg);
        if (null == request) {
            return;
        }
        if (request.isPart()) {
            this.executePart(ctx, request);
            return;
        }

        // content has not been read yet
        if (!request.chunkIsEnd() && !request.readChunk()) {
            return;
        }

        try {
            LogicRunner asyncRunner = new LogicRunner(routeMethodHandler, allowCost, WebContext.get());

            CompletableFuture<Void> future = CompletableFuture.completedFuture(asyncRunner)
                    .thenApplyAsync(LogicRunner::handle, logicExecutor)
                    .thenAcceptAsync(LogicRunner::finishWrite, logicExecutor);

            asyncRunner.setFuture(future);
        } finally {
            WebContext.remove();
            LOCAL_CONTEXT.remove();
        }
    }

    public static LocalContext getLocalContext() {
        return LOCAL_CONTEXT.get();
    }

    public static void setLocalContext(LocalContext localContext) {
        LOCAL_CONTEXT.remove();
        LOCAL_CONTEXT.set(localContext);
    }

    private void executePart(ChannelHandlerContext ctx, Request request) {
        Response response = new HttpResponse();

        // init web context
        WebContext webContext = WebContext.create(request, response, ctx, LOCAL_CONTEXT.get());

        String uri    = request.uri();
        String method = request.method();

        try {
            if (isStaticFile(method, uri)) {
                staticFileHandler.handle(webContext);
                LOCAL_CONTEXT.remove();
                WebContext.remove();
            } else {
                Route route = routeMatcher.lookupRoute(method, uri);
                if (null != route) {
                    webContext.setRoute(route);
                } else {
                    throw new NotFoundException(uri);
                }
            }
        } catch (Exception e) {
            routeMethodHandler.exceptionCaught(uri, method, e);
            routeMethodHandler.finishWrite(webContext);
            LOCAL_CONTEXT.remove();
            WebContext.remove();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (!ExceptionHandler.isResetByPeer(cause)) {
            log.error(cause.getMessage(), cause);
            FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.valueOf(500));
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private Set<String> notStaticUri = new HashSet<>(32);

    private boolean isStaticFile(String method, String uri) {
        if ("POST".equals(method) || notStaticUri.contains(uri)) {
            return false;
        }
        Optional<String> result = WebContext.blade().getStatics().stream().filter(s -> s.equals(uri) || uri.startsWith(s)).findFirst();
        if (!result.isPresent()) {
            notStaticUri.add(uri);
            return false;
        }
        return true;
    }

}