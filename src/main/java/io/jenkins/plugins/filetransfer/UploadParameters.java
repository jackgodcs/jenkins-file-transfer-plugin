package io.jenkins.plugins.filetransfer;

import hudson.model.ParametersAction;
import hudson.model.Run;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class UploadParameters {
    private UploadParameters() {}

    static FileOrFolderParameterValue find(Run<?, ?> run, String name) throws IOException {
        validateAndBind(run);
        ParametersAction action = run.getAction(ParametersAction.class);
        if (action == null || !(action.getParameter(name) instanceof FileOrFolderParameterValue))
            throw new IOException("Upload parameter was not found: " + name);
        return (FileOrFolderParameterValue) action.getParameter(name);
    }

    static synchronized void validateAndBind(Run<?, ?> run) throws IOException {
        ParametersAction action = run.getAction(ParametersAction.class);
        if (action == null) return;
        List<FileOrFolderParameterValue> uploads = new ArrayList<>();
        long bytes = 0;
        for (hudson.model.ParameterValue parameter : action.getAllParameters())
            if (parameter instanceof FileOrFolderParameterValue) {
                FileOrFolderParameterValue upload = (FileOrFolderParameterValue) parameter;
                if (upload.isPresent()) {
                    uploads.add(upload);
                    bytes = Math.addExact(bytes, upload.getBytes());
                }
            }
        if (bytes > FileTransferGlobalConfiguration.get().getDefaultBuildMaxBytes())
            throw new IOException("Combined uploads exceed the configured build limit");
        List<String> sessionIds = new ArrayList<>();
        for (FileOrFolderParameterValue upload : uploads) sessionIds.add(upload.getSessionId());
        UploadStore.get().bindAll(sessionIds, run.getExternalizableId());
    }
}
