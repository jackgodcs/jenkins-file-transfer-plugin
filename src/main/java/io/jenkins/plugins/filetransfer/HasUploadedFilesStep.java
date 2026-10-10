package io.jenkins.plugins.filetransfer;

import hudson.Extension;
import hudson.model.Run;
import java.util.Collections;
import java.util.Set;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;

public class HasUploadedFilesStep extends Step {
    private final String parameter;

    @DataBoundConstructor
    public HasUploadedFilesStep(String parameter) {
        this.parameter = parameter;
    }

    public String getParameter() {
        return parameter;
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(context, parameter);
    }

    private static final class Execution extends SynchronousNonBlockingStepExecution<Boolean> {
        private static final long serialVersionUID = 1L;
        private final String parameter;

        Execution(StepContext context, String parameter) {
            super(context);
            this.parameter = parameter;
        }

        @Override
        protected Boolean run() throws Exception {
            return UploadParameters.find(getContext().get(Run.class), parameter).isPresent();
        }
    }

    @Extension
    @Symbol("hasUploadedFiles")
    public static class DescriptorImpl extends StepDescriptor {
        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Collections.singleton(Run.class);
        }

        @Override
        public String getFunctionName() {
            return "hasUploadedFiles";
        }

        @Override
        public String getDisplayName() {
            return Messages.HasUploadedFilesStep_DisplayName();
        }
    }
}
