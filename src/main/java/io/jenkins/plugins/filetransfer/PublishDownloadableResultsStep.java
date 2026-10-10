package io.jenkins.plugins.filetransfer;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import jenkins.model.ArtifactManager;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

public class PublishDownloadableResultsStep extends Step {
    private final String path;
    private final String name;
    private boolean allowMissing;

    @DataBoundConstructor
    public PublishDownloadableResultsStep(String path, String name) {
        this.path = path;
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public String getName() {
        return name;
    }

    public boolean isAllowMissing() {
        return allowMissing;
    }

    @DataBoundSetter
    public void setAllowMissing(boolean allowMissing) {
        this.allowMissing = allowMissing;
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(context, path, name, allowMissing);
    }

    private static final class Execution extends SynchronousNonBlockingStepExecution<Void> {
        private static final long serialVersionUID = 1L;
        private final String path;
        private final String name;
        private final boolean allowMissing;

        Execution(StepContext context, String path, String name, boolean allowMissing) {
            super(context);
            this.path = path;
            this.name = name;
            this.allowMissing = allowMissing;
        }

        @Override
        protected Void run() throws Exception {
            Run<?, ?> run = getContext().get(Run.class);
            FilePath workspace = getContext().get(FilePath.class);
            Launcher launcher = getContext().get(Launcher.class);
            TaskListener listener = getContext().get(TaskListener.class);
            if (run == null || workspace == null || launcher == null || listener == null)
                throw new IOException("publishDownloadableResults requires a node workspace");
            String safe = PathRules.normalizeRelative(
                    path, FileTransferGlobalConfiguration.get().getMaxDepth());
            FilePath source = workspace.child(safe);
            if (!source.exists()) {
                if (allowMissing) return null;
                throw new IOException("Result path does not exist: " + safe);
            }
            boolean directory = source.isDirectory();
            String id = UUID.randomUUID().toString();
            String prefix = "file-transfer-results/" + id + "/";
            Map<String, String> artifacts = new LinkedHashMap<>();
            List<String> files = new ArrayList<>();
            if (directory) {
                for (FilePath file : source.list("**/*"))
                    if (!file.isDirectory()) {
                        String relative = file.getRemote()
                                .substring(source.getRemote().length() + 1)
                                .replace('\\', '/');
                        artifacts.put(
                                prefix + relative,
                                file.getRemote()
                                        .substring(workspace.getRemote().length() + 1)
                                        .replace('\\', '/'));
                        files.add(relative);
                    }
            } else {
                artifacts.put(prefix + source.getName(), safe);
                files.add(source.getName());
            }
            if (artifacts.isEmpty() && !allowMissing) throw new IOException("Result path contains no files: " + safe);
            if (artifacts.isEmpty()) return null;
            ArtifactManager manager = run.pickArtifactManager();
            BuildListener buildListener =
                    listener instanceof BuildListener ? (BuildListener) listener : listener::getLogger;
            manager.archive(workspace, launcher, buildListener, artifacts);
            DownloadableResultsAction action = run.getAction(DownloadableResultsAction.class);
            if (action == null) {
                action = new DownloadableResultsAction();
                run.addAction(action);
            }
            action.add(
                    new DownloadableResult(id, name, directory ? prefix : prefix + source.getName(), directory, files));
            run.save();
            listener.getLogger().println("Published downloadable result: " + name);
            return null;
        }
    }

    @Extension
    @Symbol("publishDownloadableResults")
    public static class DescriptorImpl extends StepDescriptor {
        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Run.class, FilePath.class, Launcher.class, TaskListener.class);
        }

        @Override
        public String getFunctionName() {
            return "publishDownloadableResults";
        }

        @Override
        public String getDisplayName() {
            return Messages.PublishDownloadableResultsStep_DisplayName();
        }
    }
}
