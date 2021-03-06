// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.azure.core.http.netty;

import com.azure.core.http.ProxyOptions;
import com.azure.core.http.netty.implementation.ChallengeHolder;
import com.azure.core.http.netty.implementation.DeferredHttpProxyProvider;
import com.azure.core.util.AuthorizationChallengeHandler;
import com.azure.core.util.Configuration;
import com.azure.core.util.logging.ClientLogger;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import reactor.netty.NettyPipeline;
import reactor.netty.channel.BootstrapHandlers;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.tcp.ProxyProvider;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Builder class responsible for creating instances of {@link NettyAsyncHttpClient}.
 *
 * <p><strong>Building a new HttpClient instance</strong></p>
 *
 * {@codesnippet com.azure.core.http.netty.instantiation-simple}
 *
 * @see NettyAsyncHttpClient
 * @see HttpClient
 */
public class NettyAsyncHttpClientBuilder {
    private final ClientLogger logger = new ClientLogger(NettyAsyncHttpClientBuilder.class);

    private final HttpClient baseHttpClient;
    private ProxyOptions proxyOptions;
    private ConnectionProvider connectionProvider;
    private boolean enableWiretap;
    private int port = 80;
    private EventLoopGroup eventLoopGroup;
    private Configuration configuration;
    private boolean disableBufferCopy;

    /**
     * Creates a new builder instance, where a builder is capable of generating multiple instances of {@link
     * NettyAsyncHttpClient}.
     */
    public NettyAsyncHttpClientBuilder() {
        this.baseHttpClient = null;
    }

    /**
     * Creates a new builder instance, where a builder is capable of generating multiple instances of {@link
     * NettyAsyncHttpClient} based on the provided reactor netty HttpClient.
     *
     * {@codesnippet com.azure.core.http.netty.from-existing-http-client}
     *
     * @param nettyHttpClient base reactor netty HttpClient
     */
    public NettyAsyncHttpClientBuilder(HttpClient nettyHttpClient) {
        this.baseHttpClient = Objects.requireNonNull(nettyHttpClient, "'nettyHttpClient' cannot be null.");
    }

    /**
     * Creates a new Netty-backed {@link com.azure.core.http.HttpClient} instance on every call, using the configuration
     * set in the builder at the time of the build method call.
     *
     * @return A new Netty-backed {@link com.azure.core.http.HttpClient} instance.
     * @throws IllegalStateException If the builder is configured to use an unknown proxy type.
     */
    public com.azure.core.http.HttpClient build() {
        HttpClient nettyHttpClient;
        if (this.baseHttpClient != null) {
            nettyHttpClient = baseHttpClient;
        } else if (this.connectionProvider != null) {
            nettyHttpClient = HttpClient.create(this.connectionProvider);
        } else {
            nettyHttpClient = HttpClient.create();
        }

        nettyHttpClient = nettyHttpClient
            .port(port)
            .wiretap(enableWiretap);

        Configuration buildConfiguration = (configuration == null)
            ? Configuration.getGlobalConfiguration()
            : configuration;

        ProxyOptions buildProxyOptions = (proxyOptions == null && buildConfiguration != Configuration.NONE)
            ? ProxyOptions.fromConfiguration(buildConfiguration)
            : proxyOptions;

        /*
         * Only configure the custom authorization challenge handler and challenge holder when using an authenticated
         * HTTP proxy. All other proxying such as SOCKS4, SOCKS5, and anonymous HTTP will use Netty's built-in handlers.
         */
        boolean useCustomProxyHandler = shouldUseCustomProxyHandler(buildProxyOptions);
        AuthorizationChallengeHandler handler = useCustomProxyHandler
            ? new AuthorizationChallengeHandler(buildProxyOptions.getUsername(), buildProxyOptions.getPassword())
            : null;
        AtomicReference<ChallengeHolder> proxyChallengeHolder = useCustomProxyHandler ? new AtomicReference<>() : null;

        nettyHttpClient = nettyHttpClient.tcpConfiguration(tcpClient -> {
            if (eventLoopGroup != null) {
                tcpClient = tcpClient.runOn(eventLoopGroup);
            }

            // Proxy configurations are present, setup a proxy in Netty.
            if (buildProxyOptions != null) {
                // Determine if custom handling will be used, otherwise use Netty's built-in handlers.
                if (handler != null) {
                    /*
                     * Configure the request Channel to be initialized with a ProxyHandler. The ProxyHandler is the
                     * first operation in the pipeline as it needs to handle sending a CONNECT request to the proxy
                     * before any request data is sent.
                     */
                    tcpClient = tcpClient.bootstrap(bootstrap -> BootstrapHandlers.updateConfiguration(bootstrap,
                        NettyPipeline.ProxyHandler, new DeferredHttpProxyProvider(handler, proxyChallengeHolder,
                            buildProxyOptions)));
                } else {
                    tcpClient = tcpClient.proxy(proxy ->
                        proxy.type(toReactorNettyProxyType(buildProxyOptions.getType(), logger))
                            .address(buildProxyOptions.getAddress())
                            .username(buildProxyOptions.getUsername())
                            .password(ignored -> buildProxyOptions.getPassword())
                            .nonProxyHosts(buildProxyOptions.getNonProxyHosts()));
                }
            }

            return tcpClient;
        });

        return new NettyAsyncHttpClient(nettyHttpClient, disableBufferCopy);
    }

    /**
     * Sets the connection provider.
     *
     * @param connectionProvider the connection provider
     * @return the updated {@link NettyAsyncHttpClientBuilder} object.
     */
    public NettyAsyncHttpClientBuilder connectionProvider(ConnectionProvider connectionProvider) {
        // Enables overriding the default reactor-netty connection/channel pool.
        this.connectionProvider = connectionProvider;
        return this;
    }

    /**
     * Sets the {@link ProxyOptions proxy options} that the client will use.
     *
     * @param proxyOptions The proxy configuration to use.
     * @return the updated NettyAsyncHttpClientBuilder object.
     */
    public NettyAsyncHttpClientBuilder proxy(ProxyOptions proxyOptions) {
        // proxyOptions can be null
        this.proxyOptions = proxyOptions;
        return this;
    }

    /**
     * Enables the Netty wiretap feature.
     *
     * @param enableWiretap Flag indicating wiretap status
     * @return the updated NettyAsyncHttpClientBuilder object.
     */
    public NettyAsyncHttpClientBuilder wiretap(boolean enableWiretap) {
        this.enableWiretap = enableWiretap;
        return this;
    }

    /**
     * Sets the port which this client should connect, which by default will be set to port 80.
     *
     * @param port The port to connect to.
     * @return the updated NettyAsyncHttpClientBuilder object.
     */
    public NettyAsyncHttpClientBuilder port(int port) {
        this.port = port;
        return this;
    }

    /**
     * Sets the NIO event loop group that will be used to run IO loops.
     *
     * @param nioEventLoopGroup The {@link NioEventLoopGroup} that will run IO loops.
     * @return the updated NettyAsyncHttpClientBuilder object.
     * @deprecated deprecated in favor of {@link #eventLoopGroup(EventLoopGroup)}.
     */
    @Deprecated
    public NettyAsyncHttpClientBuilder nioEventLoopGroup(NioEventLoopGroup nioEventLoopGroup) {
        this.eventLoopGroup = nioEventLoopGroup;
        return this;
    }

    /**
     * Sets the IO event loop group that will be used to run IO loops.
     *
     * <p><strong>Code Samples</strong></p>
     *
     * {@codesnippet com.azure.core.http.netty.NettyAsyncHttpClientBuilder#eventLoopGroup}
     *
     * @param eventLoopGroup The {@link EventLoopGroup} that will run IO loops.
     * @return the updated NettyAsyncHttpClientBuilder object.
     */
    public NettyAsyncHttpClientBuilder eventLoopGroup(EventLoopGroup eventLoopGroup) {
        this.eventLoopGroup = eventLoopGroup;
        return this;
    }

    /**
     * Sets the configuration store that is used during construction of the HTTP client.
     * <p>
     * The default configuration store is a clone of the {@link Configuration#getGlobalConfiguration() global
     * configuration store}, use {@link Configuration#NONE} to bypass using configuration settings during construction.
     *
     * @param configuration The configuration store used to
     * @return The updated NettyAsyncHttpClientBuilder object.
     */
    public NettyAsyncHttpClientBuilder configuration(Configuration configuration) {
        this.configuration = configuration;
        return this;
    }

    /**
     * Disables deep copy of response {@link ByteBuffer} into a heap location that is managed by this client as opposed
     * to the underlying netty library which may use direct buffer pool.
     * <br>
     * <b>
     * Caution: Disabling this is not recommended as it can lead to data corruption if the downstream consumers of the
     * response do not handle the byte buffers before netty releases them.
     * </b>
     * If copy is disabled, underlying Netty layer can potentially reclaim byte array backed by the {@code ByteBuffer}
     * upon the return of {@code onNext()}. So, users should ensure they process the {@link ByteBuffer} immediately and
     * then return.
     *
     * {@codesnippet com.azure.core.http.netty.disabled-buffer-copy}
     *
     * @param disableBufferCopy If set to {@code true}, the client built from this builder will not deep-copy response
     * {@link ByteBuffer ByteBuffers}.
     * @return The updated {@link NettyAsyncHttpClientBuilder} object.
     */
    public NettyAsyncHttpClientBuilder disableBufferCopy(boolean disableBufferCopy) {
        this.disableBufferCopy = disableBufferCopy;
        return this;
    }

    private static boolean shouldUseCustomProxyHandler(ProxyOptions options) {
        return options != null && options.getUsername() != null && options.getType() == ProxyOptions.Type.HTTP;
    }

    private static ProxyProvider.Proxy toReactorNettyProxyType(ProxyOptions.Type azureProxyType, ClientLogger logger) {
        switch (azureProxyType) {
            case HTTP:
                return ProxyProvider.Proxy.HTTP;
            case SOCKS4:
                return ProxyProvider.Proxy.SOCKS4;
            case SOCKS5:
                return ProxyProvider.Proxy.SOCKS5;
            default:
                throw logger.logExceptionAsError(
                    new IllegalArgumentException("Unknown 'ProxyOptions.Type' enum value"));
        }
    }
}
