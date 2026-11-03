package dev.workhub.admin;

import dev.workhub.common.Ids;

import java.sql.*;
import java.util.*;

/** Deployment-only CLI; uses configured DB credentials, never exposed through HTTP. */
public final class DeploymentAdmin {
    public static void main(String[] args) throws Exception {
        if (args.length < 1)
            throw new IllegalArgumentException(
                    "workspace-admin <workspace UUID> <login> | github-grant <workspace UUID>"
                            + " <installation ID> <repository ID> <owner/repo>");
        String url =
                "jdbc:mysql://"
                        + env("MYSQL_HOST", "127.0.0.1")
                        + ":"
                        + env("MYSQL_PORT", "13306")
                        + "/"
                        + env("MYSQL_DATABASE", "workhub")
                        + "?connectionTimeZone=UTC&allowPublicKeyRetrieval=true&sslMode=DISABLED";
        try (Connection c =
                DriverManager.getConnection(
                        url,
                        env("MYSQL_USER", "workhub"),
                        Objects.requireNonNull(
                                System.getenv("MYSQL_PASSWORD"), "MYSQL_PASSWORD required"))) {
            c.setAutoCommit(false);
            try {
                UUID w = UUID.fromString(args[1]);
                try (var s = c.prepareStatement("SELECT id FROM workspace WHERE id=? FOR UPDATE")) {
                    s.setBytes(1, Ids.bytes(w));
                    if (!s.executeQuery().next())
                        throw new IllegalArgumentException("Workspace not found");
                }
                if (args[0].equals("workspace-admin") && args.length == 3) {
                    try (var s =
                            c.prepareStatement(
                                    "UPDATE workspace_member m JOIN app_user u ON u.id=m.user_id"
                                            + " SET m.role='ADMIN',m.revision=m.revision+1 WHERE"
                                            + " m.workspace_id=? AND u.login=? AND m.active AND"
                                            + " u.active")) {
                        s.setBytes(1, Ids.bytes(w));
                        s.setString(2, args[2]);
                        if (s.executeUpdate() != 1)
                            throw new IllegalArgumentException("Active workspace member required");
                    }
                } else if (args[0].equals("github-grant") && args.length == 5) {
                    long installation = Long.parseLong(args[2]),
                            repository = Long.parseLong(args[3]);
                    if (installation <= 0
                            || repository <= 0
                            || !args[4].matches("[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+")
                            || args[4].contains(".."))
                        throw new IllegalArgumentException("Invalid repository mapping");
                    try (var s =
                            c.prepareStatement(
                                    "INSERT INTO"
                                        + " github_installation_grant(installation_id,workspace_id)"
                                        + " VALUES (?,?) ON DUPLICATE KEY UPDATE"
                                        + " installation_id=installation_id")) {
                        s.setLong(1, installation);
                        s.setBytes(2, Ids.bytes(w));
                        s.executeUpdate();
                    }
                    try (var s =
                            c.prepareStatement(
                                    "SELECT workspace_id FROM github_installation_grant WHERE"
                                            + " installation_id=? FOR UPDATE")) {
                        s.setLong(1, installation);
                        var r = s.executeQuery();
                        r.next();
                        if (!Arrays.equals(r.getBytes(1), Ids.bytes(w)))
                            throw new IllegalArgumentException(
                                    "Installation already belongs to a different workspace");
                    }
                    try (var s =
                            c.prepareStatement(
                                    "INSERT INTO"
                                        + " github_repository_grant(repository_id,installation_id,repository_name)"
                                        + " VALUES (?,?,?) ON DUPLICATE KEY UPDATE"
                                        + " repository_id=repository_id")) {
                        s.setLong(1, repository);
                        s.setLong(2, installation);
                        s.setString(3, args[4]);
                        s.executeUpdate();
                    }
                    try (var s =
                            c.prepareStatement(
                                    "SELECT installation_id,repository_name FROM"
                                            + " github_repository_grant WHERE repository_id=?")) {
                        s.setLong(1, repository);
                        var r = s.executeQuery();
                        r.next();
                        if (r.getLong(1) != installation || !r.getString(2).equals(args[4]))
                            throw new IllegalArgumentException(
                                    "Repository already has a different grant");
                    }
                } else throw new IllegalArgumentException("Invalid command or argument count");
                c.commit();
                System.out.println("Deployment configuration applied; no remote request sent.");
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    private static String env(String key, String fallback) {
        return System.getenv().getOrDefault(key, fallback);
    }
}
