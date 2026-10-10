package io.jenkins.plugins.filetransfer;

import hudson.Extension;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

@Extension
public class UploadLifecycleListener extends RunListener<Run<?, ?>> {
    private static final Logger LOGGER = Logger.getLogger(UploadLifecycleListener.class.getName());

    @Override
    public void onCompleted(Run<?, ?> run, TaskListener listener) {
        forEach(run, value -> {
            if (!UploadStore.get().isBoundTo(value.getSessionId(), run.getExternalizableId())) return;
            FileTransferGlobalConfiguration config = FileTransferGlobalConfiguration.get();
            int configured = Result.SUCCESS.equals(run.getResult())
                    ? value.getSuccessRetentionDays()
                    : value.getFailureRetentionDays();
            int fallback = Result.SUCCESS.equals(run.getResult())
                    ? config.getSuccessRetentionDays()
                    : config.getFailureRetentionDays();
            int days = Math.min(config.getMaximumRetentionDays(), configured < 0 ? fallback : configured);
            if (days == 0) UploadStore.get().delete(value.getSessionId());
            else
                UploadStore.get()
                        .retain(
                                value.getSessionId(),
                                run.getExternalizableId(),
                                System.currentTimeMillis() + days * 86_400_000L);
        });
    }

    @Override
    public void onDeleted(Run<?, ?> run) {
        forEach(run, value -> UploadStore.get().delete(value.getSessionId()));
    }

    private static void forEach(Run<?, ?> run, Operation operation) {
        hudson.model.ParametersAction action = run.getAction(hudson.model.ParametersAction.class);
        if (action == null) return;
        for (hudson.model.ParameterValue parameter : action.getAllParameters())
            if (parameter instanceof FileOrFolderParameterValue
                    && ((FileOrFolderParameterValue) parameter).isPresent()) {
                try {
                    operation.apply((FileOrFolderParameterValue) parameter);
                } catch (IOException exception) {
                    LOGGER.log(
                            Level.WARNING,
                            "Failed to update upload lifecycle for " + run.getExternalizableId(),
                            exception);
                }
            }
    }

    private interface Operation {
        void apply(FileOrFolderParameterValue value) throws IOException;
    }
}
