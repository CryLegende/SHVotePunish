package org.Cry_Legende.sHVotePunish;

import org.Cry_Legende.sHVotePunish.commands.AdminCommand;
import org.Cry_Legende.sHVotePunish.commands.VotePunishCommand;
import org.Cry_Legende.sHVotePunish.data.Storage;
import org.Cry_Legende.sHVotePunish.listeners.InventoryListener;
import org.Cry_Legende.sHVotePunish.listeners.PlayerListener;
import org.Cry_Legende.sHVotePunish.managers.ConfigManager;
import org.Cry_Legende.sHVotePunish.managers.VoteManager;
import org.Cry_Legende.sHVotePunish.network.NetBus;
import org.Cry_Legende.sHVotePunish.network.NetworkVotes;
import org.Cry_Legende.sHVotePunish.network.ShNetworkBus;
import org.Cry_Legende.sHVotePunish.network.VoteDatabase;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
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
    // Section reseau de config.yml au démarrage : elle ne se relit pas à chaud
    private String networkSignature;

    /** Mode réseau (SHNetwork) : null sur un serveur seul. */
    private NetworkVotes networkVotes;

    private static Permission perms = null;

    @Override
    public void onEnable() {
        instance = this;

        // 1. Initialiser les gestionnaires
        this.configManager = new ConfigManager(this);
        configManager.loadConfig();
        this.networkSignature = networkSignature();
        this.storage = new Storage(this);
        this.voteManager = new VoteManager(this);
        this.playerListener = new PlayerListener(this);

        // 2. Essayer de s'accrocher à l'API de permissions de Vault
        if (!setupPermissions()) {
            getLogger().severe("Vault non trouvé ! Le plugin ne peut pas démarrer sans Vault pour gérer les permissions.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("Le hook avec Vault a été établi avec succès.");

        // 3. Enregistrer les commandes et les listeners
        try {
            AdminCommand admin = new AdminCommand(this);
            getCommand("votepunish").setExecutor(new VotePunishCommand(this, admin));
            getCommand("shvotepunish").setExecutor(admin);
            getCommand("shvotepunish").setTabCompleter(admin);
        } catch (NullPointerException e) {
            getLogger().log(Level.SEVERE, "La commande 'votepunish' n'a pas pu être chargée. Vérifiez votre fichier plugin.yml.", e);
        }
        getServer().getPluginManager().registerEvents(new InventoryListener(this), this);
        getServer().getPluginManager().registerEvents(this.playerListener, this);

        // 4. Votes et listes partagés entre les serveurs, si SHNetwork est là
        enableNetwork();

        getLogger().info("SHVotePunish a été activé avec succès !");
    }

    @Override
    public void onDisable() {
        if (this.voteManager != null) {
            this.voteManager.cancelAllVotes();
        }
        if (this.networkVotes != null) {
            this.networkVotes.close();
        }
        getLogger().info("SHVotePunish a été désactivé.");
    }

    /**
     * Passe en mode réseau si SHNetwork est installé et 'reseau.actif' est vrai. Les classes qui touchent à SHNetwork
     * ne sont résolues qu'ici : sans lui, rien n'est chargé et le plugin garde son fonctionnement d'origine.
     */
    private void enableNetwork() {
        FileConfiguration config = getConfig();
        if (!config.getBoolean("reseau.actif", true)) return;
        if (!Bukkit.getPluginManager().isPluginEnabled("SHNetwork")) return;
        VoteDatabase db = null;
        try {
            NetBus bus = ShNetworkBus.open();
            db = new VoteDatabase(
                config.getString("reseau.base-de-donnees.url"),
                config.getString("reseau.base-de-donnees.utilisateur"),
                config.getString("reseau.base-de-donnees.mot-de-passe"),
                Math.max(250, config.getInt("reseau.base-de-donnees.delai-ms", 3000)),
                config.getString("reseau.joueurs-connus.table", "Global.sh_track_firstseen"));
            NetworkVotes net = new NetworkVotes(this, bus, db,
                config.getString("reseau.canal", "votepunish"),
                Math.max(100, config.getLong("reseau.delai-reponse-ms", 2000L)),
                Math.max(1, config.getLong("reseau.absence-max-secondes", 4L)) * 1000L);
            networkVotes = net;
            voteManager.attachNetwork(net);
            net.start(config.getLong("reseau.rechargement-listes-secondes", 120L));
            getLogger().info("SHNetwork détecté : un vote par joueur visé pour tout le réseau, listes en base.");
        } catch (RuntimeException | LinkageError e) {
            networkVotes = null;
            voteManager.attachNetwork(null);
            storage.useSharedLists(null);
            if (db != null) db.close();
            getLogger().log(Level.SEVERE, "SHNetwork présent mais inutilisable, votes locaux à ce serveur : " + e);
        }
    }

    private String networkSignature() {
        return String.valueOf(getConfig().getConfigurationSection("reseau") == null
            ? "" : getConfig().getConfigurationSection("reseau").getValues(true));
    }

    /**
     * /shvotepunish reload : relit config.yml et messages.yml. Un fichier illisible ne change rien. Les votes en cours,
     * les listes et la base ne sont pas touchés.
     *
     * @return null si tout est rechargé, sinon {fichier, erreur}
     */
    public String[] reloadAll() {
        String[] error = configManager.reload();
        if (error == null) {
            playerListener.refreshLimit();
        }
        return error;
    }

    /** Vrai si la section reseau de config.yml n'est plus celle du démarrage (elle demande un redémarrage). */
    public boolean networkSettingsChanged() {
        return !networkSignature.equals(networkSignature());
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

    /** Vote en réseau avec SHNetwork, ou null si le plugin tourne seul sur ce serveur. */
    public NetworkVotes getNetworkVotes() {
        return networkVotes;
    }
}
