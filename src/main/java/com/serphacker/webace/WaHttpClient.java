/*
 * WebAce - Java Http Client for webscraping https://gitlab.com/serphacker/webace
 *
 * Copyright (c) 2018 SERP Hacker
 * @author Pierre Nogues <support@serphacker.com>
 * @license https://opensource.org/licenses/MIT MIT License
 */

package com.serphacker.webace;

import com.serphacker.webace.proxy.*;
import com.serphacker.webace.requests.PostBodyEntity;
import com.serphacker.webace.routes.WaRoutePlanner;
import com.serphacker.webace.routes.WaRoutes;
import com.serphacker.webace.sockets.PlainSocksConnectionSocketFactory;
import com.serphacker.webace.sockets.SecureConnectionSocketFactory;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.Credentials;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.auth.BasicScheme;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.BasicHttpClientConnectionManager;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.socket.ConnectionSocketFactory;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

public class WaHttpClient implements Closeable {

    public final static Logger LOG = LoggerFactory.getLogger(WaHttpClient.class);

    WaCookieStore cookies = new WaCookieStore();
    WaHttpConfig config = new WaHttpConfig();
    WaRoutes routes = new WaRoutes();
    WaProxy proxy = DirectNoProxy.INSTANCE;
    WaProxy previousProxy = DirectNoProxy.INSTANCE;


    CloseableHttpClient client;
    BasicHttpClientConnectionManager connectionManager;
    PlainSocksConnectionSocketFactory plainSocketFactory = new PlainSocksConnectionSocketFactory();
    SecureConnectionSocketFactory secureSocketFactory = new SecureConnectionSocketFactory(plainSocketFactory);
    BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();

    WaRoutePlanner routePlanner = new WaRoutePlanner(routes);

    public WaHttpClient() {

        connectionManager = new BasicHttpClientConnectionManager(
            RegistryBuilder.<ConnectionSocketFactory>create()
                .register("http", plainSocketFactory)
                .register("https", secureSocketFactory)
                .build()
        );
        connectionManager.setSocketConfig(SocketConfig.custom().setSoTimeout(Timeout.ofMilliseconds(config.timeoutMilli)).build());

        client = HttpClients
            .custom()
            .setRoutePlanner(routePlanner)
            .setDefaultCredentialsProvider(credentialsProvider)
            .setDefaultCookieStore(cookies.store)
            .setConnectionManager(connectionManager)
            .build();

    }

    public WaCookieStore cookies() {
        return cookies;
    }

    public WaHttpConfig config() {
        return config;
    }

    public WaRoutes routes() {
        return routes;
    }

    public WaHttpResponse doGet(String uri) {
        return doGet(uri, null);
    }

    public WaHttpResponse doGet(String uri, List<Header> headers) {
        return doRequest(Methods.GET.name(), uri, headers);
    }

    public WaHttpResponse doDelete(String uri) {
        return doDelete(uri, null);
    }

    public WaHttpResponse doDelete(String uri, List<Header> headers) {
        return doRequest(Methods.DELETE.name(), uri, headers);
    }

    public WaHttpResponse doPost(String uri, PostBodyEntity body) {
        return doPost(uri, body, null);
    }

    public WaHttpResponse doPost(String uri, PostBodyEntity body, List<Header> headers) {
        return doRequest(Methods.POST.name(), uri, body, headers);
    }

    public WaHttpResponse doPut(String uri, PostBodyEntity body) {
        return doPut(uri, body, null);
    }

    public WaHttpResponse doPut(String uri, PostBodyEntity body, List<Header> headers) {
        return doRequest(Methods.PUT.name(), uri, body, headers);
    }

    public WaHttpResponse doPatch(String uri, PostBodyEntity body) {
        return doPatch(uri, body, null);
    }

    public WaHttpResponse doPatch(String uri, PostBodyEntity body, List<Header> headers) {
        return doRequest(Methods.PATCH.name(), uri, body, headers);
    }

    public WaHttpResponse doRequest(String verb, String uri, List<Header> headers) {
        return doRequest(verb, uri, (HttpEntity) null, headers);
    }

    public WaHttpResponse doRequest(String verb, String uri, PostBodyEntity body, List<Header> headers) {
        return doRequest(verb, uri, body != null ? body.getHttpEntity() : null, headers);
    }

    public WaHttpResponse doRequest(String verb, String uri, HttpEntity body, List<Header> headers) {
        HttpUriRequestBase request = new HttpUriRequestBase(verb, URI.create(uri));

        if (body != null) {
            request.setEntity(body);
        }

        if (headers != null) {
            for (var requestHeader : headers) {
                request.addHeader(requestHeader);
            }
        }

        return doRequest(request);
    }

    public WaHttpResponse doRequest(ClassicHttpRequest request) {
        return doRequest(request, HttpClientContext.create());
    }

    public WaHttpResponse doRequest(ClassicHttpRequest request, HttpClientContext context) {
        WaHttpResponse response = new WaHttpResponse();

        reInitializeClient();
        initializeRequest(request, context);
        context.setAttribute(WaHttpContexts.WEBAXE_PROXY, proxy);
        initPreemptiveAuth(request, context);


        try {
            response.executionTimerStart();
            response.setContext(context);
            try (CloseableHttpResponse httpResponse = client.execute(request, context)) {
                response.setHttpResponse(httpResponse);
                final byte[] content = consumeResponse(httpResponse);
                response.setContent(content);
            }
        } catch (IOException ex) {
            response.setException(ex);
        } finally {
            response.executionTimerStop();
        }

        return response;
    }

    protected void initPreemptiveAuth(ClassicHttpRequest request, HttpClientContext context) {

        int port = HttpDefaultPort.determine(request.getAuthority().getPort(), request.getScheme());

        HttpHost host = new HttpHost(request.getScheme(), request.getAuthority().getHostName(), port);

        final Credentials credentials = credentialsProvider.getCredentials(
            new AuthScope(host),
            context
        );

        if (credentials != null) {
            final var basicAuth = new BasicScheme();
            basicAuth.initPreemptive(credentials);
            context.resetAuthExchange(host, basicAuth);
        }
    }

    protected void reInitializeClient() {
        connectionManager.setSocketConfig(SocketConfig.custom().setSoTimeout(Timeout.ofMilliseconds(config.timeoutMilli)).build());
        secureSocketFactory.setTrustAllSsl(config.isTrustAllSsl());
    }

    protected void initializeRequest(HttpRequest request, HttpClientContext context) {

        if (request.getFirstHeader(HttpHeaders.USER_AGENT) == null) {
            request.setHeader(HttpHeaders.USER_AGENT, config.getUserAgent());
        }

        for (Header requestHeader : config.defaultHeaders().list()) {
            request.addHeader(requestHeader);
        }

        RequestConfig.Builder configBuilder = RequestConfig.copy(context.getRequestConfig());
        configBuilder.setConnectTimeout(Timeout.ofMilliseconds(config.timeoutMilli));
        configBuilder.setConnectionRequestTimeout(Timeout.ofMilliseconds(config.timeoutMilli));
        configBuilder.setMaxRedirects(config.maxRedirect);
        configBuilder.setRedirectsEnabled(config.maxRedirect > 0);
        RequestConfig config = configBuilder.build();

        context.setAttribute(HttpClientContext.REQUEST_CONFIG, config);
    }

    protected byte[] consumeResponse(CloseableHttpResponse response) throws IOException {
        try (HttpEntity entity = response.getEntity()) {
            long contentLength = entity.getContentLength();

            if (contentLength > config.getMaxResponseLength()) {
                throw new IOException("content length (" + contentLength + ") is greater than max length (" +
                    config.getMaxResponseLength() + ")");
            }

            byte[] buffer = new byte[config.getMaxResponseLength() + 1];
            InputStream stream = entity.getContent();
            int totalRead = 0;
            int read;

            while (totalRead <= config.getMaxResponseLength()
                && (read = stream.read(buffer, totalRead, config.getMaxResponseLength() + 1 - totalRead)) != -1) {
                totalRead += read;
            }

            if (totalRead > config.getMaxResponseLength()) {
                throw new IOException("response is too big, already read " + totalRead + " bytes");
            }

            return Arrays.copyOfRange(buffer, 0, totalRead);
        }
    }

    public void closeConnection() {
        connectionManager.closeIdle(TimeValue.ofMilliseconds(0));
    }

    public WaProxy getProxy() {
        return proxy;
    }

    public void setProxy(WaProxy proxy) {
        this.proxy = proxy == null ? DirectNoProxy.INSTANCE : proxy;
        if (!this.proxy.equals(previousProxy)) {
            closeConnection();
        }
        this.previousProxy = this.proxy;

        if (proxy instanceof AuthentProxy && ((AuthentProxy) proxy).hasCredentials()) {

            if (proxy instanceof HttpProxy) {
                final var scope = new AuthScope(((HttpProxy) proxy).getIp(), ((HttpProxy) proxy).getPort());
                final var credentials = new UsernamePasswordCredentials(
                    ((AuthentProxy) proxy).getUsername(),
                    ((AuthentProxy) proxy).getPassword().toCharArray()
                );
                credentialsProvider.setCredentials(scope, credentials);
            }

            if (proxy instanceof SocksProxy) {
                SocksAuthenticator.INSTANCE.addSocksWithCredentials((SocksProxy) proxy);
            }

        }

    }

    public void setCredentials(AuthScope authScope, Credentials credentials) {
        credentialsProvider.setCredentials(authScope, credentials);
    }

    public void setCredentials(String hostname, String login, String password) {
        setCredentials(new AuthScope(hostname, -1), new UsernamePasswordCredentials(login, password.toCharArray()));
    }

    @Override
    public void close() throws IOException {
        client.close();
    }

}
