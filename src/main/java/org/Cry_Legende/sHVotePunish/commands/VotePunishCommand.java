package org.Cry_Legende.sHVotePunish.commands;

import org.Cry_Legende.sHVotePunish.SHVotePunish;
import org.Cry_Legende.sHVotePunish.data.Storage;
import org.Cry_Legende.sHVotePunish.managers.ConfigManager;
import org.Cry_Legende.sHVotePunish.managers.GUIManager;
import org.Cry_Legende.sHVotePunish.managers.VoteManager;
import org.Cry_Legende.sHVotePunish.network.NetworkVotes;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * Gère la commande /votepunish et son alias : lancer un vote contre un joueur, ou voter. L'administration est dans
 * /shvotepunish ; les anciennes formes /votepunish ignore|unignore|unpunish|import y mènent toujours.
 * Utilise la méthode centralisée hasPermission() du plugin pour toutes les vérifications.
 */
public class VotePunishCommand implements CommandExecutor {

    private final SHVotePunish plugin;
    private final ConfigManager configManager;
    private final VoteManager voteManager;
    private final GUIManager guiManager;
    private final Storage storage;
    private final AdminCommand admin;

    public VotePunishCommand(SHVotePunish plugin, AdminCommand admin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.voteManager = plugin.getVoteManager();
        this.storage = plugin.getStorage();
        this.guiManager = new GUIManager(plugin);
        this.admin = admin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(configManager.get("player_only"));
            return true;
        }

        Player player = (Player) sender;

        if (args.length == 0) {
            player.sendMessage(configManager.get("usage_player"));
            return true;
        }

        // --- Anciennes formes d'administration : /votepunish ignore|unignore|unpunish <joueur> ---
        String first = args[0].toLowerCase(Locale.ROOT);
        if (first.equals("ignore") || first.equals("unignore") || first.equals("unpunish")) {
            if (!admin.can(player, first)) {
                player.sendMessage(configManager.getNoPermissionMessage());
                return true;
            }
            admin.run(player, first, args);
            return true;
        }

        // "import" est réservé à l'administration ; pour les autres joueurs c'est un pseudo comme un autre
        if (first.equals("import") && admin.can(player, "import")) {
            admin.run(player, "import", args);
            return true;
        }

        // --- Processus principal ---
        if (!plugin.hasPermission(player, configManager.getUsePermission())) {
            player.sendMessage(configManager.getNoPermissionMessage());
            return true;
        }

        if (storage.isPlayerIgnored(player.getUniqueId())) {
            player.sendMessage(configManager.get("not_allowed_to_vote"));
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
                player.sendMessage(configManager.get("vote_not_found"));
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

        // Vérifier si le joueur a déjà été sanctionné par un vote
        if (storage.isPlayerPunished(target.getUniqueId())) {
            player.sendMessage(configManager.getPlayerAlreadyPunishedMessage());
            return true;
        }

        // Ouvre le menu de CONFIRMATION pour lancer un NOUVEAU vote.
        player.sendMessage(configManager.getVoteStartConfirmationMessage().replace("{player}", target.getName()));
        guiManager.openConfirmationMenu(player, target);

        return true;
    }
}
