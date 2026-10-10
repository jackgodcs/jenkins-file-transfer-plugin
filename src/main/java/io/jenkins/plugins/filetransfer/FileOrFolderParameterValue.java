package io.jenkins.plugins.filetransfer;

import hudson.EnvVars;
import hudson.model.ParameterValue;
import hudson.model.Run;
import java.io.IOException;
import org.kohsuke.stapler.DataBoundConstructor;

public class FileOrFolderParameterValue extends ParameterValue {
    private static final long serialVersionUID = 1L;
    private final String sessionId;
    private final boolean folder;
    private final String topLevelName;
    private final long bytes;
    private final int files;
    private final int successRetentionDays;
    private final int failureRetentionDays;

    @DataBoundConstructor
    public FileOrFolderParameterValue(
            String name,
            String sessionId,
            boolean folder,
            String topLevelName,
            long bytes,
            int files,
            int successRetentionDays,
            int failureRetentionDays) {
        super(name);
        this.sessionId = sessionId;
        this.folder = folder;
        this.topLevelName = topLevelName;
        this.bytes = bytes;
        this.files = files;
        this.successRetentionDays = successRetentionDays;
        this.failureRetentionDays = failureRetentionDays;
    }

    public String getSessionId() {
        return sessionId;
    }

    public boolean isFolder() {
        return folder;
    }

    public String getTopLevelName() {
        return topLevelName;
    }

    public long getBytes() {
        return bytes;
    }

    public int getFiles() {
        return files;
    }

    public int getSuccessRetentionDays() {
        return successRetentionDays;
    }

    public int getFailureRetentionDays() {
        return failureRetentionDays;
    }

    public boolean isPresent() {
        return sessionId != null;
    }

    @Override
    public void buildEnvironment(Run<?, ?> build, EnvVars env) {
        if (sessionId == null) return;
        try {
            UploadParameters.validateAndBind(build);
            env.put(name + "_UPLOAD_NAME", topLevelName);
            env.put(name + "_UPLOAD_TYPE", folder ? "folder" : "file");
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to bind upload to build", exception);
        }
    }

    @Override
    public String getShortDescription() {
        return sessionId == null
                ? name + "=(empty)"
                : name + "=" + topLevelName + " (" + files + " files, " + bytes + " bytes)";
    }
}
