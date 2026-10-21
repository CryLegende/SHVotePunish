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
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Gère le stockage persistant des données du plugin, comme les joueurs ignorés et sanctionnés.
 * Utilise des fichiers dédiés : ignored_players.yml et punished_players.yml.
 */
public class Storage {

    private final SHVotePunish plugin;

    // Fichiers et configurations
    private File ignoredFile;
    private FileConfiguration ignoredConfig;
    private File punishedFile;
    private FileConfiguration punishedConfig;

    // Utiliser des Sets pour des recherches rapides (O(1) en moyenne)
    private final Set<UUID> ignoredPlayers = new HashSet<>();
    private final Set<UUID> punishedPlayers = new HashSet<>(); // NOUVEAU: Cache pour les joueurs sanctionnés

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
        if (ignoredPlayers.add(uuid)) {
            saveIgnored();
        }
    }

    public void removeIgnoredPlayer(UUID uuid) {
        if (ignoredPlayers.remove(uuid)) {
            saveIgnored();
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
        if (punishedPlayers.add(uuid)) {
            savePunished();
        }
    }

    /**
     * Retire un joueur de la liste des joueurs sanctionnés (pardon) et sauvegarde.
     *
     * @param uuid L'UUID du joueur à retirer.
     */
    public void removePunishedPlayer(UUID uuid) {
        if (punishedPlayers.remove(uuid)) {
            savePunished();
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
}
