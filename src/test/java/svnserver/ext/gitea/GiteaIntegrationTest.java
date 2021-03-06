/*
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.ext.gitea;

import io.gitea.ApiClient;
import io.gitea.api.RepositoryApi;
import io.gitea.api.UserApi;
import io.gitea.auth.ApiKeyAuth;
import io.gitea.model.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.FixedHostPortGenericContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.tmatesoft.svn.core.SVNAuthenticationException;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.io.SVNRepository;
import svnserver.SvnTestHelper;
import svnserver.SvnTestServer;
import svnserver.TestHelper;
import svnserver.UserType;
import svnserver.config.RepositoryMappingConfig;
import svnserver.ext.gitea.auth.GiteaUserDBConfig;
import svnserver.ext.gitea.config.GiteaConfig;
import svnserver.ext.gitea.config.GiteaContext;
import svnserver.ext.gitea.config.GiteaToken;
import svnserver.ext.gitea.mapping.GiteaMappingConfig;
import svnserver.ext.gitlfs.storage.LfsStorage;
import svnserver.ext.gitlfs.storage.local.LfsLocalStorageTest;
import svnserver.repository.git.GitCreateMode;

import java.nio.file.Path;
import java.util.function.Function;

/**
 * @author Andrew Thornton <zeripath@users.noreply.github.com>
 */
public final class GiteaIntegrationTest {
  @NotNull
  private static final Logger log = TestHelper.logger;
  @NotNull
  private static final String administrator = "administrator";
  @NotNull
  private static final String administratorPassword = "administrator";
  @NotNull
  private static final String user = "testuser";
  @NotNull
  private static final String userPassword = "userPassword";
  @NotNull
  private static final String collaborator = "collaborator";
  @NotNull
  private static final String collaboratorPassword = "collaboratorPassword";

  private GenericContainer<?> gitea;
  private String giteaUrl;
  private String giteaApiUrl;
  private GiteaToken administratorToken;
  private Repository testPublicRepository;
  private Repository testPrivateRepository;

  @BeforeClass
  void before() throws Exception {
    SvnTestHelper.skipTestIfDockerUnavailable();

    String giteaVersion = System.getenv("GITEA_VERSION");
    if (giteaVersion == null) {
      SvnTestHelper.skipTestIfRunningOnCI();

      giteaVersion = "latest";
    }

    final int hostPort = 9999;
    final int containerPort = 3000;
    final String hostname = DockerClientFactory.instance().dockerHostIpAddress();

    giteaUrl = String.format("http://%s:%s", hostname, hostPort);
    giteaApiUrl = giteaUrl + "/api/v1";

    gitea = new FixedHostPortGenericContainer<>("gitea/gitea:" + giteaVersion)
        .withFixedExposedPort(hostPort, containerPort)
        .withExposedPorts(containerPort)
        .withEnv("ROOT_URL", giteaUrl)
        .withEnv("INSTALL_LOCK", "true")
        .withEnv("SECRET_KEY", "CmjF5WBUNZytE2C80JuogljLs5enS0zSTlikbP2HyG8IUy15UjkLNvTNsyYW7wN")
        .withEnv("RUN_MODE", "prod")
        .withEnv("LFS_START_SERVER", "true")
        .waitingFor(Wait.forHttp("/user/login"))
        .withLogConsumer(new Slf4jLogConsumer(log));

    gitea.start();

    ExecResult createUserHelpResult = gitea.execInContainer("gitea", "admin", "create-user", "--help", "-c", "/data/gitea/conf/app.ini");
    boolean mustChangePassword = createUserHelpResult.getStdout().contains("--must-change-password");
    String mustChangePasswordString = mustChangePassword ? "--must-change-password=false" : "";
    {
      ExecResult result = gitea.execInContainer("gitea", "admin", "create-user", "--name", administrator,
          "--password", administratorPassword, "--email", "administrator@example.com", "--admin",
          mustChangePasswordString, "-c", "/data/gitea/conf/app.ini");
      System.out.println(result.getStdout());
      System.err.println(result.getStderr());
    }

    ApiClient apiClient = new ApiClient();
    apiClient.setBasePath(giteaApiUrl);
    apiClient.setUsername(administrator);
    apiClient.setPassword(administratorPassword);
    UserApi userApi = new UserApi(apiClient);
    AccessTokenName accessTokenName = new AccessTokenName();
    accessTokenName.setName("integration-test");
    AccessToken token = userApi.userCreateToken(administrator, accessTokenName);
    administratorToken = new GiteaToken(token.getSha1());

    // Switch to the GiteaContext approach
    // CreateTestUser
    final User testUser = createUser(user, userPassword);
    Assert.assertNotNull(testUser);

    final User collaboratorUser = createUser(collaborator, collaboratorPassword);
    Assert.assertNotNull(collaboratorUser);

    // Create a repository for the test user
    testPublicRepository = createRepository(user, "public-user-repo", "Public User Repository", false, true);
    Assert.assertNotNull(testPublicRepository);

    testPrivateRepository = createRepository(user, "private-user-repo", "Private User Repository", true, true);
    Assert.assertNotNull(testPrivateRepository);
  }

  @NotNull
  private User createUser(@NotNull String username, @NotNull String password) throws Exception {
    return createUser(username, username + "@example.com", password);
  }

  @NotNull
  private Repository createRepository(@NotNull String username, @NotNull String name, @NotNull String description, @Nullable Boolean _private, @Nullable Boolean autoInit) throws Exception {
    final ApiClient apiClient = sudo(GiteaContext.connect(giteaApiUrl, administratorToken), username);
    final RepositoryApi repositoryApi = new RepositoryApi(apiClient);
    final CreateRepoOption repoOption = new CreateRepoOption();
    repoOption.setName(name);
    repoOption.setDescription(description);
    repoOption.setPrivate(_private);
    repoOption.setAutoInit(autoInit);
    repoOption.setReadme("Default");
    return repositoryApi.createCurrentUserRepo(repoOption);
  }

  @NotNull
  private User createUser(@NotNull String username, @NotNull String email, @NotNull String password) throws Exception {
    // Need to create user using command line because users now default to requiring to change password
    ExecResult createUserHelpResult = gitea.execInContainer("gitea", "admin", "create-user", "--help", "-c", "/data/gitea/conf/app.ini");
    boolean mustChangePassword = createUserHelpResult.getStdout().contains("--must-change-password");
    String mustChangePasswordString = mustChangePassword ? "--must-change-password=false" : "";
    gitea.execInContainer("gitea", "admin", "create-user", "--name", username,
        "--password", password, "--email", email,
        mustChangePasswordString, "-c", "/data/gitea/conf/app.ini");
    ApiClient apiClient = GiteaContext.connect(giteaApiUrl, administratorToken);
    UserApi userApi = new UserApi(sudo(apiClient, username));

    return userApi.userGetCurrent();
  }

  // Gitea API methods
  @NotNull
  private ApiClient sudo(ApiClient apiClient, String username) {
    ApiKeyAuth sudoParam = (ApiKeyAuth) apiClient.getAuthentication("SudoParam");
    sudoParam.setApiKey(username);
    return apiClient;
  }

  @Test
  void testLfs() throws Exception {
    final LfsStorage storage = GiteaConfig.createLfsStorage(giteaUrl, testPublicRepository.getFullName(), administratorToken);
    final svnserver.auth.User user = svnserver.auth.User.create(administrator, administrator, administrator, administrator, UserType.Gitea);

    LfsLocalStorageTest.checkLfs(storage, user);
    LfsLocalStorageTest.checkLfs(storage, user);

    LfsLocalStorageTest.checkLocks(storage, user);
  }

  // Tests
  @Test
  void testApiConnectPassword() throws Exception {
    ApiClient apiClient = new ApiClient();
    apiClient.setBasePath(giteaApiUrl);
    apiClient.setUsername(administrator);
    apiClient.setPassword(administratorPassword);
    UserApi userApi = new UserApi(apiClient);
    User user = userApi.userGetCurrent();
    Assert.assertNotNull(user);
    Assert.assertEquals(user.getLogin(), administrator);
  }

  @Test
  void testGiteaContextConnect() throws Exception {
    ApiClient apiClient = GiteaContext.connect(giteaApiUrl, administratorToken);
    UserApi userApi = new UserApi(apiClient);
    User user = userApi.userGetCurrent();
    Assert.assertNotNull(user);
    Assert.assertEquals(user.getLogin(), administrator);
  }

  @Test
  void testCheckAdminLogin() throws Exception {
    checkUser(administrator, administratorPassword);
  }

  private void checkUser(@NotNull String login, @NotNull String password) throws Exception {
    try (SvnTestServer server = createServer(administratorToken, null)) {
      server.openSvnRepository(login, password).getLatestRevision();
    }
  }

  // SvnTest Methods
  @NotNull
  private SvnTestServer createServer(@NotNull GiteaToken token, @Nullable Function<Path, RepositoryMappingConfig> mappingConfigCreator) throws Exception {
    final GiteaConfig giteaConfig = new GiteaConfig(giteaApiUrl, token);
    return SvnTestServer.createEmpty(new GiteaUserDBConfig(), mappingConfigCreator, false, SvnTestServer.LfsMode.None, giteaConfig);
  }

  @Test
  void testCheckUserLogin() throws Exception {
    checkUser(user, userPassword);
  }

  @Test
  void testInvalidPassword() {
    Assert.expectThrows(SVNAuthenticationException.class, () -> checkUser(administrator, "wrongpassword"));
  }

  @Test
  void testInvalidUser() {
    Assert.expectThrows(SVNAuthenticationException.class, () -> checkUser("wronguser", administratorPassword));
  }

  @Test
  void testGiteaMapping() throws Exception {
    try (SvnTestServer server = createServer(administratorToken, dir -> new GiteaMappingConfig(dir, GitCreateMode.EMPTY))) {
      // Test user can get own private repo
      openSvnRepository(server, testPrivateRepository, user, userPassword).getLatestRevision();
      // Collaborator cannot get test's private repo
      Assert.assertThrows(SVNAuthenticationException.class, () -> openSvnRepository(server, testPrivateRepository, collaborator, collaboratorPassword).getLatestRevision());
      // Anyone can get public repo
      openSvnRepository(server, testPublicRepository, "anonymous", "nopassword").getLatestRevision();
      // Collaborator can get public repo
      openSvnRepository(server, testPublicRepository, collaborator, collaboratorPassword).getLatestRevision();
      // Add collaborator to private repo
      repoAddCollaborator(testPrivateRepository.getOwner().getLogin(), testPrivateRepository.getName(), collaborator);
      // Collaborator can get private repo
      openSvnRepository(server, testPrivateRepository, collaborator, collaboratorPassword).getLatestRevision();
    }
  }

  @NotNull
  private SVNRepository openSvnRepository(@NotNull SvnTestServer server, @NotNull Repository repository, @NotNull String username, @NotNull String password) throws SVNException {
    return SvnTestServer.openSvnRepository(server.getUrl(false).appendPath(repository.getFullName() + "/master", false), username, password);
  }

  private void repoAddCollaborator(@NotNull String owner, @NotNull String repo, @NotNull String collaborator) throws Exception {
    ApiClient apiClient = GiteaContext.connect(giteaApiUrl, administratorToken);
    RepositoryApi repositoryApi = new RepositoryApi(apiClient);
    AddCollaboratorOption aco = new AddCollaboratorOption();
    aco.setPermission("write");
    repositoryApi.repoAddCollaborator(owner, repo, collaborator, aco);
  }

  @AfterClass
  void after() {
    if (gitea != null) {
      gitea.stop();
      gitea = null;
    }
  }
}