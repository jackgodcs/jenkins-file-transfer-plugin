package io.jenkins.plugins.filetransfer;

import hudson.model.Action;
import hudson.model.Run;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jenkins.model.RunAction2;

public class DownloadableResultsAction implements Action, RunAction2 {
    private transient Run<?, ?> run;
    private final List<DownloadableResult> results = new ArrayList<>();

    @Override
    public String getIconFileName() {
        return "symbol-download";
    }

    @Override
    public String getDisplayName() {
        return Messages.DownloadableResultsAction_DisplayName();
    }

    @Override
    public String getUrlName() {
        return "downloadable-results";
    }

    @Override
    public void onAttached(Run<?, ?> run) {
        this.run = run;
    }

    @Override
    public void onLoad(Run<?, ?> run) {
        this.run = run;
    }

    public Run<?, ?> getRun() {
        return run;
    }

    public synchronized List<DownloadableResult> getResults() {
        return Collections.unmodifiableList(new ArrayList<>(results));
    }

    public synchronized void add(DownloadableResult result) {
        results.add(result);
    }
}
