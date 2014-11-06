package com.nirima.jenkins.plugins.docker.builder;

import com.google.common.base.Optional;
import com.google.common.base.Throwables;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.DockerException;

import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.DockerClientConfig;
import com.nirima.jenkins.plugins.docker.DockerSlave;
import com.nirima.jenkins.plugins.docker.action.DockerBuildImageAction;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Node;
import hudson.remoting.VirtualChannel;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;

/**
 * Builder extension to build / publish an image from a Dockerfile.
 */
public class DockerBuilderPublisher extends Builder implements Serializable {

    public final String dockerFileDirectory;
    public final String tag;
    public final boolean pushOnSuccess;
    public final boolean cleanImages;
    public final boolean cleanupWithJenkinsJobDelete;

    @DataBoundConstructor
    public DockerBuilderPublisher(String dockerFileDirectory, String tag, boolean pushOnSuccess, boolean cleanImages, boolean cleanupWithJenkinsJobDelete) {
        this.dockerFileDirectory = dockerFileDirectory;
        this.tag = tag;
        this.pushOnSuccess = pushOnSuccess;
        this.cleanImages = cleanImages;
        this.cleanupWithJenkinsJobDelete = cleanupWithJenkinsJobDelete;
    }

    @Override
    public boolean perform(final AbstractBuild build, final Launcher launcher, final BuildListener listener) throws IOException, InterruptedException {

        listener.getLogger().println("Docker Build");

        FilePath fpChild = new FilePath(build.getWorkspace(), dockerFileDirectory);

        final String tagToUse = getTag(build, launcher, listener);
        final String url = getUrl(build);
        // Marshal the builder across the wire.
        final DockerClientConfig clientConfig = getDockerClientConfig(build);



        String response = fpChild.act(new FilePath.FileCallable<String>() {
            public String invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
                try {
                    listener.getLogger().println("Docker Build : build with tag " + tagToUse + " at path " + f.getAbsolutePath());


                    //TODO:
                    //DockerClient client = builder
                    //    .readTimeout(3600000).build();

                    DockerClient client = DockerClientBuilder.getInstance(clientConfig)
                        .build();




                    File dockerFile;

                    // Be lenient and allow the user to just specify the path.
                    if( f.isFile() )
                        dockerFile = f;
                    else
                        dockerFile = new File(f, "Dockerfile");

                    InputStream is = client.buildImageCmd(dockerFile)
                            .withTag(tagToUse)
                            .exec();

                    return IOUtils.toString(is);

                } catch (DockerException e) {
                    throw Throwables.propagate(e);
                }

            }
        });


        listener.getLogger().println("Docker Build Response : " + response);

        Optional<String> id = getImageId(response);
        if( !id.isPresent() )
           return false;

        build.addAction( new DockerBuildImageAction(url, id.get(), tagToUse, cleanupWithJenkinsJobDelete, pushOnSuccess) );
        build.save();

        DockerClient client = getDockerClient(build);

        if( pushOnSuccess ) {

            listener.getLogger().println("Pushing " + tagToUse);
            if( !tagToUse.toLowerCase().equals(tagToUse) ) {
                listener.getLogger().println("ERROR: Docker will refuse to push tag name " + tagToUse + " because it uses upper case.");
            }

//            Identifier identifier = Identifier.fromCompoundString(tagToUse);
//
//            String repositoryName = identifier.repository.name;

            InputStream pushResponse = client.pushImageCmd(tagToUse)
                    .exec();

            String stringResponse = IOUtils.toString(pushResponse);

            listener.getLogger().println("Docker Push Response : " + stringResponse);
        }

        if (cleanImages) {

            // For some reason, docker delete doesn't delete all tagged
            // versions, despite force = true.
            // So, do it multiple times (protect against infinite looping).
            listener.getLogger().println("Cleaning local images");

            client.removeImageCmd(id.get())
                        .withForce()
                        .exec();
        }



        listener.getLogger().println("Docker Build Done");

        return true;
    }

    private Optional<String> getImageId(String response) {
        for(String item : response.split("\n") ) {
            if (item.contains("Successfully built")) {
                String id =  StringUtils.substringAfterLast(item, "Successfully built ").trim();
                return Optional.of(id);
            }
        }

        return Optional.absent();
    }


    private DockerClient getDockerClient(AbstractBuild build) {

        Node node = build.getBuiltOn();
        if( node instanceof DockerSlave ) {
            DockerSlave slave = (DockerSlave)node;
            return slave.getCloud().connect();
        }

        return null;
    }

    private DockerClientConfig getDockerClientConfig(AbstractBuild build) {
        Node node = build.getBuiltOn();
        if( node instanceof DockerSlave ) {
            DockerSlave slave = (DockerSlave)node;
            return slave.getCloud().getDockerClientConfig();
        }

        return null;
    }

    private String getUrl(AbstractBuild build) {
        Node node = build.getBuiltOn();
        if( node instanceof DockerSlave ) {
            DockerSlave slave = (DockerSlave)node;
            return slave.getCloud().serverUrl;
        }


        return null;
    }

    private String getTag(AbstractBuild build, Launcher launcher, BuildListener listener) {
        try {
            return TokenMacro.expandAll(build, listener, tag);
        }catch(Exception ex) {
            listener.getLogger().println("Couldn't macro expand tag " + tag);
        }
        return tag;

    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Build / Publish Docker Containers";
        }
    }
}