package com.expercise.interpreterapi.docker;

import com.expercise.interpreterapi.exception.InterpreterException;
import com.expercise.interpreterapi.request.InterpretResponse;
import com.spotify.docker.client.DefaultDockerClient;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.exceptions.DockerCertificateException;
import com.spotify.docker.client.exceptions.DockerException;
import com.spotify.docker.client.messages.ContainerConfig;
import com.spotify.docker.client.messages.ContainerCreation;
import com.spotify.docker.client.messages.HostConfig;

import java.io.IOException;
import java.nio.file.Path;

public abstract class DockerService {

    protected static final int STDOUT_BYTE_LIMIT = 1024;
    protected static final int STDERR_BYTE_LIMIT = 1024;

    private static final Long MEMORY_CONSTRAINT = 4 * 1024 * 1024L;

    protected final DockerClient docker;

    public DockerService() throws DockerCertificateException, DockerException, InterruptedException {
        docker = DefaultDockerClient.fromEnv().build();
        docker.pull(getDockerImage().imageName());
    }

    public InterpretResponse runContainer(Path hostPath, Path containerPath) {
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

    protected abstract InterpretResponse execute(String containerId) throws DockerException, InterruptedException, IOException;

    private ContainerCreation startContainer(ContainerConfig containerConfig) {
        try {
            ContainerCreation cc = docker.createContainer(containerConfig);
            docker.startContainer(cc.id());
            return cc;
        } catch (DockerException | InterruptedException e) {
            throw new InterpreterException("Interpreter exception occurred while container starting.", e);
        }
    }

    private void destroyContainer(String containerId) {
        try {
            docker.killContainer(containerId);
            docker.removeContainer(containerId);
        } catch (DockerException | InterruptedException e) {
            throw new InterpreterException("Interpreter exception occurred while destroying container.", e);
        }
    }
}
