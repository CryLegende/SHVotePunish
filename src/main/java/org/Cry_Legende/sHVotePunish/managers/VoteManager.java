package org.Cry_Legende.sHVotePunish.managers;

import org.Cry_Legende.sHVotePunish.SHVotePunish;
import org.Cry_Legende.sHVotePunish.utils.DiscordWebhook;
import org.Cry_Legende.sHVotePunish.utils.VoteLogger;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class VoteManager {

    private final SHVotePunish plugin;
    private final Map<UUID, ActiveVote> activeVotes = new ConcurrentHashMap<>();

    public VoteManager(SHVotePunish plugin) {
        this.plugin = plugin;
    }

    public void startVote(Player initiator, Player target) {
        if (isVoteInProgress(target.getUniqueId())) return;

        int required = calculateRequiredVotes();
        ActiveVote vote = new ActiveVote(initiator, target, required);

        String broadcastMessage = plugin.getConfigManager().getVoteBroadcastMessage()
            .replace("{initiator}", initiator.getName())
            .replace("{target}", target.getName());
        plugin.getServer().broadcastMessage(broadcastMessage);

        String logMessage = plugin.getConfigManager().getLogVoteStart()
            .replace("{initiator}", initiator.getName())
            .replace("{target}", target.getName());
        plugin.getVoteLogger().log(logMessage); // Utilise le logger de fichier

        BukkitTask expirationTask = new BukkitRunnable() {
            @Override
            public void run() {
                ActiveVote currentVote = activeVotes.get(target.getUniqueId());
                if (currentVote != null && currentVote.equals(vote)) {
                    endVote(currentVote, false);
                }
            }
        }.runTaskLater(plugin, plugin.getConfigManager().getVoteDuration() * 20L);

        vote.setExpirationTask(expirationTask);
        activeVotes.put(target.getUniqueId(), vote);
    }

    public void addVote(Player voter, Player target) {
        ActiveVote vote = activeVotes.get(target.getUniqueId());
        if (vote == null || vote.hasVoted(voter.getUniqueId())) return;

        vote.addVoter(voter.getUniqueId());
        updateGUIsForVote(vote);

        String logMessage = plugin.getConfigManager().getLogPlayerVote()
            .replace("{voter}", voter.getName())
            .replace("{target}", target.getName());
        plugin.getVoteLogger().log(logMessage); // Utilise le logger de fichier

        if (vote.isSuccessful()) {
            endVote(vote, true);
        }
    }

    public void endVote(ActiveVote vote, boolean success) {
        activeVotes.remove(vote.getTargetUUID());
        if (vote.getExpirationTask() != null) vote.getExpirationTask().cancel();

        ConfigManager cfg = plugin.getConfigManager();

        // Log amélioré pour inclure la liste des votants
        String punishers = vote.getVoters().stream()
            .map(uuid -> Bukkit.getOfflinePlayer(uuid).getName())
            .filter(Objects::nonNull)
            .collect(Collectors.joining(", "));

        String logMessage = cfg.getLogVoteEnd()
            .replace("{target}", vote.getTargetName())
            .replace("{outcome}", success ? "SUCCESS" : "FAILURE")
            .replace("{current}", String.valueOf(vote.getCurrentVotes()))
            .replace("{required}", String.valueOf(vote.getRequiredVotes()))
            + " | Votants: [" + punishers + "]";
        plugin.getVoteLogger().log(logMessage); // Utilise le logger de fichier

        if (success) {
            plugin.getServer().broadcastMessage(cfg.getVoteSuccessMessage().replace("{player}", vote.getTargetName()));

            // NOUVEAU: Ajoute le joueur à la liste des joueurs déjà sanctionnés
            plugin.getStorage().addPunishedPlayer(vote.getTargetUUID());

            String command = cfg.getCommandOnSuccess().replace("{player}", vote.getTargetName()).replace("{uuid}", vote.getTargetUUID().toString());
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));

            if (cfg.isWebhookEnabled()) {
                try {
                    sendDiscordWebhook(vote);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Une erreur critique est survenue lors de la préparation du webhook Discord. La sanction a quand même été appliquée.", e);
                }
            }
        } else {
            plugin.getServer().broadcastMessage(cfg.getVoteFailMessage().replace("{player}", vote.getTargetName()));
        }

        vote.getViewers().stream()
            .map(Bukkit::getPlayer)
            .filter(Objects::nonNull)
            .forEach(Player::closeInventory);
    }

    public void updateGUIsForVote(ActiveVote vote) {
        GUIManager guiManager = new GUIManager(plugin);
        for (UUID viewerUUID : vote.getViewers()) {
            Player viewer = Bukkit.getPlayer(viewerUUID);
            if (viewer != null && viewer.getOpenInventory().getTopInventory().getHolder() instanceof VoteGUIHolder) {
                guiManager.updateVoteMenu(viewer, viewer.getOpenInventory().getTopInventory(), vote);
            }
        }
    }

    private void sendDiscordWebhook(ActiveVote vote) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            ConfigManager cfg = plugin.getConfigManager();
            if (cfg.getWebhookUrl() == null || cfg.getWebhookUrl().isEmpty() || cfg.getWebhookUrl().contains("VOTRE_URL")) {
                plugin.getLogger().warning("L'URL du Webhook Discord n'est pas configurée. Notification non envoyée.");
                return;
            }

            try {
                JSONObject payload = new JSONObject();
                payload.put("username", Optional.ofNullable(cfg.getWebhookUsername()).orElse("Alerte Plugin"));
                payload.put("avatar_url", Optional.ofNullable(cfg.getWebhookAvatarUrl()).orElse("").replace("{player}", vote.getTargetName()));

                JSONObject embed = new JSONObject();
                embed.put("title", Optional.ofNullable(cfg.getWebhookEmbedTitle()).orElse("Rapport de Sanction"));
                embed.put("color", cfg.getWebhookEmbedColor() != 0 ? cfg.getWebhookEmbedColor() : 15158332);

                OfflinePlayer target = Bukkit.getOfflinePlayer(vote.getTargetUUID());
                String firstJoin = new SimpleDateFormat("dd/MM/yyyy HH:mm").format(new Date(target.getFirstPlayed()));
                String punishers = vote.getVoters().stream().map(uuid -> Bukkit.getOfflinePlayer(uuid).getName()).filter(Objects::nonNull).collect(Collectors.joining(", "));
                if (punishers.isEmpty()) punishers = "N/A";

                String finalDescription = Optional.ofNullable(cfg.getWebhookEmbedDescription())
                    .orElse("**Joueurs ayant voté:**\n{punisher_list}")
                    .replace("{punisher_list}", punishers);
                embed.put("description", finalDescription);

                String lastMessages = plugin.getPlayerListener().getFormattedRecentMessages(vote.getTargetUUID());
                if (lastMessages.length() > 1020) lastMessages = lastMessages.substring(0, 1020) + "...";
                if (lastMessages.isEmpty()) lastMessages = "Aucun message récent.";

                JSONArray fields = new JSONArray();
                fields.put(new JSONObject().put("name", cfg.getFieldTitlePlayer()).put("value", "`" + vote.getTargetName() + "`").put("inline", true));
                fields.put(new JSONObject().put("name", cfg.getFieldTitleFirstJoin()).put("value", firstJoin).put("inline", true));
                fields.put(new JSONObject().put("name", cfg.getFieldTitleLastMessages()).put("value", lastMessages).put("inline", false));
                embed.put("fields", fields);

                JSONObject footer = new JSONObject();
                footer.put("text", Optional.ofNullable(cfg.getWebhookEmbedFooter()).orElse("SHVotePunish • {current_time}").replace("{current_time}", new SimpleDateFormat("HH:mm:ss").format(new Date())));
                embed.put("footer", footer);

                payload.put("embeds", new JSONArray().put(embed));

                DiscordWebhook webhook = new DiscordWebhook(cfg.getWebhookUrl());
                webhook.setContent(payload.toString());
                webhook.execute();

            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Erreur lors de l'envoi du Webhook Discord (tâche asynchrone)", e);
            }
        });
    }

    private int calculateRequiredVotes() {
        ConfigManager cfg = plugin.getConfigManager();
        if (cfg.getRequiredVotesType().equalsIgnoreCase("FIXED")) {
            return cfg.getRequiredVotesValue();
        } else {
            long onlinePlayers = Bukkit.getOnlinePlayers().size();
            return (int) Math.ceil(onlinePlayers * (double) cfg.getRequiredVotesValue() / 100.0);
        }
    }

    public void cancelAllVotes() {
        activeVotes.values().forEach(vote -> {
            if (vote.getExpirationTask() != null) vote.getExpirationTask().cancel();
        });
        activeVotes.clear();
        plugin.getLogger().info("Tous les votes en cours ont été annulés.");
    }

    public boolean isVoteInProgress(UUID targetUUID) {
        return activeVotes.containsKey(targetUUID);
    }

    public ActiveVote getActiveVote(UUID targetUUID) {
        return activeVotes.get(targetUUID);
    }

    public Map<UUID, ActiveVote> getActiveVotes() {
        return activeVotes;
    }

    public static class ActiveVote {
        private final UUID initiatorUUID;
        private final UUID targetUUID;
        private final String targetName;
        private final int requiredVotes;
        private final Set<UUID> voters = new HashSet<>();
        private final Set<UUID> viewers = new HashSet<>();
        private BukkitTask expirationTask;

        public ActiveVote(Player initiator, Player target, int requiredVotes) {
            this.initiatorUUID = initiator.getUniqueId();
            this.targetUUID = target.getUniqueId();
            this.targetName = target.getName();
            this.requiredVotes = requiredVotes;
            this.voters.add(initiatorUUID);
        }

        public void addVoter(UUID voterUUID) {
            this.voters.add(voterUUID);
        }

        public boolean hasVoted(UUID voterUUID) {
            return this.voters.contains(voterUUID);
        }

        public boolean isSuccessful() {
            return voters.size() >= requiredVotes;
        }

        public void addViewer(UUID viewerUUID) {
            this.viewers.add(viewerUUID);
        }

        public void removeViewer(UUID viewerUUID) {
            this.viewers.remove(viewerUUID);
        }

        public Set<UUID> getViewers() {
            return new HashSet<>(this.viewers);
        }

        public UUID getTargetUUID() {
            return targetUUID;
        }

        public String getTargetName() {
            return targetName;
        }

        public int getCurrentVotes() {
            return voters.size();
        }

        public int getRequiredVotes() {
            return requiredVotes;
        }

        public Set<UUID> getVoters() {
            return new HashSet<>(voters);
        }

        public BukkitTask getExpirationTask() {
            return expirationTask;
        }

        public void setExpirationTask(BukkitTask expirationTask) {
            this.expirationTask = expirationTask;
        }
    }
}
