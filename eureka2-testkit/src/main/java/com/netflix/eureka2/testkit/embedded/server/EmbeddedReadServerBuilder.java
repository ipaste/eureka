package com.netflix.eureka2.testkit.embedded.server;

import java.util.ArrayList;
import java.util.List;

import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.Scopes;
import com.google.inject.util.Modules;
import com.netflix.eureka2.channel.InterestChannel;
import com.netflix.eureka2.client.EurekaInterestClient;
import com.netflix.eureka2.client.EurekaRegistrationClient;
import com.netflix.eureka2.client.Eurekas;
import com.netflix.eureka2.client.channel.ClientChannelFactory;
import com.netflix.eureka2.client.channel.InterestChannelFactory;
import com.netflix.eureka2.client.interest.BatchAwareIndexRegistry;
import com.netflix.eureka2.client.interest.BatchingRegistry;
import com.netflix.eureka2.client.resolver.ServerResolver;
import com.netflix.eureka2.config.BasicEurekaTransportConfig;
import com.netflix.eureka2.interests.IndexRegistryImpl;
import com.netflix.eureka2.registry.SourcedEurekaRegistry;
import com.netflix.eureka2.registry.SourcedEurekaRegistryImpl;
import com.netflix.eureka2.registry.instance.InstanceInfo;
import com.netflix.eureka2.server.AbstractEurekaServer;
import com.netflix.eureka2.server.EurekaReadServerConfigurationModule;
import com.netflix.eureka2.server.EurekaReadServerModule;
import com.netflix.eureka2.server.config.EurekaServerConfig;
import com.netflix.eureka2.server.interest.FullFetchBatchingRegistry;
import com.netflix.eureka2.server.interest.FullFetchInterestClient;
import com.netflix.eureka2.server.module.CommonEurekaServerModule;
import com.netflix.eureka2.server.module.EurekaExtensionModule;
import com.netflix.eureka2.server.spi.ExtAbstractModule.ServerType;
import com.netflix.eureka2.server.transport.tcp.interest.TcpInterestServer;
import com.netflix.eureka2.testkit.netrouter.NetworkRouter;
import com.netflix.governator.Governator;
import com.netflix.governator.LifecycleInjector;

import static com.netflix.eureka2.metric.EurekaRegistryMetricFactory.registryMetrics;
import static com.netflix.eureka2.metric.client.EurekaClientMetricFactory.clientMetrics;
import static com.netflix.eureka2.server.config.ServerConfigurationNames.DEFAULT_CONFIG_PREFIX;

/**
 * @author Tomasz Bak
 */
public class EmbeddedReadServerBuilder extends EmbeddedServerBuilder<EurekaServerConfig, EmbeddedReadServerBuilder> {

    private final String serverId;
    private ServerResolver registrationResolver;
    private ServerResolver interestResolver;

    public EmbeddedReadServerBuilder(String serverId) {
        this.serverId = serverId;
    }

    public EmbeddedReadServerBuilder withRegistrationResolver(ServerResolver registrationResolver) {
        this.registrationResolver = registrationResolver;
        return this;
    }

    public EmbeddedReadServerBuilder withInterestResolver(ServerResolver interestResolver) {
        this.interestResolver = interestResolver;
        return this;
    }

    public EmbeddedReadServer build() {
        EurekaRegistrationClient registrationClient = Eurekas.newRegistrationClientBuilder()
                .withServerResolver(registrationResolver)
                .build();

        // TODO We need to better encapsulate EurekaInterestClient construction
        BatchingRegistry<InstanceInfo> remoteBatchingRegistry = new FullFetchBatchingRegistry<>();
        BatchAwareIndexRegistry<InstanceInfo> indexRegistry = new BatchAwareIndexRegistry<>(
                new IndexRegistryImpl<InstanceInfo>(), remoteBatchingRegistry);

        BasicEurekaTransportConfig transportConfig = new BasicEurekaTransportConfig.Builder().build();
        SourcedEurekaRegistry<InstanceInfo> registry = new SourcedEurekaRegistryImpl(indexRegistry, registryMetrics());
        ClientChannelFactory<InterestChannel> channelFactory = new InterestChannelFactory(
                serverId,
                transportConfig,
                interestResolver,
                registry,
                remoteBatchingRegistry,
                clientMetrics()
        );

        EurekaInterestClient interestClient = new FullFetchInterestClient(registry, channelFactory);

        List<Module> coreModules = new ArrayList<>();

        if (configuration == null) {
            coreModules.add(EurekaReadServerConfigurationModule.fromArchaius(DEFAULT_CONFIG_PREFIX));
        } else {
            coreModules.add(EurekaReadServerConfigurationModule.fromConfig(configuration));
        }
        coreModules.add(new CommonEurekaServerModule());
        if (ext) {
            coreModules.add(new EurekaExtensionModule(ServerType.Read));
        }
        coreModules.add(new EurekaReadServerModule(registrationClient, interestClient));
        if (adminUI) {
            coreModules.add(new EmbeddedKaryonAdminModule(configuration.getEurekaTransport().getWebAdminPort()));
        }

        List<Module> overrides = new ArrayList<>();
        overrides.add(
                new AbstractModule() {
                    @Override
                    protected void configure() {
                        bind(AbstractEurekaServer.class).to(EmbeddedReadServer.class);
                    }
                }
        );
        if (networkRouter != null) {
            overrides.add(new NetworkRouterModule(networkRouter));
        }

        LifecycleInjector injector = Governator.createInjector(Modules.override(Modules.combine(coreModules)).with(overrides));
        return injector.getInstance(EmbeddedReadServer.class);
    }

    static class NetworkRouterModule extends AbstractModule {

        private final NetworkRouter networkRouter;

        NetworkRouterModule(NetworkRouter networkRouter) {
            this.networkRouter = networkRouter;
        }

        @Override
        protected void configure() {
            bind(NetworkRouter.class).toInstance(networkRouter);
            bind(TcpInterestServer.class).to(EmbeddedTcpInterestServer.class).in(Scopes.SINGLETON);
        }
    }
}
