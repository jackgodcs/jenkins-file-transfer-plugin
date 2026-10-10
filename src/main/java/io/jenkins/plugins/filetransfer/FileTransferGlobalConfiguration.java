package io.jenkins.plugins.filetransfer;

import hudson.Extension;
import java.nio.file.Path;
import java.nio.file.Paths;
import jenkins.model.GlobalConfiguration;
import org.kohsuke.stapler.DataBoundSetter;

@Extension
public class FileTransferGlobalConfiguration extends GlobalConfiguration {
    private String storageRoot;
    private long defaultMaxBytes = 1024L * 1024 * 1024;
    private long hardMaxBytes = 5L * 1024 * 1024 * 1024;
    private long defaultBuildMaxBytes = 2L * 1024 * 1024 * 1024;
    private int defaultMaxFiles = 10_000;
    private int hardMaxFiles = 50_000;
    private int maxDepth = 64;
    private int maxConcurrentUploads = 4;
    private int maxConcurrentUploadsPerUser = 2;
    private int incompleteExpiryMinutes = 60;
    private int completedExpiryMinutes = 120;
    private long minimumFreeBytes = 20L * 1024 * 1024 * 1024;
    private int minimumFreePercent = 10;
    private String deniedExtensions = "";
    private int successRetentionDays;
    private int failureRetentionDays = 3;
    private int maximumRetentionDays = 30;

    public FileTransferGlobalConfiguration() {
        load();
    }

    public static FileTransferGlobalConfiguration get() {
        return GlobalConfiguration.all().get(FileTransferGlobalConfiguration.class);
    }

    public Path getStorageRootPath() {
        String configured = storageRoot == null ? "" : storageRoot.trim();
        return configured.isEmpty()
                ? Paths.get(System.getProperty("java.io.tmpdir"), "jenkins-file-transfer")
                        .toAbsolutePath()
                        .normalize()
                : Paths.get(configured).toAbsolutePath().normalize();
    }

    public String getStorageRoot() {
        return storageRoot;
    }

    @DataBoundSetter
    public void setStorageRoot(String storageRoot) {
        this.storageRoot = storageRoot;
        save();
    }

    public long getDefaultMaxBytes() {
        return defaultMaxBytes;
    }

    @DataBoundSetter
    public void setDefaultMaxBytes(long value) {
        defaultMaxBytes = positive(value, defaultMaxBytes);
        save();
    }

    public long getHardMaxBytes() {
        return hardMaxBytes;
    }

    @DataBoundSetter
    public void setHardMaxBytes(long value) {
        hardMaxBytes = positive(value, hardMaxBytes);
        save();
    }

    public long getDefaultBuildMaxBytes() {
        return defaultBuildMaxBytes;
    }

    @DataBoundSetter
    public void setDefaultBuildMaxBytes(long value) {
        defaultBuildMaxBytes = positive(value, defaultBuildMaxBytes);
        save();
    }

    public int getDefaultMaxFiles() {
        return defaultMaxFiles;
    }

    @DataBoundSetter
    public void setDefaultMaxFiles(int value) {
        defaultMaxFiles = positive(value, defaultMaxFiles);
        save();
    }

    public int getHardMaxFiles() {
        return hardMaxFiles;
    }

    @DataBoundSetter
    public void setHardMaxFiles(int value) {
        hardMaxFiles = positive(value, hardMaxFiles);
        save();
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    @DataBoundSetter
    public void setMaxDepth(int value) {
        maxDepth = positive(value, maxDepth);
        save();
    }

    public int getMaxConcurrentUploads() {
        return maxConcurrentUploads;
    }

    @DataBoundSetter
    public void setMaxConcurrentUploads(int value) {
        maxConcurrentUploads = positive(value, maxConcurrentUploads);
        save();
    }

    public int getMaxConcurrentUploadsPerUser() {
        return maxConcurrentUploadsPerUser;
    }

    @DataBoundSetter
    public void setMaxConcurrentUploadsPerUser(int value) {
        maxConcurrentUploadsPerUser = positive(value, maxConcurrentUploadsPerUser);
        save();
    }

    public int getIncompleteExpiryMinutes() {
        return incompleteExpiryMinutes;
    }

    @DataBoundSetter
    public void setIncompleteExpiryMinutes(int value) {
        incompleteExpiryMinutes = positive(value, incompleteExpiryMinutes);
        save();
    }

    public int getCompletedExpiryMinutes() {
        return completedExpiryMinutes;
    }

    @DataBoundSetter
    public void setCompletedExpiryMinutes(int value) {
        completedExpiryMinutes = positive(value, completedExpiryMinutes);
        save();
    }

    public long getMinimumFreeBytes() {
        return minimumFreeBytes;
    }

    @DataBoundSetter
    public void setMinimumFreeBytes(long value) {
        minimumFreeBytes = Math.max(0, value);
        save();
    }

    public int getMinimumFreePercent() {
        return minimumFreePercent;
    }

    @DataBoundSetter
    public void setMinimumFreePercent(int value) {
        minimumFreePercent = Math.max(0, Math.min(100, value));
        save();
    }

    public String getDeniedExtensions() {
        return deniedExtensions;
    }

    @DataBoundSetter
    public void setDeniedExtensions(String value) {
        deniedExtensions = value == null ? "" : value;
        save();
    }

    public int getSuccessRetentionDays() {
        return successRetentionDays;
    }

    @DataBoundSetter
    public void setSuccessRetentionDays(int value) {
        successRetentionDays = retention(value);
        save();
    }

    public int getFailureRetentionDays() {
        return failureRetentionDays;
    }

    @DataBoundSetter
    public void setFailureRetentionDays(int value) {
        failureRetentionDays = retention(value);
        save();
    }

    public int getMaximumRetentionDays() {
        return maximumRetentionDays;
    }

    @DataBoundSetter
    public void setMaximumRetentionDays(int value) {
        maximumRetentionDays = Math.max(0, value);
        successRetentionDays = retention(successRetentionDays);
        failureRetentionDays = retention(failureRetentionDays);
        save();
    }

    private int retention(int value) {
        return Math.max(0, Math.min(value, maximumRetentionDays));
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static long positive(long value, long fallback) {
        return value > 0 ? value : fallback;
    }

    @Override
    public String getDisplayName() {
        return Messages.FileTransferGlobalConfiguration_DisplayName();
    }
}
