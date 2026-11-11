package org.Cry_Legende.sHVotePunish.commands;

import org.Cry_Legende.sHVotePunish.SHVotePunish;
import org.Cry_Legende.sHVotePunish.data.Storage;
import org.Cry_Legende.sHVotePunish.managers.ConfigManager;
import org.Cry_Legende.sHVotePunish.managers.VoteManager;
import org.Cry_Legende.sHVotePunish.network.NetworkVotes;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * /shvotepunish ignore|unignore|unpunish|import|reload|list|info : l'administration du plugin. Chaque sous-commande a
 * sa permission (le nom de permissions.admin du config.yml, suivi de la sous-commande ; le nom seul les donne toutes).
 * Les anciennes formes /votepunish ignore|unignore|unpunish|import passent par {@link #run}.
 */
public class AdminCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS =
        List.of("ignore", "unignore", "unpunish", "import", "reload", "list", "info");
    private static final int PAGE_SIZE = 15;

    private final SHVotePunish plugin;

    public AdminCommand(SHVotePunish plugin) {
        this.plugin = plugin;
    }

    private ConfigManager config() {
        return plugin.getConfigManager();
    }

    /** Vrai si l'expéditeur a le droit d'utiliser cette sous-commande (la console les a toutes). */
    public boolean can(CommandSender sender, String subCommand) {
        String admin = config().getAdminPermission();
        if (sender instanceof Player player) {
            return plugin.hasPermission(player, admin + "." + subCommand) || plugin.hasPermission(player, admin);
        }
        return sender.hasPermission(admin + "." + subCommand) || sender.hasPermission(admin);
    }

    private List<String> allowed(CommandSender sender) {
        return SUBCOMMANDS.stream().filter(sub -> can(sender, sub)).toList();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        List<String> allowed = allowed(sender);
        if (allowed.isEmpty()) {
            sender.sendMessage(config().getNoPermissionMessage());
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (!allowed.contains(sub)) {
            sender.sendMessage(config().get("admin_usage", "subcommands", String.join("|", allowed)));
            return true;
        }
        run(sender, sub, args);
        return true;
    }

    /** Exécute la sous-commande (déjà autorisée) ; {@code args[0]} est son nom. */
    public void run(CommandSender sender, String sub, String[] args) {
        switch (sub) {
            case "ignore", "unignore", "unpunish" -> {
                if (args.length < 2) {
                    sender.sendMessage(config().get("admin_usage_target", "subcommand", sub));
                    return;
                }
                resolvePlayer(args[1], (uuid, name) -> {
                    if (sender instanceof Player player && !player.isOnline()) return;
                    if (uuid == null) {
                        sender.sendMessage(plugin.getNetworkVotes() != null
                            ? config().getUnknownPlayerNetworkMessage().replace("{player}", args[1])
                            : config().get("player_never_seen", "player", args[1]));
                        return;
                    }
                    apply(sender, sub, uuid, name);
                });
            }
            case "import" -> {
                NetworkVotes network = plugin.getNetworkVotes();
                if (network == null) {
                    sender.sendMessage(config().getImportUnavailableMessage());
                } else {
                    network.importLists(sender);
                }
            }
            case "reload" -> reload(sender);
            case "list" -> list(sender, args);
            case "info" -> {
                if (args.length < 2) {
                    sender.sendMessage(config().get("admin_usage_target", "subcommand", sub));
                    return;
                }
                resolvePlayer(args[1], (uuid, name) -> {
                    if (sender instanceof Player player && !player.isOnline()) return;
                    if (uuid == null) {
                        sender.sendMessage(config().get("player_never_seen", "player", args[1]));
                        return;
                    }
                    info(sender, uuid, name);
                });
            }
            default -> sender.sendMessage(config().get("admin_usage", "subcommands", String.join("|", allowed(sender))));
        }
    }

    private void apply(CommandSender sender, String sub, UUID uuid, String name) {
        Storage storage = plugin.getStorage();
        ConfigManager config = config();
        switch (sub) {
            case "ignore" -> {
                storage.addIgnoredPlayer(uuid, name);
                sender.sendMessage(config.getAdminIgnoreAddMessage().replace("{player}", name));
            }
            case "unignore" -> {
                storage.removeIgnoredPlayer(uuid);
                sender.sendMessage(config.getAdminIgnoreRemoveMessage().replace("{player}", name));
            }
            case "unpunish" -> {
                storage.removePunishedPlayer(uuid);
                sender.sendMessage(config.getAdminUnpunishMessage().replace("{player}", name));
            }
            default -> {
            }
        }
    }

    private void reload(CommandSender sender) {
        String[] error = plugin.reloadAll();
        if (error != null) {
            plugin.getLogger().warning(error[0] + " illisible, configuration inchangée : " + error[1]);
            sender.sendMessage(config().get("reload_error", "file", error[0], "error", error[1]));
            return;
        }
        sender.sendMessage(config().get("reload_done"));
        if (plugin.networkSettingsChanged()) {
            sender.sendMessage(config().get("reload_restart"));
        }
    }

    private void list(CommandSender sender, String[] args) {
        String kind = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
        if (!kind.equals("ignored") && !kind.equals("punished")) {
            sender.sendMessage(config().get("list_usage"));
            return;
        }
        List<UUID> uuids = new ArrayList<>(kind.equals("ignored")
            ? plugin.getStorage().getIgnoredPlayers() : plugin.getStorage().getPunishedPlayers());
        if (uuids.isEmpty()) {
            sender.sendMessage(config().get("list_empty", "list", kind));
            return;
        }
        // Noms d'après l'UUID (jamais d'après un pseudo) ; l'UUID est affiché quand le nom est inconnu
        List<String[]> rows = new ArrayList<>();
        for (UUID uuid : uuids) {
            String name = Bukkit.getOfflinePlayer(uuid).getName();
            rows.add(new String[]{name == null ? "?" : name, uuid.toString()});
        }
        rows.sort((a, b) -> a[0].compareToIgnoreCase(b[0]));
        int pages = (rows.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int page = 1;
        if (args.length >= 3) {
            try {
                page = Math.min(Math.max(1, Integer.parseInt(args[2])), pages);
            } catch (NumberFormatException ignored) {
                page = 1;
            }
        }
        sender.sendMessage(config().get("list_header", "list", kind, "count", String.valueOf(rows.size()),
            "page", String.valueOf(page), "pages", String.valueOf(pages)));
        for (String[] row : rows.subList((page - 1) * PAGE_SIZE, Math.min(rows.size(), page * PAGE_SIZE))) {
            sender.sendMessage(config().get("list_entry", "name", row[0], "uuid", row[1]));
        }
    }

    private void info(CommandSender sender, UUID uuid, String name) {
        ConfigManager config = config();
        Storage storage = plugin.getStorage();
        String yes = config.text("info_yes");
        String no = config.text("info_no");
        boolean blacklisted = config.getBlacklistedPlayers().stream().anyMatch(entry -> entry.equalsIgnoreCase(name));
        sender.sendMessage(config.get("info_header", "player", name));
        sender.sendMessage(config.get("info_ignored", "value", storage.isPlayerIgnored(uuid) ? yes : no));
        sender.sendMessage(config.get("info_punished", "value", storage.isPlayerPunished(uuid) ? yes : no));
        sender.sendMessage(config.get("info_blacklisted", "value", blacklisted ? yes : no));
        VoteManager.ActiveVote vote = plugin.getVoteManager().getActiveVote(uuid);
        if (vote == null) {
            sender.sendMessage(config.get("info_no_vote"));
        } else {
            sender.sendMessage(config.get("info_vote", "votes", String.valueOf(vote.getCurrentVotes()),
                "required", String.valueOf(vote.getRequiredVotes())));
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online == null) {
            sender.sendMessage(config.get("info_offline"));
            return;
        }
        // Les mêmes tests que /votepunish : lancer ou voter, puis être visé
        sender.sendMessage(config.get("info_permission", "node", config.getUsePermission(),
            "value", plugin.hasPermission(online, config.getUsePermission()) ? yes : no));
        sender.sendMessage(config.get("info_permission", "node", config.getBypassPermission(),
            "value", plugin.hasPermission(online, config.getBypassPermission()) ? yes : no));
        if (!config.getEligibilityBypassPermission().equals(config.getBypassPermission())) {
            sender.sendMessage(config.get("info_permission", "node", config.getEligibilityBypassPermission(),
                "value", plugin.hasPermission(online, config.getEligibilityBypassPermission()) ? yes : no));
        }
    }

    /**
     * Retrouve un joueur par son pseudo pour les commandes d'administration, sans jamais interroger Mojang : connecté,
     * sinon connu du cache de profils (seul son nom est gardé, l'UUID est celui « hors ligne » de tout le réseau). Avec
     * SHNetwork, la recherche passe par tout le réseau. Le rappel reçoit {@code (null, null)} s'il est inconnu.
     */
    public void resolvePlayer(String name, BiConsumer<UUID, String> callback) {
        NetworkVotes network = plugin.getNetworkVotes();
        if (network != null) {
            network.resolvePlayer(name, callback);
            return;
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            callback.accept(online.getUniqueId(), online.getName());
            return;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null && cached.getName() != null) {
            callback.accept(offlineUuid(cached.getName()), cached.getName());
            return;
        }
        callback.accept(null, null);
    }

    /** UUID « hors ligne » d'un pseudo : celui sous lequel chaque serveur du réseau enregistre le joueur. */
    private static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> allowed = allowed(sender);
        if (args.length == 1) {
            String typed = args[0].toLowerCase(Locale.ROOT);
            return allowed.stream().filter(sub -> sub.startsWith(typed)).toList();
        }
        if (args.length == 2 && allowed.contains(args[0].toLowerCase(Locale.ROOT))) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            String typed = args[1].toLowerCase(Locale.ROOT);
            if (sub.equals("list")) {
                return List.of("ignored", "punished").stream().filter(kind -> kind.startsWith(typed)).toList();
            }
            if (sub.equals("ignore") || sub.equals("unignore") || sub.equals("unpunish") || sub.equals("info")) {
                List<String> names = new ArrayList<>();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getName().toLowerCase(Locale.ROOT).startsWith(typed)) {
                        names.add(player.getName());
                    }
                }
                return names;
            }
        }
        return List.of();
    }
}
