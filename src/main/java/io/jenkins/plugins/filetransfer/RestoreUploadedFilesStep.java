package io.jenkins.plugins.filetransfer;

import hudson.Extension;
import hudson.FilePath;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.IOException;
import java.util.Set;
import java.util.logging.Logger;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;

public class RestoreUploadedFilesStep extends Step {
    private static final Logger LOGGER = Logger.getLogger(RestoreUploadedFilesStep.class.getName());
    private final String parameter;
    private final String target;

    @DataBoundConstructor
    public RestoreUploadedFilesStep(String parameter, String target) {
        this.parameter = parameter;
        this.target = target;
    }

    public String getParameter() {
        return parameter;
    }

    public String getTarget() {
        return target;
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(context, parameter, target);
    }

    private static final class Execution extends SynchronousNonBlockingStepExecution<Void> {
        private static final long serialVersionUID = 1L;
        private final String parameter;
        private final String target;

        Execution(StepContext context, String parameter, String target) {
            super(context);
            this.parameter = parameter;
            this.target = target;
        }

        @Override
        protected Void run() throws Exception {
            Run<?, ?> run = getContext().get(Run.class);
            FilePath workspace = getContext().get(FilePath.class);
            TaskListener listener = getContext().get(TaskListener.class);
            if (run == null || workspace == null)
                throw new IOException("restoreUploadedFiles requires a node workspace");
            String safeTarget = PathRules.normalizeRelative(
                    target, FileTransferGlobalConfiguration.get().getMaxDepth());
            FilePath destination = workspace.child(safeTarget);
            if (destination.exists() && !destination.list().isEmpty())
                throw new IOException("Restore target is not empty: " + safeTarget);
            FileOrFolderParameterValue value = UploadParameters.find(run, parameter);
            if (!value.isPresent()) throw new IOException("Optional upload parameter is empty: " + parameter);
            UploadSession session = UploadStore.get().loadBound(value.getSessionId(), run.getExternalizableId());
            FilePath temporary = workspace.child(safeTarget + ".file-transfer-" + session.getId());
            temporary.deleteRecursive();
            temporary.mkdirs();
            try {
                new FilePath(UploadStore.get().files(session.getId()).toFile()).copyRecursiveTo(temporary);
                if (destination.exists()) destination.deleteRecursive();
                temporary.renameTo(destination);
            } catch (Exception exception) {
                temporary.deleteRecursive();
                throw exception;
            }
            listener.getLogger().println("Restored upload " + session.getTopLevelName() + " to " + safeTarget);
            LOGGER.info("upload restored user=" + session.getUserId() + " job=" + session.getJobFullName() + " build="
                    + run.getExternalizableId() + " parameter=" + session.getParameterName() + " type="
                    + (session.isFolder() ? "folder" : "file") + " name=" + session.getTopLevelName() + " files="
                    + session.getDeclaredFiles() + " bytes=" + session.getDeclaredBytes());
            return null;
        }
    }

    @Extension
    @Symbol("restoreUploadedFiles")
    public static class DescriptorImpl extends StepDescriptor {
        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Run.class, FilePath.class, TaskListener.class);
        }

        @Override
        public String getFunctionName() {
            return "restoreUploadedFiles";
        }

        @Override
        public String getDisplayName() {
            return Messages.RestoreUploadedFilesStep_DisplayName();
        }
    }
}
