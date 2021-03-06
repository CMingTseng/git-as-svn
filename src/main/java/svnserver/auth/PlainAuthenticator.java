/*
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.auth;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.tmatesoft.svn.core.SVNException;
import svnserver.server.SessionContext;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * @author Marat Radchenko <marat@slonopotamus.org>
 */
public final class PlainAuthenticator implements Authenticator {

  @NotNull
  private final UserDB userDB;

  public PlainAuthenticator(@NotNull UserDB userDB) {
    this.userDB = userDB;
  }

  @NotNull
  @Override
  public String getMethodName() {
    return "PLAIN";
  }

  @Nullable
  @Override
  public User authenticate(@NotNull SessionContext context, @NotNull String token) throws SVNException {
    final byte[] decoded = Base64.getMimeDecoder().decode(token);
    final String decodedToken = new String(decoded, StandardCharsets.US_ASCII);
    final String[] credentials = decodedToken.split("\u0000");
    if (credentials.length < 3)
      return null;

    final String username = credentials[1];
    final String password = credentials[2];
    return userDB.check(username, password);
  }
}
