package io.jenkins.plugins.filetransfer;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Extension
public class UploadCleanup extends AsyncPeriodicWork {
    public UploadCleanup() {
        super("File transfer upload cleanup");
    }

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit.MINUTES.toMillis(15);
    }

    @Override
    protected void execute(TaskListener listener) throws IOException, InterruptedException {
        listener.getLogger().println("Removed " + UploadStore.get().cleanupExpired() + " expired upload sessions");
    }
}
