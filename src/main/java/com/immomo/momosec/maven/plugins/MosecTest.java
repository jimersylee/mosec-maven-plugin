package com.immomo.momosec.maven.plugins;

import com.google.gson.*;
import com.immomo.momosec.maven.plugins.exceptions.NetworkErrorException;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.repository.RemoteRepository;

import java.util.ArrayList;
import java.util.List;

@Mojo(name = "test")
public class MosecTest extends AbstractMojo {

    @Component
    private RepositorySystem repositorySystem;

    @Parameter(property = "project", required = true, readonly = true)
    private MavenProject project;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repositorySystemSession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
    private List<RemoteRepository> remoteProjectRepositories;

    @Parameter(defaultValue = "${project.remotePluginRepositories}", readonly = true)
    private List<RemoteRepository> remotePluginRepositories;

    @Parameter(defaultValue = "${settings}", readonly = true, required = true )
    private Settings settings;

    /**
     * 威胁等级 [High|Medium|Low]
     */
    @Parameter(property = "severity", defaultValue = "High")
    private String severityLevel;

    /**
     * 仅检查直接依赖
     */
    @Parameter(property = "onlyProvenance", defaultValue = "false")
    private Boolean onlyProvenance;

    /**
     * 发现漏洞即编译失败
     */
    @Parameter(property = "failOnVuln", defaultValue = "true")
    private Boolean failOnVuln;

    /**
     * 上报API
     */
    @Parameter(property = "endpoint")
    private String endpoint;

    /**
     * 是否包含Provided Scope依赖
     */
    @Parameter(property = "includeProvidedDependency", defaultValue = "false")
    private Boolean includeProvidedDependency;

    public void execute() throws MojoFailureException {
        String env_endpoint = System.getenv(Constants.MOSEC_ENDPOINT_ENV);
        if (env_endpoint != null) {
            endpoint = env_endpoint;
        }

        if (endpoint == null) {
            throw new MojoFailureException(Constants.ERROR_ON_NULL_ENDPOINT);
        }

        if (remoteProjectRepositories == null) {
            remoteProjectRepositories = new ArrayList<>();
        }

        if (remotePluginRepositories == null) {
            remotePluginRepositories = new ArrayList<>();
        }

        try {
            for (RemoteRepository remoteProjectRepository : remoteProjectRepositories) {
                getLog().debug("Remote project repository: " + remoteProjectRepository);
            }
            for (RemoteRepository remotePluginRepository : remotePluginRepositories) {
                getLog().debug("Remote plugin repository: " + remotePluginRepository);
            }
            List<RemoteRepository> remoteRepositories = new ArrayList<>(remoteProjectRepositories);
            remoteRepositories.addAll(remotePluginRepositories);

            ProjectDependencyCollector collector = new ProjectDependencyCollector(
                    project,
                    repositorySystem,
                    repositorySystemSession,
                    remoteRepositories,
                    includeProvidedDependency,
                    onlyProvenance
            );
            collector.collectDependencies();
            JsonObject projectTree = collector.getTree();

            projectTree.addProperty("type", Constants.BUILD_TOOL_TYPE);
            projectTree.addProperty("language", Constants.PROJECT_LANGUAGE);
            projectTree.addProperty("severityLevel", severityLevel);
            getLog().debug(new GsonBuilder().setPrettyPrinting().create().toJson(projectTree));

            HttpPost request = new HttpPost(endpoint);
            request.addHeader("content-type", Constants.CONTENT_TYPE_JSON);
            HttpEntity entity = new StringEntity(projectTree.toString());
            request.setEntity(entity);

            HttpClientHelper httpClientHelper = new HttpClientHelper(getLog(), settings);
            HttpClient client = httpClientHelper.buildHttpClient();
            HttpResponse response = client.execute(request);

            if (response.getStatusLine().getStatusCode() >= 400) {
                throw new NetworkErrorException(response.getStatusLine().getReasonPhrase());
            }

            Renderer renderer = new Renderer(getLog(), failOnVuln);
            renderer.renderResponse(response.getEntity().getContent());

        } catch (DependencyCollectionException e) {
            throw new MojoFailureException(e.getMessage());
        } catch(MojoFailureException e) {
            throw e;
        } catch(Exception e) {
            if (getLog().isDebugEnabled()) {
                getLog().error(Constants.ERROR_GENERAL, e);
            } else {
                getLog().error(Constants.ERROR_GENERAL);
                getLog().error(Constants.ERROR_RERUN_WITH_DEBUG);
            }
        }
    }
}
