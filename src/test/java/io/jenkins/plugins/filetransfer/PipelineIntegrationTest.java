package io.jenkins.plugins.filetransfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Result;
import hudson.security.ACL;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.JenkinsRule;

public class PipelineIntegrationTest {
    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void folderUploadCanBeRestoredAndPublishedAsDownloadableArtifacts() throws Exception {
        configureStore();
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "process-upload");
        job.addProperty(new ParametersDefinitionProperty(new FileOrFolderParameterDefinition("INPUT")));
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        UploadStore.CreatedSession created =
                UploadStore.get().create(job.getFullName(), "INPUT", "input-root", true, content.length, 1, 1024, 10);
        UploadStore.Token token = UploadStore.Token.parse(created.getToken());
        UploadStore.get()
                .receiveChunk(
                        token.id,
                        token.secret,
                        "input-root/nested/data.txt",
                        content.length,
                        0,
                        content.length,
                        true,
                        new ByteArrayInputStream(content));
        UploadSession session = UploadStore.get().complete(token.id, token.secret);
        UploadStore.get().reserve(created.getToken(), job.getFullName(), "INPUT");
        FileOrFolderParameterValue value = new FileOrFolderParameterValue(
                "INPUT",
                session.getId(),
                true,
                session.getTopLevelName(),
                session.getDeclaredBytes(),
                session.getDeclaredFiles(),
                0,
                3);
        job.setDefinition(new CpsFlowDefinition(
                "node { if (!hasUploadedFiles(parameter: 'INPUT')) { error 'missing upload' }; restoreUploadedFiles parameter: 'INPUT', target: 'restored'; if (readFile('restored/input-root/nested/data.txt') != 'hello') { error 'bad restore' }; writeFile file: 'output/result.txt', text: 'processed'; publishDownloadableResults path: 'output', name: 'Processed' }",
                true));

        WorkflowRun run = jenkins.assertBuildStatusSuccess(job.scheduleBuild2(0, new ParametersAction(value)));

        DownloadableResultsAction action = run.getAction(DownloadableResultsAction.class);
        assertNotNull(action);
        assertEquals(1, action.getResults().size());
        DownloadableResult result = action.getResults().get(0);
        assertEquals(List.of("result.txt"), result.getFiles());
        assertTrue(result.isDirectory());
        assertTrue(run.getArtifactManager()
                .root()
                .child(result.getArtifactPath() + "result.txt")
                .isFile());
        assertFalse(Files.exists(UploadStore.get().files(session.getId())));
    }

    @Test
    public void optionalUploadCanBeEmpty() throws Exception {
        configureStore();
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "optional-upload");
        FileOrFolderParameterDefinition definition = new FileOrFolderParameterDefinition("INPUT");
        definition.setRequired(false);
        job.addProperty(new ParametersDefinitionProperty(definition));
        job.setDefinition(new CpsFlowDefinition(
                "node { if (hasUploadedFiles(parameter: 'INPUT')) { error 'unexpected upload' } }", true));
        FileOrFolderParameterValue value = new FileOrFolderParameterValue("INPUT", null, false, null, 0, 0, -1, -1);

        jenkins.assertBuildStatusSuccess(job.scheduleBuild2(0, new ParametersAction(value)));
    }

    @Test
    public void browserBuildFormSubmitsUploadedParameter() throws Exception {
        configureStore();
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "browser-upload");
        job.addProperty(new ParametersDefinitionProperty(new FileOrFolderParameterDefinition("INPUT")));
        job.setDefinition(new CpsFlowDefinition(
                "node { if (!hasUploadedFiles(parameter: 'INPUT')) { error 'missing upload' } }", true));
        UploadStore.CreatedSession created;
        try (hudson.security.ACLContext ignored = ACL.as2(Jenkins.ANONYMOUS2)) {
            created = UploadStore.get().create(job.getFullName(), "INPUT", "input.txt", false, 1, 1, 1024, 10);
            UploadStore.Token token = UploadStore.Token.parse(created.getToken());
            UploadStore.get()
                    .receiveChunk(
                            token.id, token.secret, "input.txt", 1, 0, 1, true, new ByteArrayInputStream(new byte[] {1
                            }));
            UploadStore.get().complete(token.id, token.secret);
        }
        JenkinsRule.WebClient webClient = jenkins.createWebClient();
        webClient.getOptions().setJavaScriptEnabled(false);
        webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
        HtmlPage page = webClient.getPage(job, "build?delay=0sec");
        jenkins.assertXPathValue(page, "//div[@name='parameter']//input[@name='name']/@value", "INPUT");
        jenkins.assertXPath(page, "//div[@name='parameter']//input[@name='value']");
        WebRequest request =
                new WebRequest(new URL(jenkins.getURL(), job.getUrl() + "build?delay=0sec"), HttpMethod.POST);
        request.setRequestParameters(Arrays.asList(
                new NameValuePair(
                        "json", "{\"parameter\":{\"name\":\"INPUT\",\"value\":\"" + created.getToken() + "\"}}"),
                new NameValuePair("statusCode", "303"),
                new NameValuePair("redirectTo", ".")));
        request.setAdditionalHeader("Jenkins-Crumb", "test");
        webClient.getPage(request);
        jenkins.waitUntilNoActivity();

        WorkflowRun run = job.getLastBuild();
        assertNotNull(run);
        ParametersAction action = run.getAction(ParametersAction.class);
        assertNotNull(action);
        assertTrue(action.getParameter("INPUT") instanceof FileOrFolderParameterValue);
        jenkins.assertBuildStatusSuccess(run);
    }

    @Test
    public void combinedBuildLimitDoesNotPartiallyBindUploads() throws Exception {
        FileTransferGlobalConfiguration config = configureStore();
        config.setDefaultBuildMaxBytes(1);
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "limited-upload");
        job.addProperty(new ParametersDefinitionProperty(
                new FileOrFolderParameterDefinition("FIRST"), new FileOrFolderParameterDefinition("SECOND")));
        FileOrFolderParameterValue first = upload(job, "FIRST", "first.bin", new byte[] {1}, 0, 3);
        FileOrFolderParameterValue second = upload(job, "SECOND", "second.bin", new byte[] {2}, 0, 3);
        job.setDefinition(new CpsFlowDefinition("node { hasUploadedFiles(parameter: 'FIRST') }", true));

        jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0, new ParametersAction(first, second)));

        UploadStore.get().bind(first.getSessionId(), "probe#1");
        UploadStore.get().bind(second.getSessionId(), "probe#1");
    }

    @Test
    public void failedBuildRetainsBoundUpload() throws Exception {
        configureStore();
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "failed-upload");
        job.addProperty(new ParametersDefinitionProperty(new FileOrFolderParameterDefinition("INPUT")));
        FileOrFolderParameterValue value = upload(job, "INPUT", "input.bin", new byte[] {1}, 0, 1);
        job.setDefinition(new CpsFlowDefinition(
                "node { if (!hasUploadedFiles(parameter: 'INPUT')) { error 'missing upload' }; error 'processing failed' }",
                true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0, new ParametersAction(value)));

        UploadSession retained = UploadStore.get().loadBound(value.getSessionId(), run.getExternalizableId());
        assertEquals(UploadSession.State.BOUND, retained.getState());
        assertTrue(retained.getExpiresAt() > System.currentTimeMillis());
        assertTrue(Files.exists(UploadStore.get().files(value.getSessionId())));
    }

    private FileTransferGlobalConfiguration configureStore() throws Exception {
        FileTransferGlobalConfiguration config = FileTransferGlobalConfiguration.get();
        config.setStorageRoot(temporary.newFolder().getAbsolutePath());
        config.setMinimumFreeBytes(0);
        config.setMinimumFreePercent(0);
        return config;
    }

    private FileOrFolderParameterValue upload(
            WorkflowJob job,
            String parameter,
            String name,
            byte[] content,
            int successRetentionDays,
            int failureRetentionDays)
            throws Exception {
        UploadStore.CreatedSession created =
                UploadStore.get().create(job.getFullName(), parameter, name, false, content.length, 1, 1024, 10);
        UploadStore.Token token = UploadStore.Token.parse(created.getToken());
        UploadStore.get()
                .receiveChunk(
                        token.id,
                        token.secret,
                        name,
                        content.length,
                        0,
                        content.length,
                        true,
                        new ByteArrayInputStream(content));
        UploadSession session = UploadStore.get().complete(token.id, token.secret);
        UploadStore.get().reserve(created.getToken(), job.getFullName(), parameter);
        return new FileOrFolderParameterValue(
                parameter,
                session.getId(),
                false,
                session.getTopLevelName(),
                session.getDeclaredBytes(),
                session.getDeclaredFiles(),
                successRetentionDays,
                failureRetentionDays);
    }
}
