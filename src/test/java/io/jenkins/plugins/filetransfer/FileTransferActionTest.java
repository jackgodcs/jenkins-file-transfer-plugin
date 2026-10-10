package io.jenkins.plugins.filetransfer;

import static org.junit.Assert.assertTrue;

import hudson.model.RootAction;
import org.junit.Test;

public class FileTransferActionTest {
    @Test
    public void uploadEndpointsArePublishedAsARootAction() {
        assertTrue(new FileTransferAction() instanceof RootAction);
    }
}
