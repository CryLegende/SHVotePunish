package org.Cry_Legende.sHVotePunish.data;

import org.Cry_Legende.sHVotePunish.SHVotePunish;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Gère le stockage persistant des données du plugin, comme les joueurs ignorés et sanctionnés.
 * Utilise des fichiers dédiés : ignored_players.yml et punished_players.yml.
 *
 * <p>Avec SHNetwork (voir {@link #useSharedLists}), les listes vivent dans la base commune à tous les serveurs : les
 * fichiers ne sont plus écrits et les ensembles en mémoire suivent la base.</p>
 */
public class Storage {

    private final SHVotePunish plugin;

    // Fichiers et configurations
    private File ignoredFile;
    private FileConfiguration ignoredConfig;
    private File punishedFile;
    private FileConfiguration punishedConfig;

    // Utiliser des Sets pour des recherches rapides (O(1) en moyenne).
    // Ensembles sûrs entre fils : en mode réseau, la base et le bus les mettent à jour hors du fil principal.
    private final Set<UUID> ignoredPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> punishedPlayers = ConcurrentHashMap.newKeySet(); // NOUVEAU: Cache pour les joueurs sanctionnés

    /** Base partagée du mode réseau ; null sur un serveur seul. */
    private volatile SharedLists shared;

    public Storage(SHVotePunish plugin) {
        this.plugin = plugin;
        setupFiles();
        load();
    }

    /**
     * Initialise les fichiers de stockage.
     * Crée les fichiers et les dossiers s'ils n'existent pas.
     */
    private void setupFiles() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        // Fichier pour les joueurs ignorés
        ignoredFile = new File(plugin.getDataFolder(), "ignored_players.yml");
        if (!ignoredFile.exists()) {
            try {
                ignoredFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Impossible de créer le fichier ignored_players.yml", e);
            }
        }
        ignoredConfig = YamlConfiguration.loadConfiguration(ignoredFile);

        // NOUVEAU: Fichier pour les joueurs déjà sanctionnés
        punishedFile = new File(plugin.getDataFolder(), "punished_players.yml");
        if (!punishedFile.exists()) {
            try {
                punishedFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Impossible de créer le fichier punished_players.yml", e);
            }
        }
        punishedConfig = YamlConfiguration.loadConfiguration(punishedFile);
    }

    /**
     * Charge toutes les listes depuis les fichiers de configuration.
     */
    public void load() {
        // Charger les joueurs ignorés
        ignoredPlayers.clear();
        List<String> ignoredUuidsAsString = ignoredConfig.getStringList("ignored-players");
        for (String uuidStr : ignoredUuidsAsString) {
            try {
                ignoredPlayers.add(UUID.fromString(uuidStr));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("UUID invalide '" + uuidStr + "' trouvé dans ignored_players.yml.");
            }
        }

        // NOUVEAU: Charger les joueurs sanctionnés
        punishedPlayers.clear();
        List<String> punishedUuidsAsString = punishedConfig.getStringList("punished-players");
        for (String uuidStr : punishedUuidsAsString) {
            try {
                punishedPlayers.add(UUID.fromString(uuidStr));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("UUID invalide '" + uuidStr + "' trouvé dans punished_players.yml.");
            }
        }
    }

    /**
     * Sauvegarde la liste des joueurs ignorés dans le fichier.
     */
    private void saveIgnored() {
        List<String> ignoredUuidsAsString = ignoredPlayers.stream()
            .map(UUID::toString)
            .collect(Collectors.toList());

        ignoredConfig.set("ignored-players", ignoredUuidsAsString);
        try {
            ignoredConfig.save(ignoredFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Impossible de sauvegarder les données dans ignored_players.yml", e);
        }
    }

    /**
     * NOUVEAU: Sauvegarde la liste des joueurs sanctionnés dans le fichier.
     */
    private void savePunished() {
        List<String> punishedUuidsAsString = punishedPlayers.stream()
            .map(UUID::toString)
            .collect(Collectors.toList());

        punishedConfig.set("punished-players", punishedUuidsAsString);
        try {
            punishedConfig.save(punishedFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Impossible de sauvegarder les données dans punished_players.yml", e);
        }
    }

    // --- Gestion des joueurs ignorés ---

    public void addIgnoredPlayer(UUID uuid) {
        addIgnoredPlayer(uuid, null);
    }

    public void addIgnoredPlayer(UUID uuid, String name) {
        if (ignoredPlayers.add(uuid)) {
            SharedLists lists = shared;
            if (lists != null) {
                lists.add(SharedLists.Kind.IGNORED, uuid, name);
            } else {
                saveIgnored();
            }
        }
    }

    public void removeIgnoredPlayer(UUID uuid) {
        if (ignoredPlayers.remove(uuid)) {
            SharedLists lists = shared;
            if (lists != null) {
                lists.remove(SharedLists.Kind.IGNORED, uuid);
            } else {
                saveIgnored();
            }
        }
    }

    public boolean isPlayerIgnored(UUID uuid) {
        return ignoredPlayers.contains(uuid);
    }


    // --- NOUVEAU: Gestion des joueurs sanctionnés ---

    /**
     * Ajoute un joueur à la liste des joueurs déjà sanctionnés et sauvegarde.
     *
     * @param uuid L'UUID du joueur à ajouter.
     */
    public void addPunishedPlayer(UUID uuid) {
        addPunishedPlayer(uuid, null);
    }

    public void addPunishedPlayer(UUID uuid, String name) {
        if (punishedPlayers.add(uuid)) {
            SharedLists lists = shared;
            if (lists != null) {
                lists.add(SharedLists.Kind.PUNISHED, uuid, name);
            } else {
                savePunished();
            }
        }
    }

    /**
     * Retire un joueur de la liste des joueurs sanctionnés (pardon) et sauvegarde.
     *
     * @param uuid L'UUID du joueur à retirer.
     */
    public void removePunishedPlayer(UUID uuid) {
        if (punishedPlayers.remove(uuid)) {
            SharedLists lists = shared;
            if (lists != null) {
                lists.remove(SharedLists.Kind.PUNISHED, uuid);
            } else {
                savePunished();
            }
        }
    }

    /**
     * Vérifie si un joueur a déjà été sanctionné par un vote.
     *
     * @param uuid L'UUID du joueur à vérifier.
     * @return true si le joueur est dans la liste, false sinon.
     */
    public boolean isPlayerPunished(UUID uuid) {
        return punishedPlayers.contains(uuid);
    }

    // --- Mode réseau ---

    /** Passe les listes sur la base commune (ou les rend aux fichiers avec null). */
    public void useSharedLists(SharedLists lists) {
        this.shared = lists;
    }

    /**
     * Mode réseau : décide si ce vote est celui qui sanctionne le joueur. Vrai pour un seul appel sur tout le réseau ;
     * le joueur est alors inscrit dans la liste des sanctionnés, ici et en base.
     */
    public CompletableFuture<Boolean> claimPunished(UUID uuid, String name) {
        SharedLists lists = shared;
        if (lists == null) {
            return CompletableFuture.completedFuture(punishedPlayers.add(uuid));
        }
        return PunishClaim.claim(punishedPlayers, lists, uuid, name);
    }

    /** Mode réseau : applique un changement venu d'un autre serveur, sans le réécrire en base. */
    public void applyShared(SharedLists.Kind kind, UUID uuid, boolean present) {
        Set<UUID> set = set(kind);
        if (present) {
            set.add(uuid);
        } else {
            set.remove(uuid);
        }
    }

    /** Mode réseau : aligne la liste sur la base. Les entrées nouvelles arrivent avant que les autres partent : jamais de liste vide entre deux. */
    public void replaceShared(SharedLists.Kind kind, Set<UUID> fresh) {
        Set<UUID> set = set(kind);
        set.addAll(fresh);
        set.retainAll(fresh);
    }

    /** Contenu actuel du fichier d'une liste, pour l'importer dans la base du réseau. */
    public Set<UUID> readFile(SharedLists.Kind kind) {
        FileConfiguration config = kind == SharedLists.Kind.IGNORED
            ? YamlConfiguration.loadConfiguration(ignoredFile)
            : YamlConfiguration.loadConfiguration(punishedFile);
        Set<UUID> out = new HashSet<>();
        for (String text : config.getStringList(kind == SharedLists.Kind.IGNORED ? "ignored-players" : "punished-players")) {
            try {
                out.add(UUID.fromString(text));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("UUID invalide '" + text + "' ignoré à l'importation.");
            }
        }
        return out;
    }

    private Set<UUID> set(SharedLists.Kind kind) {
        return kind == SharedLists.Kind.IGNORED ? ignoredPlayers : punishedPlayers;
    }
}
