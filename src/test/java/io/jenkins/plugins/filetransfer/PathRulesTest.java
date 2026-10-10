package io.jenkins.plugins.filetransfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import org.junit.Test;

public class PathRulesTest {
    @Test
    public void normalizesFolderSeparatorsWithoutChangingStructure() throws Exception {
        assertEquals("folder/nested/file.txt", PathRules.normalizeRelative("folder\\nested\\file.txt", 64));
    }

    @Test
    public void rejectsTraversalAbsoluteAndWindowsDevicePaths() {
        assertThrows(IOException.class, () -> PathRules.normalizeRelative("../secret.txt", 64));
        assertThrows(IOException.class, () -> PathRules.normalizeRelative("C:\\secret.txt", 64));
        assertThrows(IOException.class, () -> PathRules.normalizeRelative("folder/CON.txt", 64));
    }
}
