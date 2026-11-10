package org.Cry_Legende.sHVotePunish.network;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.Cry_Legende.sHVotePunish.data.SharedLists.Kind;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Listes des joueurs ignorés et sanctionnés en MariaDB, partagées par tous les serveurs, et registre des premières
 * connexions pour reconnaître un joueur qui n'est jamais venu sur ce serveur. Chargée seulement avec SHNetwork
 * (HikariCP et le pilote MariaDB viennent de {@code libraries:} dans plugin.yml).
 *
 * <p>Toutes les méthodes bloquent : à appeler hors du fil principal et hors du fil du bus.</p>
 */
public final class VoteDatabase {

    private static final String TABLE = "votepunish_players";
    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)?");

    /** Joueur du registre des premières connexions. */
    public record Known(String name, long firstPlayed) {
    }

    private final HikariDataSource pool;
    private final String firstSeenTable;

    public VoteDatabase(String url, String user, String password, int timeoutMs, String firstSeenTable) {
        if (!TABLE_NAME.matcher(firstSeenTable).matches()) {
            throw new IllegalArgumentException("Nom de table invalide : " + firstSeenTable);
        }
        this.firstSeenTable = firstSeenTable;
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("SHVotePunish");
        hikari.setJdbcUrl(url);
        hikari.setUsername(user);
        hikari.setPassword(password);
        hikari.setDriverClassName("org.mariadb.jdbc.Driver");
        hikari.addDataSourceProperty("connectTimeout", String.valueOf(timeoutMs));
        hikari.addDataSourceProperty("socketTimeout", String.valueOf(timeoutMs));
        hikari.setMaximumPoolSize(3);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(timeoutMs);
        // le serveur démarre même si la base est absente : les listes du fichier restent alors en mémoire
        hikari.setInitializationFailTimeout(-1);
        hikari.setKeepaliveTime(60_000);
        this.pool = new HikariDataSource(hikari);
    }

    /** Crée la table si elle manque. Une table déjà là ne demande aucun droit CREATE à l'utilisateur. */
    public void createTable() throws SQLException {
        try (Connection c = pool.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT 1 FROM " + TABLE + " LIMIT 1")) {
                return;
            } catch (SQLException missing) {
                // la table n'existe pas encore : création ci-dessous
            }
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "list VARCHAR(16) NOT NULL, "
                + "uuid CHAR(36) NOT NULL, "
                + "name VARCHAR(32) NOT NULL DEFAULT '', "
                + "since TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "PRIMARY KEY (list, uuid)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
        }
    }

    /** Tous les joueurs d'une liste. */
    public Set<UUID> load(Kind kind) throws SQLException {
        Set<UUID> out = new HashSet<>();
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT uuid FROM " + TABLE + " WHERE list = ?")) {
            ps.setString(1, kind.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        out.add(UUID.fromString(rs.getString(1)));
                    } catch (IllegalArgumentException ignored) {
                        // ligne illisible : ignorée
                    }
                }
            }
        }
        return out;
    }

    /**
     * Inscrit le joueur dans la liste. Une seule instruction atomique : si deux serveurs inscrivent le même joueur en
     * même temps, un seul reçoit « vrai ».
     */
    public boolean add(Kind kind, UUID uuid, String name) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO " + TABLE + " (list, uuid, name) VALUES (?, ?, ?)")) {
            ps.setString(1, kind.id());
            ps.setString(2, uuid.toString());
            ps.setString(3, name == null ? "" : name);
            return ps.executeUpdate() > 0;
        }
    }

    /** Retire le joueur de la liste ; vrai s'il y était. */
    public boolean remove(Kind kind, UUID uuid) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM " + TABLE + " WHERE list = ? AND uuid = ?")) {
            ps.setString(1, kind.id());
            ps.setString(2, uuid.toString());
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Reprend les listes des anciens fichiers. Un joueur déjà inscrit n'est pas touché, donc l'importation peut être
     * relancée sans rien compter deux fois.
     *
     * @return {importés, déjà présents}
     */
    public int[] importLists(Set<UUID> ignored, Set<UUID> punished) throws SQLException {
        int imported = 0;
        int present = 0;
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO " + TABLE + " (list, uuid, name) VALUES (?, ?, '')")) {
            for (Kind kind : Kind.values()) {
                for (UUID uuid : kind == Kind.IGNORED ? ignored : punished) {
                    ps.setString(1, kind.id());
                    ps.setString(2, uuid.toString());
                    if (ps.executeUpdate() > 0) {
                        imported++;
                    } else {
                        present++;
                    }
                }
            }
        }
        return new int[]{imported, present};
    }

    /** Joueur du registre des premières connexions, par pseudo (colonne name en minuscules). */
    public Optional<Known> known(String lowerName) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT display_name, first_played FROM " + firstSeenTable + " WHERE name = ?")) {
            ps.setString(1, lowerName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Timestamp first = rs.getTimestamp(2);
                return Optional.of(new Known(rs.getString(1), first == null ? 0L : first.getTime()));
            }
        }
    }

    public void close() {
        pool.close();
    }
}
