/*
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.server;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.tmatesoft.svn.core.SVNErrorCode;
import org.tmatesoft.svn.core.SVNErrorMessage;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.internal.delta.SVNDeltaCompression;
import org.tmatesoft.svn.core.io.SVNCapability;
import svnserver.Loggers;
import svnserver.auth.AnonymousAuthenticator;
import svnserver.auth.Authenticator;
import svnserver.auth.User;
import svnserver.auth.UserDB;
import svnserver.config.Config;
import svnserver.context.SharedContext;
import svnserver.parser.MessageParser;
import svnserver.parser.SvnServerParser;
import svnserver.parser.SvnServerWriter;
import svnserver.parser.token.ListBeginToken;
import svnserver.parser.token.ListEndToken;
import svnserver.repository.RepositoryInfo;
import svnserver.repository.RepositoryMapping;
import svnserver.repository.git.GitBranch;
import svnserver.server.command.*;
import svnserver.server.msg.AuthReq;
import svnserver.server.msg.ClientInfo;
import svnserver.server.step.Step;

import java.io.EOFException;
import java.io.IOException;
import java.net.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Сервер для предоставления доступа к git-у через протокол subversion.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
public final class SvnServer extends Thread {
  @NotNull
  static final String svndiff1Capability = "svndiff1";
  @NotNull
  static final String svndiff2Capability = "accepts-svndiff2";
  /**
   * {@link SVNCapability#GET_FILE_REVS_REVERSED is wrong.
   */
  @NotNull
  private static final String fileRevsReverseCapability = "file-revs-reverse";
  @NotNull
  private static final Logger log = Loggers.svn;
  private static final long FORCE_SHUTDOWN = TimeUnit.SECONDS.toMillis(5);
  @NotNull
  private static final Set<SVNErrorCode> WARNING_CODES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
      SVNErrorCode.CANCELLED,
      SVNErrorCode.ENTRY_NOT_FOUND,
      SVNErrorCode.FS_NOT_FOUND,
      SVNErrorCode.RA_NOT_AUTHORIZED,
      SVNErrorCode.REPOS_HOOK_FAILURE,
      SVNErrorCode.WC_NOT_UP_TO_DATE,
      SVNErrorCode.IO_WRITE_ERROR,
      SVNErrorCode.IO_PIPE_READ_ERROR,
      SVNErrorCode.RA_SVN_REPOS_NOT_FOUND,
      SVNErrorCode.AUTHZ_UNREADABLE,
      SVNErrorCode.AUTHZ_UNWRITABLE
  )));
  @NotNull
  private static AtomicInteger threadNumber = new AtomicInteger(1);
  @NotNull
  private final Map<String, BaseCmd<?>> commands = new HashMap<>();
  @NotNull
  private final Map<Long, Socket> connections = new ConcurrentHashMap<>();
  @NotNull
  private final RepositoryMapping<?> repositoryMapping;
  @NotNull
  private final Config config;
  @NotNull
  private final ServerSocket serverSocket;
  @NotNull
  private final AtomicBoolean stopped = new AtomicBoolean(false);
  @NotNull
  private final AtomicLong lastSessionId = new AtomicLong();
  @NotNull
  private final SharedContext sharedContext;

  public SvnServer(@NotNull Path basePath, @NotNull Config config) throws Exception {
    super("SvnServer");
    setDaemon(true);
    this.config = config;

    final ThreadFactory threadFactory = r -> {
      final Thread thread = new Thread(r, String.format("SvnServer-thread-%s", threadNumber.incrementAndGet()));
      thread.setDaemon(true);
      return thread;
    };

    sharedContext = SharedContext.create(basePath, config.getRealm(), config.getCacheConfig().createCache(basePath), threadFactory, config.getShared());
    sharedContext.add(UserDB.class, config.getUserDB().create(sharedContext));

    // Keep order as in https://svn.apache.org/repos/asf/subversion/trunk/subversion/libsvn_ra_svn/protocol

    commands.put("reparent", new ReparentCmd());
    commands.put("get-latest-rev", new GetLatestRevCmd());
    commands.put("get-dated-rev", new GetDatedRevCmd());
    // change-rev-prop
    // change-rev-prop-2
    commands.put("rev-proplist", new RevPropListCmd());
    commands.put("rev-prop", new RevPropCmd());
    commands.put("commit", new CommitCmd());
    commands.put("get-file", new GetFileCmd());
    commands.put("get-dir", new GetDirCmd());
    commands.put("check-path", new CheckPathCmd());
    commands.put("stat", new StatCmd());
    // get-mergeinfo
    commands.put("update", new DeltaCmd(UpdateParams.class));
    commands.put("switch", new DeltaCmd(SwitchParams.class));
    commands.put("status", new DeltaCmd(StatusParams.class));
    commands.put("diff", new DeltaCmd(DiffParams.class));
    commands.put("log", new LogCmd());
    commands.put("get-locations", new GetLocationsCmd());
    commands.put("get-location-segments", new GetLocationSegmentsCmd());
    commands.put("get-file-revs", new GetFileRevsCmd());
    commands.put("lock", new LockCmd());
    commands.put("lock-many", new LockManyCmd());
    commands.put("unlock", new UnlockCmd());
    commands.put("unlock-many", new UnlockManyCmd());
    commands.put("get-lock", new GetLockCmd());
    commands.put("get-locks", new GetLocksCmd());
    commands.put("replay", new ReplayCmd());
    commands.put("replay-range", new ReplayRangeCmd());
    // get-deleted-rev
    commands.put("get-iprops", new GetIPropsCmd());
    // TODO: list (#162)

    repositoryMapping = config.getRepositoryMapping().create(sharedContext, config.canUseParallelIndexing());

    sharedContext.add(RepositoryMapping.class, repositoryMapping);

    serverSocket = new ServerSocket();
    serverSocket.setReuseAddress(config.getReuseAddress());
    serverSocket.bind(new InetSocketAddress(InetAddress.getByName(config.getHost()), config.getPort()));

    boolean success = false;
    try {
      sharedContext.ready();
      success = true;
    } finally {
      if (!success)
        sharedContext.close();
    }
  }

  public int getPort() {
    return serverSocket.getLocalPort();
  }

  @NotNull
  public SharedContext getSharedContext() {
    return sharedContext;
  }

  @Override
  public void run() {
    log.info("Ready for connections on {}", serverSocket.getLocalSocketAddress());
    while (!stopped.get()) {
      final Socket client;
      try {
        client = this.serverSocket.accept();
      } catch (IOException e) {
        if (stopped.get()) {
          log.info("Server stopped");
          break;
        }
        log.error("Error accepting client connection", e);
        continue;
      }
      long sessionId = lastSessionId.incrementAndGet();
      sharedContext.getThreadPoolExecutor().execute(() -> {
        log.info("New connection from: {}", client.getRemoteSocketAddress());
        try (Socket clientSocket = client;
             SvnServerWriter writer = new SvnServerWriter(clientSocket.getOutputStream())) {
          connections.put(sessionId, client);
          serveClient(clientSocket, writer);
        } catch (EOFException | SocketException ignore) {
          // client disconnect is not a error
        } catch (SVNException | IOException e) {
          log.warn("Exception:", e);
        } finally {
          connections.remove(sessionId);
          log.info("Connection from {} closed", client.getRemoteSocketAddress());
        }
      });
    }
  }

  private void serveClient(@NotNull Socket socket, @NotNull SvnServerWriter writer) throws IOException, SVNException {
    socket.setTcpNoDelay(true);
    final SvnServerParser parser = new SvnServerParser(socket.getInputStream());

    final ClientInfo clientInfo = exchangeCapabilities(parser, writer);

    final RepositoryInfo repositoryInfo = RepositoryMapping.findRepositoryInfo(repositoryMapping, clientInfo.getUrl(), writer);
    if (repositoryInfo == null)
      return;

    final SessionContext context = new SessionContext(parser, writer, this, repositoryInfo, clientInfo);
    context.authenticate(true);
    final GitBranch branch = context.getBranch();
    branch.updateRevisions();
    sendAnnounce(writer, repositoryInfo);

    while (!isInterrupted()) {
      try {
        Step step = context.poll();
        if (step != null) {
          step.process(context);
          continue;
        }

        parser.readToken(ListBeginToken.class);

        final String cmd = parser.readText();
        final BaseCmd<?> command = commands.get(cmd);
        if (command != null) {
          log.debug("Receive command: {}", cmd);
          processCommand(context, command, parser);
        } else {
          context.skipUnsupportedCommand(cmd);
        }
      } catch (SVNException e) {
        if (WARNING_CODES.contains(e.getErrorMessage().getErrorCode())) {
          log.warn("Command execution error: {}", e.getMessage());
        } else {
          log.error("Command execution error", e);
        }
        BaseCmd.sendError(writer, e.getErrorMessage());
      }
    }
  }

  @NotNull
  private ClientInfo exchangeCapabilities(@NotNull SvnServerParser parser, @NotNull SvnServerWriter writer) throws IOException, SVNException {
    writer
        .listBegin()
        .word("success")
        .listBegin()
        .number(2)
        .number(2)
        .listBegin()
        .listEnd()
        .listBegin();

    switch (config.getCompressionLevel()) {
      case LZ4:
        writer
            .word(svndiff2Capability);
        // fallthrough
      case Zlib:
        writer
            .word(svndiff1Capability);
        break;
    }

    writer
        //.word(SVNCapability.COMMIT_REVPROPS.toString())
        .word(SVNCapability.DEPTH.toString())
        //.word(SVNCapability.PARTIAL_REPLAY.toString()) TODO: issue #237
        .word("edit-pipeline")
        .word(SVNCapability.LOG_REVPROPS.toString())
        //.word(SVNCapability.EPHEMERAL_PROPS.toString())
        .word(fileRevsReverseCapability)
        .word("absent-entries")
        .word(SVNCapability.INHERITED_PROPS.toString())
    //.word("list") TODO: issue #162
    //.word(SVNCapability.ATOMIC_REVPROPS.toString())
    ;

    writer
        .listEnd()
        .listEnd()
        .listEnd();

    // Читаем информацию о клиенте.
    final ClientInfo clientInfo = MessageParser.parse(ClientInfo.class, parser);
    log.info("Client: {}", clientInfo.getRaClient());

    if (clientInfo.getProtocolVersion() != 2)
      throw new SVNException(SVNErrorMessage.create(SVNErrorCode.VERSION_MISMATCH, "Unsupported protocol version: " + clientInfo.getProtocolVersion() + " (expected: 2)"));

    return clientInfo;
  }

  private void sendAnnounce(@NotNull SvnServerWriter writer, @NotNull RepositoryInfo repositoryInfo) throws IOException {
    writer
        .listBegin()
        .word("success")
        .listBegin()
        .string(repositoryInfo.getBranch().getUuid())
        .string(repositoryInfo.getBaseUrl().toString())
        .listBegin()
        //.word("mergeinfo")
        .listEnd()
        .listEnd()
        .listEnd();
  }

  private static <T> void processCommand(@NotNull SessionContext context, @NotNull BaseCmd<T> cmd, @NotNull SvnServerParser parser) throws IOException, SVNException {
    final T param = MessageParser.parse(cmd.getArguments(), parser);
    parser.readToken(ListEndToken.class);
    cmd.process(context, param);
  }

  @NotNull
  public User authenticate(@NotNull SessionContext context, boolean allowAnonymous) throws IOException, SVNException {
    // Отправляем запрос на авторизацию.
    final List<Authenticator> authenticators = new ArrayList<>(sharedContext.sure(UserDB.class).authenticators());
    if (allowAnonymous)
      authenticators.add(0, AnonymousAuthenticator.get());

    context.getWriter()
        .listBegin()
        .word("success")
        .listBegin()
        .listBegin()
        .word(String.join(" ", authenticators.stream().map(Authenticator::getMethodName).toArray(String[]::new)))
        .listEnd()
        .string(sharedContext.getRealm())
        .listEnd()
        .listEnd();

    while (true) {
      // Читаем выбранный вариант авторизации.
      final AuthReq authReq = MessageParser.parse(AuthReq.class, context.getParser());
      final Optional<Authenticator> authenticator = authenticators.stream().filter(o -> o.getMethodName().equals(authReq.getMech())).findAny();
      if (!authenticator.isPresent()) {
        sendError(context.getWriter(), "unknown auth type: " + authReq.getMech());
        continue;
      }

      final User user = authenticator.get().authenticate(context, authReq.getToken());
      if (user == null) {
        sendError(context.getWriter(), "incorrect credentials");
        continue;
      }

      context.getWriter()
          .listBegin()
          .word("success")
          .listBegin()
          .listEnd()
          .listEnd();

      log.info("User: {}", user);
      return user;
    }
  }

  private static void sendError(SvnServerWriter writer, String msg) throws IOException {
    writer
        .listBegin()
        .word("failure")
        .listBegin()
        .string(msg)
        .listEnd()
        .listEnd();
  }

  public void shutdown(long millis) throws Exception {
    startShutdown();
    if (!sharedContext.getThreadPoolExecutor().awaitTermination(millis, TimeUnit.MILLISECONDS)) {
      forceShutdown();
    }
    join(millis);
    sharedContext.close();
    log.info("Server shutdown complete");
  }

  public void startShutdown() throws IOException {
    if (stopped.compareAndSet(false, true)) {
      log.info("Shutdown server");
      serverSocket.close();
      sharedContext.getThreadPoolExecutor().shutdown();
    }
  }

  private void forceShutdown() throws IOException, InterruptedException {
    for (Socket socket : connections.values()) {
      socket.close();
    }
    sharedContext.getThreadPoolExecutor().awaitTermination(FORCE_SHUTDOWN, TimeUnit.MILLISECONDS);
  }

  @NotNull
  SVNDeltaCompression getCompressionLevel() {
    return config.getCompressionLevel();
  }
}
