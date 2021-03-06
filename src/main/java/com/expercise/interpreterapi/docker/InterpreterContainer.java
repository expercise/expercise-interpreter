package com.expercise.interpreterapi.docker;

import com.expercise.interpreterapi.exception.InterpreterException;
import com.spotify.docker.client.DefaultDockerClient;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.exceptions.DockerCertificateException;
import com.spotify.docker.client.exceptions.DockerException;
import com.spotify.docker.client.messages.ContainerConfig;
import com.spotify.docker.client.messages.ContainerCreation;
import com.spotify.docker.client.messages.ContainerInfo;
import com.spotify.docker.client.messages.HostConfig;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

import static org.apache.commons.lang.BooleanUtils.isTrue;

public abstract class InterpreterContainer {

    private static final Logger LOGGER = LoggerFactory.getLogger(InterpreterContainer.class);

    private static final Long MEMORY_CONSTRAINT = 32 * 1024 * 1024L;

    protected static final int STDOUT_BYTE_LIMIT = 1024;

    protected static final int STDERR_BYTE_LIMIT = 1024;

    protected final DockerClient docker;

    public InterpreterContainer() throws DockerCertificateException, DockerException, InterruptedException {
        docker = DefaultDockerClient.fromEnv().build();
        docker.pull(getDockerImage().imageName());
    }

    public Response run(Path hostPath, Path containerPath) {
        HostConfig hostConfig = HostConfig.builder()
                .appendBinds(HostConfig.Bind
                        .from(hostPath.toString())
                        .to(containerPath.toString())
                        .readOnly(true)
                        .build())
                .memory(MEMORY_CONSTRAINT)
                .build();

        ContainerConfig containerConfig = ContainerConfig.builder()
                .image(getDockerImage().imageName())
                .hostConfig(hostConfig)
                .openStdin(true)
                .networkDisabled(true)
                .workingDir(containerPath.toString())
                .build();

        ContainerCreation containerCreation = startContainer(containerConfig);
        try {
            return execute(containerCreation.id());
        } catch (DockerException | InterruptedException | IOException e) {
            throw new InterpreterException("Interpreter exception occurred", e);
        } finally {
            destroyContainer(containerCreation.id());
        }
    }

    protected abstract DockerImage getDockerImage();

    protected abstract Response execute(String containerId) throws DockerException, InterruptedException, IOException;

    private ContainerCreation startContainer(ContainerConfig containerConfig) {
        try {
            ContainerCreation cc = docker.createContainer(containerConfig);
            docker.startContainer(cc.id());
            if (CollectionUtils.isNotEmpty(cc.warnings())) {
                cc.warnings().forEach(LOGGER::warn);
            }
            return cc;
        } catch (DockerException | InterruptedException e) {
            throw new InterpreterException("Interpreter exception occurred while container starting.", e);
        }
    }

    private void checkExecutionState(String containerId) {
        try {
            ContainerInfo containerInfo = docker.inspectContainer(containerId);
            if (containerInfo.state() != null) {
                if (isTrue(containerInfo.state().oomKilled())) {
                    throw new InterpreterException("Container memory limit exceeded.");
                } else {
                    LOGGER.info("Interpreter execution completed successfully on container with ID : {} ; containerInfo : {}", containerId, containerInfo);
                }
            } else {
                throw new InterpreterException("Code execution failed with unknown state.");
            }
        } catch (DockerException | InterruptedException e) {
            throw new InterpreterException("Error occurred while checking after execution state.");
        }
    }

    private void destroyContainer(String containerId) {
        try {
            killContainer(containerId);
            checkExecutionState(containerId);
            removeContainer(containerId);
        } catch (DockerException | InterruptedException e) {
            throw new InterpreterException("Interpreter exception occurred while destroying container.", e);
        }
    }

    private void killContainer(String containerId) throws DockerException, InterruptedException {
        LOGGER.debug("Killing container. ContainerId : {}", containerId);
        docker.killContainer(containerId);
        LOGGER.debug("Container killed. ContainerId : {}", containerId);
    }

    private void removeContainer(String containerId) throws DockerException, InterruptedException {
        LOGGER.debug("Removing container. ContainerId : {}", containerId);
        docker.removeContainer(containerId);
        LOGGER.debug("Container removed. ContainerId : {}", containerId);
    }

    public static class Response {

        private final String stdOut;
        private final String stdErr;

        public Response(String stdOut, String stdErr) {
            this.stdOut = StringUtils.trim(stdOut);
            this.stdErr = StringUtils.trim(stdErr);
        }

        public String getStdOut() {
            return stdOut;
        }

        public String getStdErr() {
            return stdErr;
        }
    }
}
