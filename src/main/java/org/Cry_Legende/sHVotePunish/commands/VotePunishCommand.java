package org.Cry_Legende.sHVotePunish.commands;

import org.Cry_Legende.sHVotePunish.SHVotePunish;
import org.Cry_Legende.sHVotePunish.data.Storage;
import org.Cry_Legende.sHVotePunish.managers.ConfigManager;
import org.Cry_Legende.sHVotePunish.managers.GUIManager;
import org.Cry_Legende.sHVotePunish.managers.VoteManager;
import org.Cry_Legende.sHVotePunish.network.NetworkVotes;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Gère la commande /votepunish, ses alias et ses sous-commandes d'administration.
 * Utilise la méthode centralisée hasPermission() du plugin pour toutes les vérifications.
 */
public class VotePunishCommand implements CommandExecutor {

    private final SHVotePunish plugin;
    private final ConfigManager configManager;
    private final VoteManager voteManager;
    private final GUIManager guiManager;
    private final Storage storage;

    public VotePunishCommand(SHVotePunish plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.voteManager = plugin.getVoteManager();
        this.storage = plugin.getStorage();
        this.guiManager = new GUIManager(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Cette commande doit être exécutée par un joueur.");
            return true;
        }

        Player player = (Player) sender;

        if (args.length == 0) {
            player.sendMessage(configManager.getPrefix() + "§cUtilisation : /" + label + " <joueur>");
            return true;
        }

        // --- Gestion des sous-commandes d'administration ---
        // MODIFIÉ: Ajout de "unpunish"
        if (args[0].equalsIgnoreCase("ignore") || args[0].equalsIgnoreCase("unignore") || args[0].equalsIgnoreCase("unpunish")) {
            if (!plugin.hasPermission(player, configManager.getAdminPermission())) {
                player.sendMessage(configManager.getNoPermissionMessage());
                return true;
            }
            handleAdminCommands(player, args, label);
            return true;
        }

        // "import" est réservé à l'administration ; pour les autres joueurs c'est un pseudo comme un autre
        if (args[0].equalsIgnoreCase("import") && plugin.hasPermission(player, configManager.getAdminPermission())) {
            NetworkVotes network = plugin.getNetworkVotes();
            if (network == null) {
                player.sendMessage(configManager.getImportUnavailableMessage());
            } else {
                network.importLists(player);
            }
            return true;
        }

        // --- Processus principal ---
        if (!plugin.hasPermission(player, configManager.getUsePermission())) {
            player.sendMessage(configManager.getNoPermissionMessage());
            return true;
        }

        if (storage.isPlayerIgnored(player.getUniqueId())) {
            player.sendMessage(configManager.getPrefix() + "§cVous n'êtes pas autorisé à participer aux votes.");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            // Avec SHNetwork, le joueur peut être sur un autre serveur : le serveur qui le porte vérifie et répond
            NetworkVotes network = plugin.getNetworkVotes();
            if (network != null && network.handleRemote(player, args[0])) {
                return true;
            }
            player.sendMessage(configManager.getPlayerNotFoundMessage());
            return true;
        }

        // --- CORRECTION DE LA LOGIQUE ---
        // 5. Vérifier si un vote est déjà en cours
        if (voteManager.isVoteInProgress(target.getUniqueId())) {
            // Un vote est en cours, on ouvre le menu de participation pour le joueur.
            VoteManager.ActiveVote existingVote = voteManager.getActiveVote(target.getUniqueId());

            if (existingVote == null) {
                player.sendMessage(configManager.getPrefix() + "§cErreur: Le vote en cours n'a pas pu être trouvé. Il a peut-être expiré.");
                return true;
            }

            // Ouvre le menu de VOTE (et non de confirmation)
            guiManager.openVoteMenu(player, existingVote);
            return true; // L'action est terminée.
        }

        // Si on arrive ici, aucun vote n'est en cours contre la cible. On lance le processus de création.

        if (target.equals(player)) {
            player.sendMessage(configManager.getCannotPunishSelfMessage());
            return true;
        }

        if (plugin.hasPermission(target, configManager.getBypassPermission()) || plugin.hasPermission(target, configManager.getEligibilityBypassPermission())) {
            player.sendMessage(configManager.getPlayerHasBypassMessage());
            return true;
        }

        if (configManager.getBlacklistedPlayers().stream().anyMatch(name -> name.equalsIgnoreCase(target.getName()))) {
            player.sendMessage(configManager.getPlayerHasBypassMessage());
            return true;
        }

        // NOUVEAU: Vérifier si le joueur a déjà été sanctionné par un vote
        if (storage.isPlayerPunished(target.getUniqueId())) {
            player.sendMessage(configManager.getPlayerAlreadyPunishedMessage());
            return true;
        }

        // Ouvre le menu de CONFIRMATION pour lancer un NOUVEAU vote.
        player.sendMessage(configManager.getVoteStartConfirmationMessage().replace("{player}", target.getName()));
        guiManager.openConfirmationMenu(player, target);

        return true;
    }

    // MODIFIÉ: Renommée et étendue pour gérer toutes les commandes admin
    private void handleAdminCommands(Player player, String[] args, String label) {
        if (args.length < 2) {
            player.sendMessage(configManager.getPrefix() + "§cUtilisation : /" + label + " " + args[0] + " <joueur>");
            return;
        }

        String subCommand = args[0].toLowerCase();

        NetworkVotes network = plugin.getNetworkVotes();
        if (network != null) {
            // Avec SHNetwork, le joueur est cherché sur tout le réseau, sans interroger Mojang ni les fichiers de ce serveur
            network.resolvePlayer(args[1], (uuid, name) -> {
                if (!player.isOnline()) return;
                if (uuid == null) {
                    player.sendMessage(configManager.getUnknownPlayerNetworkMessage().replace("{player}", args[1]));
                    return;
                }
                applyAdminCommand(player, subCommand, uuid, name);
            });
            return;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            player.sendMessage(configManager.getPrefix() + "§cLe joueur '" + args[1] + "' n'a jamais été vu sur ce serveur.");
            return;
        }

        applyAdminCommand(player, subCommand, target.getUniqueId(), target.getName());
    }

    private void applyAdminCommand(Player player, String subCommand, UUID targetUUID, String targetName) {
        switch (subCommand) {
            case "ignore":
                storage.addIgnoredPlayer(targetUUID, targetName);
                player.sendMessage(configManager.getAdminIgnoreAddMessage().replace("{player}", targetName));
                break;
            case "unignore":
                storage.removeIgnoredPlayer(targetUUID);
                player.sendMessage(configManager.getAdminIgnoreRemoveMessage().replace("{player}", targetName));
                break;
            case "unpunish": // NOUVEAU
                storage.removePunishedPlayer(targetUUID);
                player.sendMessage(configManager.getAdminUnpunishMessage().replace("{player}", targetName));
                break;
        }
    }
}
