package io.jenkins.plugins.filetransfer;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.RootAction;
import java.io.IOException;
import java.util.Collections;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.interceptor.RequirePOST;

@Extension
public class FileTransferAction implements RootAction {
    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return null;
    }

    @Override
    public String getUrlName() {
        return "file-transfer";
    }

    @RequirePOST
    public HttpResponse doCreate(
            @QueryParameter String job,
            @QueryParameter String parameter,
            @QueryParameter String name,
            @QueryParameter boolean folder,
            @QueryParameter long bytes,
            @QueryParameter int files)
            throws IOException {
        FileOrFolderParameterDefinition definition = definition(job, parameter);
        UploadStore.CreatedSession created = UploadStore.get()
                .create(job, parameter, name, folder, bytes, files, definition.getMaxBytes(), definition.getMaxFiles());
        return json(JSONObject.fromObject(Collections.singletonMap("token", created.getToken())));
    }

    @RequirePOST
    public HttpResponse doFile(
            StaplerRequest request,
            @QueryParameter String token,
            @QueryParameter String path,
            @QueryParameter long size,
            @QueryParameter long offset,
            @QueryParameter long chunkBytes,
            @QueryParameter boolean last,
            @QueryParameter String job,
            @QueryParameter String parameter)
            throws IOException {
        FileOrFolderParameterDefinition definition = definition(job, parameter);
        UploadStore.Token parsed = UploadStore.Token.parse(token);
        UploadStore.get().validateTarget(parsed.id, parsed.secret, job, parameter);
        if (!definition.allows(path)) throw new IOException("File type is not allowed: " + path);
        UploadStore.get()
                .receiveChunk(parsed.id, parsed.secret, path, size, offset, chunkBytes, last, request.getInputStream());
        return json(JSONObject.fromObject(Collections.singletonMap("ok", true)));
    }

    @RequirePOST
    public HttpResponse doComplete(
            @QueryParameter String token, @QueryParameter String job, @QueryParameter String parameter)
            throws IOException {
        definition(job, parameter);
        UploadStore.Token parsed = UploadStore.Token.parse(token);
        UploadStore.get().validateTarget(parsed.id, parsed.secret, job, parameter);
        UploadStore.get().complete(parsed.id, parsed.secret);
        return json(JSONObject.fromObject(Collections.singletonMap("ok", true)));
    }

    private static HttpResponse json(JSONObject object) {
        return (request, response, node) -> {
            response.setStatus(200);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(object.toString());
        };
    }

    private FileOrFolderParameterDefinition definition(String jobName, String parameterName) throws IOException {
        Jenkins.get().checkPermission(Jenkins.READ);
        Job<?, ?> job = Jenkins.get().getItemByFullName(jobName, Job.class);
        if (job == null) throw new IOException("Job was not found");
        job.checkPermission(Item.BUILD);
        ParametersDefinitionProperty property = job.getProperty(ParametersDefinitionProperty.class);
        ParameterDefinition value = property == null ? null : property.getParameterDefinition(parameterName);
        if (!(value instanceof FileOrFolderParameterDefinition))
            throw new IOException("Upload parameter was not found");
        return (FileOrFolderParameterDefinition) value;
    }
}
