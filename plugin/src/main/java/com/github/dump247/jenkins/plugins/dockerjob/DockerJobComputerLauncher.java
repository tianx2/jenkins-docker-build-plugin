package com.github.dump247.jenkins.plugins.dockerjob;

import com.github.dump247.jenkins.plugins.dockerjob.slaves.SlaveClient;
import com.github.dump247.jenkins.plugins.dockerjob.slaves.SlaveOptions;
import com.github.dump247.jenkins.plugins.dockerjob.util.JenkinsUtils;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import hudson.Extension;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;
import jenkins.model.Jenkins;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.PrintStream;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.FINER;
import static java.util.logging.Level.WARNING;

public class DockerJobComputerLauncher extends ComputerLauncher {
    private static final Logger LOG = Logger.getLogger(DockerJobComputerLauncher.class.getName());

    private final String _cloudName;
    private final SlaveOptions _options;

    public DockerJobComputerLauncher(String cloudName, SlaveOptions options) {
        _cloudName = cloudName;
        _options = options;
    }

    public String getCloudName() {
        return _cloudName;
    }

    @Override
    public void launch(SlaveComputer computer, final TaskListener listener) throws IOException, InterruptedException {
        LOG.log(FINE, "Starting slave for {0}", _options.getName());
        Optional<DockerJobCloud> cloud = JenkinsUtils.getCloud(Jenkins.getInstance(), DockerJobCloud.class, _cloudName);

        if (!cloud.isPresent()) {
            throw new RuntimeException("Unable to find cloud to launch slave: " + _cloudName);
        }

        final SlaveClient.SlaveConnection connection = cloud.get().createSlave(_options);

        final Thread logReader = new Thread(new Runnable() {
            @Override
            public void run() {
                BufferedReader reader = null;

                try {
                    reader = new BufferedReader(new InputStreamReader(connection.getLog(), Charsets.UTF_8));
                    PrintStream logger = listener.getLogger();
                    String line;

                    while ((line = reader.readLine()) != null) {
                        logger.println(line);
                    }
                } catch (InterruptedIOException ex) {
                    LOG.log(FINER, "Log stream read thread cancelled for job " + _options.getName());
                } catch (Throwable ex) {
                    LOG.log(WARNING, "Error reading log stream for job " + _options.getName(), ex);
                } finally {
                    if (reader != null) {
                        try {
                            reader.close();
                        } catch (IOException ex) {
                            LOG.log(FINER, "Error closing log stream for job " + _options.getName(), ex);
                        }
                    }
                }
            }
        });
        logReader.setDaemon(true);
        logReader.setName(_options.getName().replaceAll("[^a-zA-Z0-9_-]", "_") + "-log-reader");
        logReader.start();

        try {
            computer.setChannel(connection.getOutput(), connection.getInput(), listener, new Channel.Listener() {
                @Override
                public void onClosed(Channel channel, IOException cause) {
                    LOG.log(FINE, "Channel closed for {0}", _options.getName());

                    connection.close();

                    try {
                        logReader.interrupt();
                        logReader.join(5000);
                    } catch (Throwable ex) {
                        LOG.log(FINER, "Error stopping log reading thread for job " + _options.getName(), ex);
                    }
                }
            });
        } catch (IOException ex) {
            connection.close();
            throw ex;
        } catch (Throwable ex) {
            connection.close();
            throw Throwables.propagate(ex);
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
