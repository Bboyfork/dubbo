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
package org.apache.dubbo.config.deploy;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.config.Environment;
import org.apache.dubbo.common.config.ReferenceCache;
import org.apache.dubbo.common.config.configcenter.DynamicConfiguration;
import org.apache.dubbo.common.config.configcenter.DynamicConfigurationFactory;
import org.apache.dubbo.common.config.configcenter.wrapper.CompositeDynamicConfiguration;
import org.apache.dubbo.common.deploy.AbstractDeployer;
import org.apache.dubbo.common.deploy.ApplicationDeployListener;
import org.apache.dubbo.common.deploy.ApplicationDeployer;
import org.apache.dubbo.common.deploy.ModuleDeployer;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.lang.ShutdownHookCallbacks;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.threadpool.manager.ExecutorRepository;
import org.apache.dubbo.common.utils.ArrayUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ConfigCenterConfig;
import org.apache.dubbo.config.DubboShutdownHook;
import org.apache.dubbo.config.MetadataReportConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.context.ConfigManager;
import org.apache.dubbo.config.utils.CompositeReferenceCache;
import org.apache.dubbo.config.utils.ConfigValidationUtils;
import org.apache.dubbo.metadata.MetadataService;
import org.apache.dubbo.metadata.MetadataServiceExporter;
import org.apache.dubbo.metadata.WritableMetadataService;
import org.apache.dubbo.metadata.report.MetadataReportFactory;
import org.apache.dubbo.metadata.report.MetadataReportInstance;
import org.apache.dubbo.metadata.report.support.AbstractMetadataReportFactory;
import org.apache.dubbo.registry.client.DefaultServiceInstance;
import org.apache.dubbo.registry.client.ServiceInstance;
import org.apache.dubbo.registry.client.metadata.ServiceInstanceMetadataUtils;
import org.apache.dubbo.registry.client.metadata.store.InMemoryWritableMetadataService;
import org.apache.dubbo.registry.client.metadata.store.RemoteMetadataServiceImpl;
import org.apache.dubbo.registry.support.RegistryManager;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.apache.dubbo.rpc.model.ModuleModel;
import org.apache.dubbo.rpc.model.ScopeModel;
import org.apache.dubbo.rpc.model.ScopeModelUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static java.lang.String.format;
import static org.apache.dubbo.common.config.ConfigurationUtils.parseProperties;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_METADATA_STORAGE_TYPE;
import static org.apache.dubbo.common.constants.CommonConstants.REGISTRY_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.REMOTE_METADATA_STORAGE_TYPE;
import static org.apache.dubbo.common.utils.StringUtils.isEmpty;
import static org.apache.dubbo.common.utils.StringUtils.isNotEmpty;
import static org.apache.dubbo.metadata.MetadataConstants.DEFAULT_METADATA_PUBLISH_DELAY;
import static org.apache.dubbo.metadata.MetadataConstants.METADATA_PUBLISH_DELAY_KEY;
import static org.apache.dubbo.registry.client.metadata.ServiceInstanceMetadataUtils.calInstanceRevision;
import static org.apache.dubbo.registry.client.metadata.ServiceInstanceMetadataUtils.setMetadataStorageType;
import static org.apache.dubbo.remoting.Constants.CLIENT_KEY;

/**
 * initialize and start application instance
 */
public class DefaultApplicationDeployer extends AbstractDeployer<ApplicationModel> implements ApplicationDeployer {

    private static final Logger logger = LoggerFactory.getLogger(DefaultApplicationDeployer.class);

    private final ApplicationModel applicationModel;

    private final ConfigManager configManager;

    private final Environment environment;

    private final ReferenceCache referenceCache;

    private final ExecutorRepository executorRepository;

    private volatile ServiceInstance serviceInstance;

    private AtomicBoolean hasPreparedApplicationInstance = new AtomicBoolean(false);

    private volatile MetadataService metadataService;

    private volatile MetadataServiceExporter metadataServiceExporter;

    private ScheduledFuture<?> asyncMetadataFuture;
    private String identifier;
    private CompletableFuture startFuture;
    private DubboShutdownHook dubboShutdownHook;

    public DefaultApplicationDeployer(ApplicationModel applicationModel) {
        super(applicationModel);
        this.applicationModel = applicationModel;
        configManager = applicationModel.getApplicationConfigManager();
        environment = applicationModel.getModelEnvironment();

        referenceCache = new CompositeReferenceCache(applicationModel);
        executorRepository = getExtensionLoader(ExecutorRepository.class).getDefaultExtension();
        dubboShutdownHook = new DubboShutdownHook(applicationModel);

        // load spi listener
        Set<ApplicationDeployListener> deployListeners = applicationModel.getExtensionLoader(ApplicationDeployListener.class)
            .getSupportedExtensionInstances();
        for (ApplicationDeployListener listener : deployListeners) {
            this.addDeployListener(listener);
        }
    }

    public static ApplicationDeployer get(ScopeModel moduleOrApplicationModel) {
        ApplicationModel applicationModel = ScopeModelUtil.getApplicationModel(moduleOrApplicationModel);
        ApplicationDeployer applicationDeployer = applicationModel.getDeployer();
        if (applicationDeployer == null) {
            applicationDeployer = applicationModel.getBeanFactory().getOrRegisterBean(DefaultApplicationDeployer.class);
        }
        return applicationDeployer;
    }

    @Override
    public ApplicationModel getApplicationModel() {
        return applicationModel;
    }

    private <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        return applicationModel.getExtensionLoader(type);
    }

    private void unRegisterShutdownHook() {
        dubboShutdownHook.unregister();
    }

    private boolean isRegisterConsumerInstance() {
        Boolean registerConsumer = getApplication().getRegisterConsumer();
        return Boolean.TRUE.equals(registerConsumer);
    }

    private String getMetadataType() {
        String type = getApplication().getMetadataType();
        if (StringUtils.isEmpty(type)) {
            type = DEFAULT_METADATA_STORAGE_TYPE;
        }
        return type;
    }

    @Override
    public ReferenceCache getReferenceCache() {
        return referenceCache;
    }

    /**
     * Initialize
     */
    @Override
    public void initialize() {
        if (initialized.get()) {
            return;
        }
        // Ensure that the initialization is completed when concurrent calls
        synchronized (this) {
            if (initialized.get()) {
                return;
            }
            // register shutdown hook
            registerShutdownHook();

            startConfigCenter();

            loadApplicationConfigs();

            initModuleDeployers();

            // @since 2.7.8
            startMetadataCenter();

            initMetadataService();

            initialized.set(true);

            if (logger.isInfoEnabled()) {
                logger.info(getIdentifier() + " has been initialized!");
            }
        }
    }

    private void registerShutdownHook() {
        dubboShutdownHook.register();
    }

    private void initModuleDeployers() {
        // make sure created default module
        applicationModel.getDefaultModule();
        for (ModuleModel moduleModel : applicationModel.getModuleModels()) {
            moduleModel.getDeployer().initialize();
        }
    }

    private void loadApplicationConfigs() {
        configManager.loadConfigs();
    }

    private void startConfigCenter() {

        // load application config
        configManager.loadConfigsOfTypeFromProps(ApplicationConfig.class);

        // load config centers
        configManager.loadConfigsOfTypeFromProps(ConfigCenterConfig.class);

        useRegistryAsConfigCenterIfNecessary();

        // check Config Center
        Collection<ConfigCenterConfig> configCenters = configManager.getConfigCenters();
        if (CollectionUtils.isEmpty(configCenters)) {
            ConfigCenterConfig configCenterConfig = new ConfigCenterConfig();
            configCenterConfig.setScopeModel(applicationModel);
            configCenterConfig.refresh();
            ConfigValidationUtils.validateConfigCenterConfig(configCenterConfig);
            if (configCenterConfig.isValid()) {
                configManager.addConfigCenter(configCenterConfig);
                configCenters = configManager.getConfigCenters();
            }
        } else {
            for (ConfigCenterConfig configCenterConfig : configCenters) {
                configCenterConfig.refresh();
                ConfigValidationUtils.validateConfigCenterConfig(configCenterConfig);
            }
        }

        if (CollectionUtils.isNotEmpty(configCenters)) {
            CompositeDynamicConfiguration compositeDynamicConfiguration = new CompositeDynamicConfiguration();
            for (ConfigCenterConfig configCenter : configCenters) {
                // Pass config from ConfigCenterBean to environment
                environment.updateExternalConfigMap(configCenter.getExternalConfiguration());
                environment.updateAppExternalConfigMap(configCenter.getAppExternalConfiguration());

                // Fetch config from remote config center
                compositeDynamicConfiguration.addConfiguration(prepareEnvironment(configCenter));
            }
            environment.setDynamicConfiguration(compositeDynamicConfiguration);
        }

        configManager.refreshAll();
    }

    private void startMetadataCenter() {

        useRegistryAsMetadataCenterIfNecessary();

        ApplicationConfig applicationConfig = getApplication();

        String metadataType = applicationConfig.getMetadataType();
        // FIXME, multiple metadata config support.
        Collection<MetadataReportConfig> metadataReportConfigs = configManager.getMetadataConfigs();
        if (CollectionUtils.isEmpty(metadataReportConfigs)) {
            if (REMOTE_METADATA_STORAGE_TYPE.equals(metadataType)) {
                throw new IllegalStateException("No MetadataConfig found, Metadata Center address is required when 'metadata=remote' is enabled.");
            }
            return;
        }

        MetadataReportInstance metadataReportInstance = applicationModel.getBeanFactory().getBean(MetadataReportInstance.class);
        for (MetadataReportConfig metadataReportConfig : metadataReportConfigs) {
            ConfigValidationUtils.validateMetadataConfig(metadataReportConfig);
            if (!metadataReportConfig.isValid()) {
                logger.info("Ignore invalid metadata-report config: " + metadataReportConfig);
                continue;
            }
            metadataReportInstance.init(metadataReportConfig);
        }
    }

    /**
     * For compatibility purpose, use registry as the default config center when
     * there's no config center specified explicitly and
     * useAsConfigCenter of registryConfig is null or true
     */
    private void useRegistryAsConfigCenterIfNecessary() {
        // we use the loading status of DynamicConfiguration to decide whether ConfigCenter has been initiated.
        if (environment.getDynamicConfiguration().isPresent()) {
            return;
        }

        if (CollectionUtils.isNotEmpty(configManager.getConfigCenters())) {
            return;
        }

        // load registry
        configManager.loadConfigsOfTypeFromProps(RegistryConfig.class);

        List<RegistryConfig> defaultRegistries = configManager.getDefaultRegistries();
        if (defaultRegistries.size() > 0) {
            defaultRegistries
                .stream()
                .filter(this::isUsedRegistryAsConfigCenter)
                .map(this::registryAsConfigCenter)
                .forEach(configCenter -> {
                    if (configManager.getConfigCenter(configCenter.getId()).isPresent()) {
                        return;
                    }
                    configManager.addConfigCenter(configCenter);
                    logger.info("use registry as config-center: " + configCenter);

                });
        }
    }

    private boolean isUsedRegistryAsConfigCenter(RegistryConfig registryConfig) {
        return isUsedRegistryAsCenter(registryConfig, registryConfig::getUseAsConfigCenter, "config",
            DynamicConfigurationFactory.class);
    }

    private ConfigCenterConfig registryAsConfigCenter(RegistryConfig registryConfig) {
        String protocol = registryConfig.getProtocol();
        Integer port = registryConfig.getPort();
        URL url = URL.valueOf(registryConfig.getAddress(), registryConfig.getScopeModel());
        String id = "config-center-" + protocol + "-" + url.getHost() + "-" + port;
        ConfigCenterConfig cc = new ConfigCenterConfig();
        cc.setId(id);
        cc.setScopeModel(applicationModel);
        if (cc.getParameters() == null) {
            cc.setParameters(new HashMap<>());
        }
        if (registryConfig.getParameters() != null) {
            cc.getParameters().putAll(registryConfig.getParameters()); // copy the parameters
        }
        cc.getParameters().put(CLIENT_KEY, registryConfig.getClient());
        cc.setProtocol(protocol);
        cc.setPort(port);
        if (StringUtils.isNotEmpty(registryConfig.getGroup())) {
            cc.setGroup(registryConfig.getGroup());
        }
        cc.setAddress(getRegistryCompatibleAddress(registryConfig));
        cc.setNamespace(registryConfig.getGroup());
        cc.setUsername(registryConfig.getUsername());
        cc.setPassword(registryConfig.getPassword());
        if (registryConfig.getTimeout() != null) {
            cc.setTimeout(registryConfig.getTimeout().longValue());
        }
        cc.setHighestPriority(false);
        return cc;
    }

    private void useRegistryAsMetadataCenterIfNecessary() {

        Collection<MetadataReportConfig> metadataConfigs = configManager.getMetadataConfigs();

        if (CollectionUtils.isNotEmpty(metadataConfigs)) {
            return;
        }

        List<RegistryConfig> defaultRegistries = configManager.getDefaultRegistries();
        if (defaultRegistries.size() > 0) {
            defaultRegistries
                .stream()
                .filter(this::isUsedRegistryAsMetadataCenter)
                .map(this::registryAsMetadataCenter)
                .forEach(metadataReportConfig -> {
                    Optional<MetadataReportConfig> configOptional = configManager.getConfig(MetadataReportConfig.class, metadataReportConfig.getId());
                    if (configOptional.isPresent()) {
                        return;
                    }
                    configManager.addMetadataReport(metadataReportConfig);
                    logger.info("use registry as metadata-center: " + metadataReportConfig);
                });
        }
    }

    private boolean isUsedRegistryAsMetadataCenter(RegistryConfig registryConfig) {
        return isUsedRegistryAsCenter(registryConfig, registryConfig::getUseAsMetadataCenter, "metadata",
            MetadataReportFactory.class);
    }

    /**
     * Is used the specified registry as a center infrastructure
     *
     * @param registryConfig       the {@link RegistryConfig}
     * @param usedRegistryAsCenter the configured value on
     * @param centerType           the type name of center
     * @param extensionClass       an extension class of a center infrastructure
     * @return
     * @since 2.7.8
     */
    private boolean isUsedRegistryAsCenter(RegistryConfig registryConfig, Supplier<Boolean> usedRegistryAsCenter,
                                           String centerType,
                                           Class<?> extensionClass) {
        final boolean supported;

        Boolean configuredValue = usedRegistryAsCenter.get();
        if (configuredValue != null) { // If configured, take its value.
            supported = configuredValue.booleanValue();
        } else {                       // Or check the extension existence
            String protocol = registryConfig.getProtocol();
            supported = supportsExtension(extensionClass, protocol);
            if (logger.isInfoEnabled()) {
                logger.info(format("No value is configured in the registry, the %s extension[name : %s] %s as the %s center"
                    , extensionClass.getSimpleName(), protocol, supported ? "supports" : "does not support", centerType));
            }
        }

        if (logger.isInfoEnabled()) {
            logger.info(format("The registry[%s] will be %s as the %s center", registryConfig,
                supported ? "used" : "not used", centerType));
        }
        return supported;
    }

    /**
     * Supports the extension with the specified class and name
     *
     * @param extensionClass the {@link Class} of extension
     * @param name           the name of extension
     * @return if supports, return <code>true</code>, or <code>false</code>
     * @since 2.7.8
     */
    private boolean supportsExtension(Class<?> extensionClass, String name) {
        if (isNotEmpty(name)) {
            ExtensionLoader extensionLoader = getExtensionLoader(extensionClass);
            return extensionLoader.hasExtension(name);
        }
        return false;
    }

    private MetadataReportConfig registryAsMetadataCenter(RegistryConfig registryConfig) {
        String protocol = registryConfig.getProtocol();
        URL url = URL.valueOf(registryConfig.getAddress(), registryConfig.getScopeModel());
        String id = "metadata-center-" + protocol + "-" + url.getHost() + "-" + url.getPort();
        MetadataReportConfig metadataReportConfig = new MetadataReportConfig();
        metadataReportConfig.setId(id);
        metadataReportConfig.setScopeModel(applicationModel);
        if (metadataReportConfig.getParameters() == null) {
            metadataReportConfig.setParameters(new HashMap<>());
        }
        if (registryConfig.getParameters() != null) {
            metadataReportConfig.getParameters().putAll(registryConfig.getParameters()); // copy the parameters
        }
        metadataReportConfig.getParameters().put(CLIENT_KEY, registryConfig.getClient());
        metadataReportConfig.setGroup(registryConfig.getGroup());
        metadataReportConfig.setAddress(getRegistryCompatibleAddress(registryConfig));
        metadataReportConfig.setUsername(registryConfig.getUsername());
        metadataReportConfig.setPassword(registryConfig.getPassword());
        metadataReportConfig.setTimeout(registryConfig.getTimeout());
        return metadataReportConfig;
    }

    private String getRegistryCompatibleAddress(RegistryConfig registryConfig) {
        String registryAddress = registryConfig.getAddress();
        String[] addresses = REGISTRY_SPLIT_PATTERN.split(registryAddress);
        if (ArrayUtils.isEmpty(addresses)) {
            throw new IllegalStateException("Invalid registry address found.");
        }
        String address = addresses[0];
        // since 2.7.8
        // Issue : https://github.com/apache/dubbo/issues/6476
        StringBuilder metadataAddressBuilder = new StringBuilder();
        URL url = URL.valueOf(address, registryConfig.getScopeModel());
        String protocolFromAddress = url.getProtocol();
        if (isEmpty(protocolFromAddress)) {
            // If the protocol from address is missing, is like :
            // "dubbo.registry.address = 127.0.0.1:2181"
            String protocolFromConfig = registryConfig.getProtocol();
            metadataAddressBuilder.append(protocolFromConfig).append("://");
        }
        metadataAddressBuilder.append(address);
        return metadataAddressBuilder.toString();
    }

    /**
     * Initialize {@link MetadataService} from {@link WritableMetadataService}'s extension
     */
    private void initMetadataService() {
//        startMetadataCenter();
        this.metadataService = getExtensionLoader(WritableMetadataService.class).getDefaultExtension();
        // support injection by super type MetadataService
        applicationModel.getBeanFactory().registerBean(this.metadataService);

        //this.metadataServiceExporter = new ConfigurableMetadataServiceExporter(metadataService);
        this.metadataServiceExporter = getExtensionLoader(MetadataServiceExporter.class).getDefaultExtension();
    }

    /**
     * Start the bootstrap
     *
     * @return
     */
    @Override
    public synchronized CompletableFuture start() {
        if (isStarting()) {
            return startFuture;
        }
        startFuture = new CompletableFuture();
        if (isStarted()) {
            // maybe call start again after add new module, check if any new module
            boolean hasNewModule = false;
            for (ModuleModel moduleModel : applicationModel.getModuleModels()) {
                if (moduleModel.getDeployer().isPending()) {
                    hasNewModule = true;
                    break;
                }
            }
            // if no new module, just return
            if (!hasNewModule) {
                startFuture.complete(false);
                return startFuture;
            }
        }

        onStarting();

        initialize();

        doStart();

        return startFuture;
    }

    private void doStart() {
        // copy current modules, ignore new module during starting
        List<ModuleModel> moduleModels = new ArrayList<>(applicationModel.getModuleModels());
        List<CompletableFuture> futures = new ArrayList<>(moduleModels.size());

        for (ModuleModel moduleModel : moduleModels) {
            // export services in module
            if (moduleModel.getDeployer().isPending()) {
                CompletableFuture moduleFuture = moduleModel.getDeployer().start();
                futures.add(moduleFuture);
            }
        }

        // prepare application instance
        prepareApplicationInstance();

        // notify on each module started
//        executorRepository.getSharedExecutor().submit(()-> {
//            awaitDeployFinished(futures);
//            onStarted();
//        });
    }

    private void awaitDeployFinished(List<CompletableFuture> futures) {
        try {
            CompletableFuture mergedFuture = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
            mergedFuture.get();
        } catch (Exception e) {
            logger.error(getIdentifier() + " await deploy finished failed", e);
        }
    }

    @Override
    public void prepareApplicationInstance() {
        if (hasPreparedApplicationInstance.get()) {
            return;
        }
        // if register consumer instance or has exported services
        if (isRegisterConsumerInstance() || hasExportedServices()) {
            if (!hasPreparedApplicationInstance.compareAndSet(false, true)) {
                return;
            }
            prepareInternalModule();
            // register the local ServiceInstance if required
            registerServiceInstance();
        }
    }

    private void prepareInternalModule() {
        // export MetadataService
        exportMetadataService();
        // start internal module
        ModuleDeployer internalModuleDeployer = applicationModel.getInternalModule().getDeployer();
        if (!internalModuleDeployer.isRunning()) {
            internalModuleDeployer.start();
        }
    }

    private boolean hasExportedServices() {
        for (ModuleModel moduleModel : applicationModel.getModuleModels()) {
            if (CollectionUtils.isNotEmpty(moduleModel.getConfigManager().getServices())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isBackground() {
        for (ModuleModel moduleModel : applicationModel.getModuleModels()) {
            if (moduleModel.getDeployer().isBackground()) {
                return true;
            }
        }
        return false;
    }

    private DynamicConfiguration prepareEnvironment(ConfigCenterConfig configCenter) {
        if (configCenter.isValid()) {
            if (!configCenter.checkOrUpdateInitialized(true)) {
                return null;
            }

            DynamicConfiguration dynamicConfiguration = null;
            try {
                dynamicConfiguration = getDynamicConfiguration(configCenter.toUrl());
            } catch (Exception e) {
                if (!configCenter.isCheck()) {
                    logger.warn("The configuration center failed to initialize", e);
                    configCenter.checkOrUpdateInitialized(false);
                    return null;
                } else {
                    throw new IllegalStateException(e);
                }
            }

            String configContent = dynamicConfiguration.getProperties(configCenter.getConfigFile(), configCenter.getGroup());

            String appGroup = getApplication().getName();
            String appConfigContent = null;
            if (isNotEmpty(appGroup)) {
                appConfigContent = dynamicConfiguration.getProperties
                    (isNotEmpty(configCenter.getAppConfigFile()) ? configCenter.getAppConfigFile() : configCenter.getConfigFile(),
                        appGroup
                    );
            }
            try {
                environment.updateExternalConfigMap(parseProperties(configContent));
                environment.updateAppExternalConfigMap(parseProperties(appConfigContent));
            } catch (IOException e) {
                throw new IllegalStateException("Failed to parse configurations from Config Center.", e);
            }
            return dynamicConfiguration;
        }
        return null;
    }

    /**
     * Get the instance of {@link DynamicConfiguration} by the specified connection {@link URL} of config-center
     *
     * @param connectionURL of config-center
     * @return non-null
     * @since 2.7.5
     */
    private DynamicConfiguration getDynamicConfiguration(URL connectionURL) {
        String protocol = connectionURL.getProtocol();

        DynamicConfigurationFactory factory = ConfigurationUtils.getDynamicConfigurationFactory(applicationModel, protocol);
        return factory.getDynamicConfiguration(connectionURL);
    }

    /**
     * export {@link MetadataService}
     */
    private void exportMetadataService() {
        metadataServiceExporter.export();
    }

    private void unexportMetadataService() {
        if (metadataServiceExporter != null && metadataServiceExporter.isExported()) {
            try {
                metadataServiceExporter.unexport();
            } catch (Exception ignored) {
                // ignored
            }
        }
    }

    private void registerServiceInstance() {
        if (isRegisteredServiceInstance()) {
            return;
        }

        ApplicationConfig application = getApplication();
        String serviceName = application.getName();
        ServiceInstance serviceInstance = createServiceInstance(serviceName);
        boolean registered = true;
        try {
            ServiceInstanceMetadataUtils.registerMetadataAndInstance(serviceInstance);
        } catch (Exception e) {
            registered = false;
            logger.error("Register instance error", e);
        }
        if (registered) {
            // scheduled task for updating Metadata and ServiceInstance
            asyncMetadataFuture = executorRepository.nextScheduledExecutor().scheduleAtFixedRate(() -> {
                InMemoryWritableMetadataService localMetadataService = (InMemoryWritableMetadataService) WritableMetadataService.getDefaultExtension(applicationModel);
                if (!applicationModel.getDeployer().isStopping() || !applicationModel.getDeployer().isStopped()) {
                    localMetadataService.blockUntilUpdated();
                }
                try {
                    ServiceInstanceMetadataUtils.refreshMetadataAndInstance(serviceInstance);
                } catch (Exception e) {
                    logger.error("Refresh instance and metadata error", e);
                } finally {
                    localMetadataService.releaseBlock();
                }
            }, 0, ConfigurationUtils.get(applicationModel, METADATA_PUBLISH_DELAY_KEY, DEFAULT_METADATA_PUBLISH_DELAY), TimeUnit.MILLISECONDS);
        }
    }

    private boolean isRegisteredServiceInstance() {
        return this.serviceInstance != null;
    }

    private void doRegisterServiceInstance(ServiceInstance serviceInstance) {
        // register instance only when at least one service is exported.
        if (serviceInstance.getPort() > 0) {
            publishMetadataToRemote(serviceInstance);
            logger.info("Start registering instance address to registry.");
            RegistryManager.getInstance(applicationModel).getServiceDiscoveries().forEach(serviceDiscovery ->
            {
                ServiceInstance serviceInstanceForRegistry = new DefaultServiceInstance((DefaultServiceInstance) serviceInstance);
                calInstanceRevision(serviceDiscovery, serviceInstanceForRegistry);
                if (logger.isDebugEnabled()) {
                    logger.info("Start registering instance address to registry" + serviceDiscovery.getUrl() + ", instance " + serviceInstanceForRegistry);
                }
                // register metadata
                serviceDiscovery.register(serviceInstanceForRegistry);
            });
        }
    }

    private void publishMetadataToRemote(ServiceInstance serviceInstance) {
//        InMemoryWritableMetadataService localMetadataService = (InMemoryWritableMetadataService)WritableMetadataService.getDefaultExtension();
//        localMetadataService.blockUntilUpdated();
        if (logger.isInfoEnabled()) {
            logger.info("Start publishing metadata to remote center, this only makes sense for applications enabled remote metadata center.");
        }
        RemoteMetadataServiceImpl remoteMetadataService = applicationModel.getBeanFactory().getBean(RemoteMetadataServiceImpl.class);
        remoteMetadataService.publishMetadata(serviceInstance.getServiceName());
    }

    private void unregisterServiceInstance() {
        if (isRegisteredServiceInstance()) {
            RegistryManager.getInstance(applicationModel).getServiceDiscoveries().forEach(serviceDiscovery -> {
                try {
                    serviceDiscovery.unregister(serviceInstance);
                } catch (Exception ignored) {
                    // ignored
                }
            });
        }
    }

    private ServiceInstance createServiceInstance(String serviceName) {
        this.serviceInstance = new DefaultServiceInstance(serviceName, applicationModel);
        setMetadataStorageType(serviceInstance, getMetadataType());
        ServiceInstanceMetadataUtils.customizeInstance(this.serviceInstance);
        return this.serviceInstance;
    }

    public ServiceInstance getServiceInstance() {
        return serviceInstance;
    }

    @Override
    public void stop() {
        destroy();
    }

    @Override
    public synchronized void destroy() {
        if (isStopping() || isStopped()) {
            return;
        }
        try {
            onStopping();
            unRegisterShutdownHook();
            unregisterServiceInstance();
            unexportMetadataService();
            if (asyncMetadataFuture != null) {
                asyncMetadataFuture.cancel(true);
            }

            executeShutdownCallbacks();

            applicationModel.destroy();

            destroyProtocols();

            destroyRegistries();
            destroyServiceDiscoveries();
            destroyMetadataReports();

            destroyServiceDiscoveries();
            destroyExecutorRepository();
            destroyDynamicConfigurations();

            onStopped();
        } catch (Throwable ex) {
            logger.error(getIdentifier() + " an error occurred while stopping application: " + ex.getMessage(), ex);
            setFailed(ex);
        }
    }

    private void executeShutdownCallbacks() {
        ShutdownHookCallbacks shutdownHookCallbacks = applicationModel.getBeanFactory().getBean(ShutdownHookCallbacks.class);
        shutdownHookCallbacks.callback();
    }

    @Override
    public void checkStarting() {
        if (isStarting()) {
            return;
        }
        onStarting();
    }

    @Override
    public void checkStarted(CompletableFuture checkerStartFuture) {
        for (ModuleModel moduleModel : applicationModel.getModuleModels()) {
            if (moduleModel.getDeployer().isPending()) {
                setPending();
            } else if (moduleModel.getDeployer().isStarting()) {
                return;
            }
        }
        // all modules has been started
        onStarted(checkerStartFuture);
    }

    private void onStarting() {
        setStarting();
        if (logger.isInfoEnabled()) {
            logger.info(getIdentifier() + " is starting.");
        }
    }

    private void onStarted(CompletableFuture checkerStartFuture) {
        setStarted();
        if (logger.isInfoEnabled()) {
            logger.info(getIdentifier() + " is ready.");
        }
        if (startFuture != null) {
            startFuture.complete(true);
        }
        if (checkerStartFuture != null) {
            checkerStartFuture.complete(true);
        }
    }

    private void onStopping() {
        applicationModel.setStopping();
        setStopping();
        if (logger.isInfoEnabled()) {
            logger.info(getIdentifier() + " is stopping.");
        }
    }

    private void onStopped() {
        setStopped();
        if (logger.isInfoEnabled()) {
            logger.info(getIdentifier() + " has stopped.");
        }

    }

    private void destroyExecutorRepository() {
        getExtensionLoader(ExecutorRepository.class).getDefaultExtension().destroyAll();
    }

    private void destroyRegistries() {
        RegistryManager.getInstance(applicationModel).destroyAll();
    }

    /**
     * Destroy all the protocols.
     */
    private void destroyProtocols() {
        FrameworkModel frameworkModel = applicationModel.getFrameworkModel();
        if (frameworkModel.getApplicationModels().isEmpty()) {
            //TODO destroy protocol in framework scope
            ExtensionLoader<Protocol> loader = frameworkModel.getExtensionLoader(Protocol.class);
            for (String protocolName : loader.getLoadedExtensions()) {
                try {
                    Protocol protocol = loader.getLoadedExtension(protocolName);
                    if (protocol != null) {
                        protocol.destroy();
                    }
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }
    }

    private void destroyServiceDiscoveries() {
        RegistryManager.getInstance(applicationModel).getServiceDiscoveries().forEach(serviceDiscovery -> {
            try {
                serviceDiscovery.destroy();
            } catch (Throwable ignored) {
                logger.warn(ignored.getMessage(), ignored);
            }
        });
        if (logger.isDebugEnabled()) {
            logger.debug(getIdentifier() + "'s all ServiceDiscoveries have been destroyed.");
        }
    }

    private void destroyMetadataReports() {
        // TODO only destroy MetadataReport of this application
        AbstractMetadataReportFactory.destroy();
    }

    private void destroyDynamicConfigurations() {
        // TODO only destroy DynamicConfiguration of this application
        // DynamicConfiguration may be cached somewhere, and maybe used during destroy
        // destroy them may cause some troubles, so just clear instances cache
        // ExtensionLoader.resetExtensionLoader(DynamicConfigurationFactory.class);
    }

    private ApplicationConfig getApplication() {
        return configManager.getApplicationOrElseThrow();
    }

    private String getIdentifier() {
        if (identifier == null) {
            if (applicationModel.getModelName() != null && !StringUtils.isEquals(applicationModel.getModelName(), applicationModel.getInternalName())) {
                identifier = applicationModel.getModelName() + "[" + applicationModel.getInternalId() + "]";
            } else {
                identifier = "Dubbo Application" + "[" + applicationModel.getInternalId() + "]";
            }
        }
        return identifier;
    }

}
