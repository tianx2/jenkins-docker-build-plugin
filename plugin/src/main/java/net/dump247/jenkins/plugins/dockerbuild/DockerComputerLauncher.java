package net.dump247.jenkins.plugins.dockerbuild;

import hudson.Extension;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;
import hudson.util.StreamCopyThread;
import net.dump247.docker.ContainerNotFoundException;
import net.dump247.docker.CreateContainerRequest;
import net.dump247.docker.CreateContainerResponse;
import net.dump247.docker.DockerClient;
import net.dump247.docker.DockerException;
import net.dump247.docker.ProgressEvent;
import net.dump247.docker.ProgressListener;
import net.dump247.docker.StartContainerRequest;
import org.apache.commons.lang.mutable.MutableInt;

import java.io.IOException;
import java.io.PrintStream;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.lang.String.format;

public class DockerComputerLauncher extends ComputerLauncher {
    private static final Logger LOG = Logger.getLogger(DockerComputerLauncher.class.getName());

    private final DockerClient _dockerClient;
    private final String _imageName;

    public DockerComputerLauncher(DockerClient dockerClient, String imageName) {
        _dockerClient = dockerClient;
        _imageName = imageName;
    }

    public DockerClient getDockerClient() {
        return _dockerClient;
    }

    @Override
    public void launch(final SlaveComputer computer, final TaskListener listener) throws IOException, InterruptedException {
        pullImage(listener);
        listener.getLogger().println(format("### Running job with Docker image %s", _imageName));
        final String containerId = createContainer(listener);

        LOG.info(format("Attaching container streams: [containerId=%s]", containerId));
        final DockerClient.ContainerStreams streams = _dockerClient.attachContainerStreams(containerId);

        LOG.info(format("Starting container: [containerId=%s]", containerId));
        _dockerClient.startContainer(new StartContainerRequest()
                .withContainerId(containerId)
                .withBinding("/usr/lib/jenkins/slave", "/usr/lib/jenkins/slave")
                .withBinding("/usr/lib/jvm/java-7-openjdk-amd64/jre", "/usr/lib/jenkins/jre"));

        final StreamCopyThread stderrThread = new StreamCopyThread(containerId + " stderr", streams.stderr, listener.getLogger());
        stderrThread.start();

        computer.setChannel(streams.stdout, streams.stdin, listener, new Channel.Listener() {
            @Override
            public void onClosed(final Channel channel, final IOException cause) {
                try {
                    LOG.info(format("Closing container stdin: [containerId=%s]", containerId));
                    streams.stdin.close();
                } catch (Throwable ex) {
                    LOG.log(Level.INFO, format("Error closing container stdin: [containerId=%s]", containerId), ex);
                }

                try {
                    LOG.info(format("Closing container stdout: [containerId=%s]", containerId));
                    streams.stdout.close();
                } catch (Throwable ex) {
                    LOG.log(Level.INFO, format("Error closing container stdout: [containerId=%s]", containerId), ex);
                }

                try {
                    LOG.info(format("Closing container stderr: [containerId=%s]", containerId));
                    stderrThread.join(2000);
                    stderrThread.interrupt();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }

                try {
                    LOG.info(format("Stopping container: [containerId=%s]", containerId));
                    _dockerClient.stopContainer(containerId);

                    LOG.info(format("Removing container: [containerId=%s]", containerId));
                    _dockerClient.removeContainer(containerId);
                } catch (ContainerNotFoundException ex) {
                    LOG.log(Level.FINE, format("Container not found: [containerId=%s]", containerId), ex);
                } catch (DockerException ex) {
                    ex.printStackTrace(listener.error("Error removing removing container: [containerId=%s]", containerId));
                }
            }
        });
    }

    private String createContainer(final TaskListener listener) throws IOException {
        LOG.info(format("Creating container: [image=%s] [endpoint=%s]", _imageName, _dockerClient));

        CreateContainerResponse response = _dockerClient.createContainer(new CreateContainerRequest()
                .withImage(_imageName)
                .withAttachStderr(true)
                .withAttachStdin(true)
                .withAttachStdout(true)
                .withStdinOnce(true)
                .withOpenStdin(true)
                .withTty(false)
                .withVolumes("/usr/lib/jenkins/slave", "/usr/lib/jenkins/jre")
                .withCommand("/usr/lib/jenkins/jre/bin/java", "-jar", "/usr/lib/jenkins/slave/slave.jar"));

        for (String warning : response.getWarnings()) {
            LOG.warning(warning);
            listener.getLogger().println(format("WARN: %s", warning));
        }

        return response.getContainerId();
    }

    private void pullImage(final TaskListener listener) throws DockerException {
        final PrintStream logger = listener.getLogger();
        final MutableInt counter = new MutableInt();

        LOG.info(format("Pulling image: [image=%s] [endpoint=%s]", _imageName, _dockerClient));

        _dockerClient.pullImage(_imageName, new ProgressListener() {
            public void progress(final ProgressEvent event) {
                // Only print out progress or error messages
                if (event.getCode() == ProgressEvent.Code.Ok && event.getTotal() == 0) {
                    return;
                }

                if (counter.intValue() == 0) {
                    LOG.info(format("Pulling docker image %s", _imageName));
                    logger.println(format("### Pulling Docker image %s", _imageName));
                }

                counter.increment();

                String statusMessage = event.getStatusMessage();
                String detailMessage = event.getDetailMessage();

                StringBuilder message = new StringBuilder(statusMessage.length() + 2 + detailMessage.length());
                message.append(statusMessage);

                if (detailMessage.length() > 0) {
                    message.append(": ").append(detailMessage);
                }

                if (event.getCode() == ProgressEvent.Code.Ok) {
                    logger.println(message);
                } else {
                    listener.error(message.toString());
                }
            }
        });

        if (counter.intValue() > 0) {
            logger.println(format("### Done pulling Docker image %s", _imageName));
        }
    }

    @Extension
    public static class Descriptor extends hudson.model.Descriptor<ComputerLauncher> {
        @Override
        public String getDisplayName() {
            return "Docker Computer Launcher";
        }
    }
}
