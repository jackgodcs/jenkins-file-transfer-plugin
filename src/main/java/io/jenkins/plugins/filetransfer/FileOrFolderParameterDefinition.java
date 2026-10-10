package io.jenkins.plugins.filetransfer;

import hudson.Extension;
import hudson.Util;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.servlet.ServletException;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest;

public class FileOrFolderParameterDefinition extends ParameterDefinition {
    private static final long serialVersionUID = 1L;
    private boolean required = true;
    private long maxBytes;
    private int maxFiles;
    private List<String> allowedExtensions = Collections.emptyList();
    private int successRetentionDays = -1;
    private int failureRetentionDays = -1;

    @DataBoundConstructor
    public FileOrFolderParameterDefinition(String name) {
        super(name);
    }

    public boolean isRequired() {
        return required;
    }

    @DataBoundSetter
    public void setRequired(boolean required) {
        this.required = required;
    }

    public long getMaxBytes() {
        return maxBytes > 0 ? maxBytes : FileTransferGlobalConfiguration.get().getDefaultMaxBytes();
    }

    @DataBoundSetter
    public void setMaxBytes(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public int getMaxFiles() {
        return maxFiles > 0 ? maxFiles : FileTransferGlobalConfiguration.get().getDefaultMaxFiles();
    }

    @DataBoundSetter
    public void setMaxFiles(int maxFiles) {
        this.maxFiles = maxFiles;
    }

    public List<String> getAllowedExtensions() {
        return allowedExtensions;
    }

    @DataBoundSetter
    public void setAllowedExtensions(List<String> values) {
        List<String> normalized = new ArrayList<>();
        if (values != null)
            for (String value : values) {
                String extension = normalizeExtension(value);
                if (extension != null) normalized.add(extension);
            }
        allowedExtensions = Collections.unmodifiableList(normalized);
    }

    public String getAllowedExtensionsText() {
        return String.join(",", allowedExtensions);
    }

    @DataBoundSetter
    public void setAllowedExtensionsText(String value) {
        setAllowedExtensions(value == null ? Collections.emptyList() : java.util.Arrays.asList(value.split(",")));
    }

    public int getSuccessRetentionDays() {
        return successRetentionDays;
    }

    @DataBoundSetter
    public void setSuccessRetentionDays(int value) {
        successRetentionDays = value;
    }

    public int getFailureRetentionDays() {
        return failureRetentionDays;
    }

    @DataBoundSetter
    public void setFailureRetentionDays(int value) {
        failureRetentionDays = value;
    }

    @Override
    public ParameterValue createValue(StaplerRequest request, JSONObject json) {
        String token = Util.fixEmptyAndTrim(json.optString("value", null));
        if (token == null) {
            if (required)
                throw new IllegalArgumentException("A completed upload is required for parameter " + getName());
            return new FileOrFolderParameterValue(
                    getName(), null, false, null, 0, 0, successRetentionDays, failureRetentionDays);
        }
        try {
            UploadSession session = UploadStore.get()
                    .reserve(
                            token,
                            request.findAncestorObject(hudson.model.Job.class).getFullName(),
                            getName());
            return new FileOrFolderParameterValue(
                    getName(),
                    session.getId(),
                    session.isFolder(),
                    session.getTopLevelName(),
                    session.getDeclaredBytes(),
                    session.getDeclaredFiles(),
                    successRetentionDays,
                    failureRetentionDays);
        } catch (IOException exception) {
            throw new IllegalArgumentException(exception.getMessage(), exception);
        }
    }

    @Override
    public ParameterValue createValue(StaplerRequest request) throws IllegalArgumentException {
        JSONObject submitted;
        try {
            submitted = request.getSubmittedForm();
        } catch (ServletException exception) {
            throw new IllegalArgumentException("Invalid upload parameter form", exception);
        }
        return createValue(request, submitted);
    }

    boolean allows(String relativePath) {
        String extension = extension(relativePath);
        if (!allowedExtensions.isEmpty() && !allowedExtensions.contains(extension)) return false;
        String denied = FileTransferGlobalConfiguration.get().getDeniedExtensions();
        if (denied != null)
            for (String value : denied.split(",")) if (extension.equals(normalizeExtension(value))) return false;
        return true;
    }

    private static String extension(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        int dot = path.lastIndexOf('.');
        return dot > slash ? path.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "";
    }

    private static String normalizeExtension(String value) {
        String extension = Util.fixEmptyAndTrim(value);
        if (extension == null) return null;
        while (extension.startsWith(".")) extension = extension.substring(1);
        return extension.toLowerCase(java.util.Locale.ROOT);
    }

    @Extension
    @Symbol("fileOrFolderUpload")
    public static class DescriptorImpl extends ParameterDefinition.ParameterDescriptor {
        @Override
        public String getDisplayName() {
            return Messages.FileOrFolderParameterDefinition_DisplayName();
        }

        @Override
        public String getHelpFile() {
            return null;
        }
    }
}
