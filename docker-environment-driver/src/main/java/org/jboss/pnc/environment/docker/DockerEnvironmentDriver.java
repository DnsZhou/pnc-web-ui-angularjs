package org.jboss.pnc.environment.docker;

import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.core.MediaType;

import org.jboss.pnc.common.Configuration;
import org.jboss.pnc.common.json.ConfigurationParseException;
import org.jboss.pnc.common.json.moduleconfig.DockerEnvironmentDriverModuleConfig;
import org.jboss.pnc.model.BuildType;
import org.jboss.pnc.model.Environment;
import org.jboss.pnc.model.OperationalSystem;
import org.jboss.pnc.spi.environment.EnvironmentDriver;
import org.jboss.pnc.spi.environment.RunningEnvironment;
import org.jboss.pnc.spi.environment.exception.EnvironmentDriverException;
import org.jboss.pnc.spi.repositorymanager.model.RepositoryConfiguration;
import org.jboss.resteasy.client.ClientRequest;
import org.jboss.resteasy.client.ClientResponse;
import org.jclouds.ContextBuilder;
import org.jclouds.compute.ComputeServiceContext;
import org.jclouds.docker.DockerApi;
import org.jclouds.docker.domain.Config;
import org.jclouds.docker.domain.Container;
import org.jclouds.docker.domain.HostConfig;
import org.jclouds.docker.features.RemoteApi;
import org.jclouds.domain.LoginCredentials;
import org.jclouds.http.handlers.BackoffLimitedRetryHandler;
import org.jclouds.io.Payloads;
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.jclouds.ssh.SshClient;
import org.jclouds.sshj.SshjSshClient;
import org.jclouds.sshj.config.SshjSshClientModule;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.HostAndPort;
import com.google.inject.Module;
import com.jcraft.jsch.agentproxy.Connector;

/**
 * Implementation of environment driver, which uses Docker to run environments
 * 
 * @author Jakub Bartecek <jbartece@redhat.com>
 *
 */
@ApplicationScoped
public class DockerEnvironmentDriver implements EnvironmentDriver {

    private static final Logger logger =
            Logger.getLogger(MethodHandles.lookup().lookupClass().getName());

    /** ID of docker image */
    private static final String IMAGE_ID = "jbartece/isshd-jenkins";

    @Inject
    private Generator generator;

    @Inject
    private ConfigurationBuilder configBuilder;

    @Inject
    private Configuration<DockerEnvironmentDriverModuleConfig> configurationService;

    private ComputeServiceContext dockerContext;

    private RemoteApi dockerClient;

    /** User in running environment to which we can connect with SSH */
    private String containerUser;

    /** Password of user to which we can connect with SSH */
    private String containerUserPsswd;

    /** Connection URL to Docker control port */
    private String dockerEndpoint;

    private String dockerIp;

    /**
     * States of creating Docker container
     * 
     * @author Jakub Bartecek <jbartece@redhat.com>
     *
     */
    private enum BuildContainerState {
        NOT_BUILT,
        BUILT,
        STARTED,
    }

    /**
     * Prepares connection to Docker daemon
     * 
     * @throws ConfigurationParseException Thrown if configuration cannot be obtained
     */
    @PostConstruct
    private void init() throws ConfigurationParseException {
        DockerEnvironmentDriverModuleConfig config =
                configurationService.getModuleConfig(DockerEnvironmentDriverModuleConfig.class);

        dockerIp = config.getIp();
        dockerEndpoint = "http://" + dockerIp + ":2375";
        containerUser = config.getInContainerUser();
        containerUserPsswd = config.getInContainerUserPassword();

        dockerContext = ContextBuilder.newBuilder("docker")
                .endpoint(dockerEndpoint)
                .credentials(containerUser, containerUserPsswd)
                .modules(ImmutableSet.<Module> of(new SLF4JLoggingModule(),
                        new SshjSshClientModule()))
                .buildView(ComputeServiceContext.class);
        dockerClient = dockerContext.unwrapApi(DockerApi.class).getRemoteApi();
    }

    @Override
    public RunningEnvironment buildEnvironment(Environment buildEnvironment, 
            RepositoryConfiguration repositoryConfiguration) throws EnvironmentDriverException {
        if (!canBuildEnvironment(buildEnvironment))
            throw new UnsupportedOperationException(
                    "DockerEnvironmentDriver currently provides support only for Linux enviroments on Docker.");

        String containerId = generator.generateContainerId();
        BuildContainerState buildContainerState = BuildContainerState.NOT_BUILT;

        logger.info("Trying to start Docker container...");
        int sshPort, jenkinsPort;
        try {
            Container createdContainer = dockerClient.createContainer(
                    containerId,
                    Config.builder()
                            .imageId(IMAGE_ID)
                            .build());
            buildContainerState = BuildContainerState.BUILT;

            dockerClient.startContainer(containerId,
                    HostConfig.builder()
                            .publishAllPorts(true)
                            .build());
            buildContainerState = BuildContainerState.STARTED;

            Map<String, HostPortMapping> containerPortMappings =
                    getContainerPortMappings(createdContainer.getId());
            // Find out, which ports are opened for SSH and Jenkins
            sshPort = getSshPort(containerPortMappings);
            jenkinsPort = getJenkinsPort(containerPortMappings);

            copyFileToContainer(sshPort, "/root/.m2/settings.xml",
                    configBuilder.createMavenConfig(repositoryConfiguration.getConnectionInfo().getDependencyUrl(), 
                            repositoryConfiguration.getConnectionInfo().getDeployUrl()), null);
        } catch (Exception e) {
            // Creating container failed => clean up
            if (buildContainerState != BuildContainerState.NOT_BUILT) {
                if (buildContainerState == BuildContainerState.BUILT)
                    destroyContainer(containerId, false);
                else
                    destroyContainer(containerId, true);
            }
            throw new EnvironmentDriverException("Cannot create environment.", e);
        }

        logger.info("Created and started Docker container. ID: " + containerId
                + ", SSH port: " + sshPort + ", Jenkins Port: " + jenkinsPort);
        return new DockerRunningEnvironment(this, repositoryConfiguration, containerId, jenkinsPort, sshPort);
    }

    @Override
    public boolean canBuildEnvironment(Environment environment) {
        if (environment.getBuildType() == BuildType.DOCKER &&
                environment.getOperationalSystem() == OperationalSystem.LINUX)
            return true;
        else
            return false;
    }

    /**
     * Destroys running container
     * 
     * @param containerId ID of container
     * @throws EnvironmentDriverException Thrown if any error occurs during destroying running environment
     */
    public void destroyEnvironment(String containerId) throws EnvironmentDriverException {
        destroyContainer(containerId, true);
    }

    /**
     * Destroys container
     * 
     * @param containerId ID of container
     * @param isRunning True if the container is running
     * @throws EnvironmentDriverException Thrown if any error occurs during destroying running environment
     */
    private void destroyContainer(String containerId, boolean isRunning) throws EnvironmentDriverException {
        logger.info("Trying to start Docker container with ID: " + containerId);
        try {
            if (isRunning)
                dockerClient.stopContainer(containerId);
            dockerClient.removeContainer(containerId);
        } catch (RuntimeException e) {
            throw new EnvironmentDriverException("Cannot destroy environment.", e);
        }
        logger.info("Stopped Docker container with ID: " + containerId);
    }

    /**
     * Copy file to container using SSH tunnel.
     * Data can be passed using stringData or streamData variable. Exactly one of this variables has to be not null.
     * 
     * @param sshPort Target port on which SSH service is running
     * @param pathOnHost Path in target container, where the data are passed
     * @param stringData Data, which will be transfered to the target container (may be null if streamData are set)
     * @param streamData Data, which will be transfered to the target container (may be null if stringData are set)
     * @throws EnvironmentDriverException Thrown if both stringData and streamData are null
     */
    public void copyFileToContainer(int sshPort, String pathOnHost, String stringData,
            InputStream streamData) throws EnvironmentDriverException {
        SshClient sshClient = new SshjSshClient(new BackoffLimitedRetryHandler() {
        }, // TODO check retryHandler configuration
                HostAndPort.fromParts(dockerIp, sshPort),
                LoginCredentials.builder().user(containerUser).password(containerUserPsswd).build(),
                1000, Optional.<Connector> absent());

        try {
            sshClient.connect();
            if (stringData != null)
                sshClient.put(pathOnHost, stringData);
            else {
                if (streamData != null)
                    sshClient.put(pathOnHost, Payloads.newInputStreamPayload(streamData));
                else
                    throw new EnvironmentDriverException(
                            "It is not possible to send null data to the container.");
            }

        } finally {
            if (sshClient != null)
                sshClient.disconnect();
        }

    }

    /**
     * Gets public host port of Jenkins
     * 
     * @param ports Port mappings of container
     * @return Public host port of Jenkins
     */
    private int getJenkinsPort(Map<String, HostPortMapping> ports) {
        return Integer.parseInt(ports.get("8080").getHostPort());
    }

    /**
     * Gets public host port of SSH
     * 
     * @param ports Port mappings of container
     * @return Public host port of SSH
     */
    private int getSshPort(Map<String, HostPortMapping> ports) {
        return Integer.parseInt(ports.get("22").getHostPort());
    }

    /**
     * Get container port mapping from Docker daemon REST interface.
     * 
     * @param containerId ID of running container
     * @return Map with pairs containerPort:publicPort
     * @throws Exception Thrown if data could not be obtained from Docker daemon or are corrupted
     */
    private Map<String, HostPortMapping> getContainerPortMappings(String containerId) throws Exception {
        Map<String, HostPortMapping> resultMap = new HashMap<>();
        String response =
                processGetRequest(String.class,
                        dockerEndpoint + "/containers/" + containerId + "/json");

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode rootNode = objectMapper.readTree(response);
        JsonNode networkSettingsNode = rootNode.path("NetworkSettings");
        JsonNode portsNode = networkSettingsNode.path("Ports");

        Map<String, List<HostPortMapping>> portsMap = objectMapper.readValue(portsNode.traverse(),
                new TypeReference<Map<String, List<HostPortMapping>>>() {
                });

        for (Map.Entry<String, List<HostPortMapping>> entry : portsMap.entrySet()) {
            resultMap.put(entry.getKey().substring(0, entry.getKey().indexOf("/")),
                    entry.getValue().get(0));
        }

        return resultMap;
    }

    /**
     * Process HTTP GET request and get the data as String.
     * Client accepts application/json MIME type.
     * 
     * @param url Request URL
     * @throws Exception Thrown if some error occurs in communication with server
     */
    private <T> T processGetRequest(Class<T> clazz, String url) throws Exception {
        ClientRequest request = new ClientRequest(url);
        request.accept(MediaType.APPLICATION_JSON);

        ClientResponse<T> response = request.get(clazz);
        return response.getEntity();
    }

}
