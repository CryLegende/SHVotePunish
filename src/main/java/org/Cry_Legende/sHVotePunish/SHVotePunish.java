package org.Cry_Legende.sHVotePunish;

import org.Cry_Legende.sHVotePunish.commands.VotePunishCommand;
import org.Cry_Legende.sHVotePunish.data.Storage;
import org.Cry_Legende.sHVotePunish.listeners.InventoryListener;
import org.Cry_Legende.sHVotePunish.listeners.PlayerListener;
import org.Cry_Legende.sHVotePunish.managers.ConfigManager;
import org.Cry_Legende.sHVotePunish.managers.VoteManager;
import org.Cry_Legende.sHVotePunish.utils.VoteLogger;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * Classe principale du plugin SHVotePunish.
 * Gère l'initialisation et la désactivation des modules, le hook avec Vault et le logger de vote.
 */
public final class SHVotePunish extends JavaPlugin {

    private static SHVotePunish instance;
    private ConfigManager configManager;
    private VoteManager voteManager;
    private Storage storage;
    private PlayerListener playerListener;
    private VoteLogger voteLogger; // Variable pour le nouveau logger

    private static Permission perms = null;

    @Override
    public void onEnable() {
        instance = this;

        // 1. Initialiser les gestionnaires
        this.configManager = new ConfigManager(this);
        configManager.loadConfig();
        this.storage = new Storage(this);
        this.voteManager = new VoteManager(this);
        this.playerListener = new PlayerListener(this);
        this.voteLogger = new VoteLogger(this); // Initialisation du logger de vote

        // 2. Essayer de s'accrocher à l'API de permissions de Vault
        if (!setupPermissions()) {
            getLogger().severe("Vault non trouvé ! Le plugin ne peut pas démarrer sans Vault pour gérer les permissions.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("Le hook avec Vault a été établi avec succès.");

        // 3. Enregistrer les commandes et les listeners
        try {
            getCommand("votepunish").setExecutor(new VotePunishCommand(this));
        } catch (NullPointerException e) {
            getLogger().log(Level.SEVERE, "La commande 'votepunish' n'a pas pu être chargée. Vérifiez votre fichier plugin.yml.", e);
        }
        getServer().getPluginManager().registerEvents(new InventoryListener(this), this);
        getServer().getPluginManager().registerEvents(this.playerListener, this);

        getLogger().info("SHVotePunish a été activé avec succès !");
    }

    @Override
    public void onDisable() {
        if (this.voteManager != null) {
            this.voteManager.cancelAllVotes();
        }
        getLogger().info("SHVotePunish a été désactivé.");
    }

    private boolean setupPermissions() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Permission> rsp = getServer().getServicesManager().getRegistration(Permission.class);
        if (rsp == null) {
            return false;
        }
        perms = rsp.getProvider();
        return perms != null;
    }

    /**
     * Vérifie si un joueur a une permission en utilisant Vault.
     *
     * @param player         Le joueur à vérifier.
     * @param permissionNode La permission à vérifier.
     * @return true si le joueur a la permission.
     */
    public boolean hasPermission(Player player, String permissionNode) {
        if (perms == null) {
            getLogger().warning("Le hook avec Vault a été perdu. Vérification des permissions via Bukkit en fallback.");
            return player.hasPermission(permissionNode);
        }
        return perms.has(player, permissionNode);
    }

    // --- Getters ---

    public static SHVotePunish getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public VoteManager getVoteManager() {
        return voteManager;
    }

    public Storage getStorage() {
        return storage;
    }

    public PlayerListener getPlayerListener() {
        return playerListener;
    }

    public VoteLogger getVoteLogger() {
        return voteLogger;
    } // Getter pour le nouveau logger
}
