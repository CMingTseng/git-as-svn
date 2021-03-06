/*
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.tester;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;
import org.tmatesoft.svn.core.SVNAuthenticationException;
import org.tmatesoft.svn.core.SVNErrorCode;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.auth.BasicAuthenticationManager;
import org.tmatesoft.svn.core.io.SVNRepositoryFactory;
import svnserver.SvnTestHelper;
import svnserver.TestHelper;

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.concurrent.TimeUnit;

/**
 * Listener for creating SvnTesterExternal.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
public class SvnTesterExternalListener implements ITestListener {
  @NotNull
  private static final Logger log = TestHelper.logger;
  @NotNull
  private static final String USER_NAME = "tester";
  @NotNull
  private static final String PASSWORD = "passw0rd";
  @NotNull
  private static final String HOST = "127.0.0.2";
  @NotNull
  private static final String CONFIG_SERVER = "" +
      "[general]\n" +
      "anon-access = none\n" +
      "auth-access = write\n" +
      "password-db = {0}\n";
  @NotNull
  private static final String CONFIG_PASSWD = "" +
      "[users]\n" +
      "{0} = {1}\n";
  private static final long SERVER_STARTUP_TIMEOUT = TimeUnit.SECONDS.toMillis(30);
  private static final long SERVER_STARTUP_DELAY = TimeUnit.MILLISECONDS.toMillis(20);
  @Nullable
  private static NativeDaemon daemon;

  @Nullable
  public static SvnTesterFactory get() {
    return daemon;
  }

  @Override
  public void onTestStart(ITestResult result) {
  }

  @Override
  public void onTestSuccess(ITestResult result) {
  }

  @Override
  public void onTestFailure(ITestResult result) {
  }

  @Override
  public void onTestSkipped(ITestResult result) {
  }

  @Override
  public void onTestFailedButWithinSuccessPercentage(ITestResult result) {
  }

  @Override
  public void onStart(ITestContext context) {
    try {
      final String svnserve = SvnTestHelper.findExecutable("svnserve");
      final String svnadmin = SvnTestHelper.findExecutable("svnadmin");
      if (svnserve != null && svnadmin != null) {
        log.warn("Native svn daemon executables: {}, {}", svnserve, svnadmin);
        daemon = new NativeDaemon(svnserve, svnadmin);
      } else {
        log.warn("Native svn daemon disabled");
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void onFinish(ITestContext context) {
    if (daemon != null) {
      try {
        daemon.close();
      } catch (Exception e) {
        throw new IllegalStateException(e);
      } finally {
        daemon = null;
      }
    }
  }

  private static class NativeDaemon implements SvnTesterFactory, AutoCloseable {
    @NotNull
    private final Process daemon;
    @NotNull
    private final Path repo;
    @NotNull
    private final SVNURL url;

    NativeDaemon(@NotNull String svnserve, @NotNull String svnadmin) throws IOException, InterruptedException, SVNException {
      int port = detectPort();
      url = SVNURL.create("svn", null, HOST, port, null, true);
      repo = TestHelper.createTempDir("git-as-svn-repo");
      log.info("Starting native svn daemon at: {}, url: {}", repo, url);
      Runtime.getRuntime().exec(new String[]{
          svnadmin,
          "create",
          repo.toString()
      }).waitFor();
      Path config = createConfigs(repo);
      daemon = Runtime.getRuntime().exec(new String[]{
          svnserve,
          "--daemon",
          "--root", repo.toString(),
          "--config-file", config.toString(),
          "--listen-host", HOST,
          "--listen-port", Integer.toString(port)
      });
      long serverStartupTimeout = System.currentTimeMillis() + SERVER_STARTUP_TIMEOUT;
      while (true) {
        try {
          SVNRepositoryFactory.create(url).getRevisionPropertyValue(0, "example");
        } catch (SVNAuthenticationException ignored) {
          break;
        } catch (SVNException e) {
          if ((e.getErrorMessage().getErrorCode() == SVNErrorCode.RA_SVN_IO_ERROR) && (System.currentTimeMillis() < serverStartupTimeout)) {
            Thread.sleep(SERVER_STARTUP_DELAY);
            continue;
          }
          throw e;
        }
        break;
      }
    }

    private int detectPort() throws IOException {
      try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName(HOST))) {
        return socket.getLocalPort();
      }
    }

    @NotNull
    private static Path createConfigs(@NotNull Path repo) throws IOException {
      final Path config = repo.resolve("conf/server.conf");
      final Path passwd = repo.resolve("conf/server.passwd");
      try (Writer writer = new FileWriter(config.toFile())) {
        writer.write(MessageFormat.format(CONFIG_SERVER, passwd.toString()));
      }
      try (Writer writer = new FileWriter(passwd.toFile())) {
        writer.write(MessageFormat.format(CONFIG_PASSWD, USER_NAME, PASSWORD));
      }
      return config;
    }

    @NotNull
    @Override
    public SvnTester create() throws Exception {
      return new SvnTesterExternal(url, BasicAuthenticationManager.newInstance(USER_NAME, PASSWORD.toCharArray()));
    }

    @Override
    public void close() throws Exception {
      log.info("Stopping native svn daemon.");
      daemon.destroy();
      daemon.waitFor();
      TestHelper.deleteDirectory(repo);
    }
  }
}
