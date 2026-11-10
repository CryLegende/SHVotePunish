package org.Cry_Legende.sHVotePunish.managers;

import org.Cry_Legende.sHVotePunish.SHVotePunish;
import org.Cry_Legende.sHVotePunish.network.NetworkVotes;
import org.Cry_Legende.sHVotePunish.utils.DiscordWebhook;
import org.Cry_Legende.sHVotePunish.utils.ActionLog;
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

    /** Issue d'un vote. LEFT : la cible a quitté le réseau avant la fin (mode réseau seulement). */
    public enum Outcome {
        SUCCESS, FAIL, LEFT
    }

    private static final String LOG_FILE = "votes";

    private final SHVotePunish plugin;
    private final Map<UUID, ActiveVote> activeVotes = new ConcurrentHashMap<>();

    /** Mode réseau : null sur un serveur seul, où rien de ce qui touche à SHNetwork n'est appelé. */
    private volatile NetworkVotes network;

    public VoteManager(SHVotePunish plugin) {
        this.plugin = plugin;
    }

    public void attachNetwork(NetworkVotes network) {
        this.network = network;
    }

    public void startVote(Player initiator, Player target) {
        startVote(initiator.getUniqueId(), initiator.getName(), target);
    }

    /**
     * Lance le vote contre un joueur de ce serveur. En mode réseau, l'initiateur peut être sur un autre serveur : seul
     * son nom et son UUID comptent. Le serveur de la cible porte le vote, les autres en gardent une copie.
     *
     * @return faux si un vote est déjà en cours contre cette cible
     */
    public boolean startVote(UUID initiatorId, String initiatorName, Player target) {
        if (isVoteInProgress(target.getUniqueId())) return false;

        NetworkVotes net = network;
        int required = calculateRequiredVotes();
        ActiveVote vote = new ActiveVote(initiatorId, initiatorName, target.getUniqueId(), target.getName(), required);
        long durationSeconds = plugin.getConfigManager().getVoteDuration();
        vote.setEndsAt(System.currentTimeMillis() + durationSeconds * 1000L);
        if (net != null) vote.setOwner(net.serverName());

        announceStart(initiatorName, target.getName());

        String logMessage = plugin.getConfigManager().getLogVoteStart()
            .replace("{initiator}", initiatorName)
            .replace("{target}", target.getName());
        ActionLog.write(plugin, LOG_FILE, logMessage);

        BukkitTask expirationTask = new BukkitRunnable() {
            @Override
            public void run() {
                ActiveVote currentVote = activeVotes.get(target.getUniqueId());
                if (currentVote != null && currentVote.equals(vote)) {
                    endVote(currentVote, false);
                }
            }
        }.runTaskLater(plugin, durationSeconds * 20L);

        vote.setExpirationTask(expirationTask);
        activeVotes.put(target.getUniqueId(), vote);

        if (net != null) {
            net.watch(vote);
            net.publishState(vote, true);
        }
        return true;
    }

    /** Vote d'un joueur de ce serveur. Si le vote est porté par un autre serveur, la demande lui est envoyée. */
    public void addVote(Player voter, UUID targetId) {
        ActiveVote vote = activeVotes.get(targetId);
        if (vote == null || vote.hasVoted(voter.getUniqueId())) return;

        if (vote.isMirror()) {
            NetworkVotes net = network;
            if (net != null) net.sendVote(vote, voter);
            return;
        }
        registerVote(vote, voter.getUniqueId(), voter.getName());
    }

    /**
     * Vote reçu d'un joueur d'un autre serveur, pour un vote que ce serveur porte.
     *
     * @return faux s'il n'y a pas (ou plus) de vote porté ici contre cette cible
     */
    public boolean addRemoteVote(UUID voterId, String voterName, UUID targetId) {
        ActiveVote vote = activeVotes.get(targetId);
        if (vote == null || vote.isMirror()) return false;
        if (!vote.hasVoted(voterId)) registerVote(vote, voterId, voterName);
        return true;
    }

    private void registerVote(ActiveVote vote, UUID voterId, String voterName) {
        vote.addVoter(voterId, voterName);
        updateGUIsForVote(vote);

        String logMessage = plugin.getConfigManager().getLogPlayerVote()
            .replace("{voter}", voterName)
            .replace("{target}", vote.getTargetName());
        ActionLog.write(plugin, LOG_FILE, logMessage);

        if (vote.isSuccessful()) {
            endVote(vote, true);
        } else if (network != null) {
            network.publishState(vote, false);
        }
    }

    public void endVote(ActiveVote vote, boolean success) {
        endVote(vote, success ? Outcome.SUCCESS : Outcome.FAIL);
    }

    public void endVote(ActiveVote vote, Outcome outcome) {
        boolean success = outcome == Outcome.SUCCESS;
        activeVotes.remove(vote.getTargetUUID());
        if (vote.getExpirationTask() != null) vote.getExpirationTask().cancel();
        vote.cancelWatch();

        ConfigManager cfg = plugin.getConfigManager();
        NetworkVotes net = network;

        // Log amélioré pour inclure la liste des votants
        String punishers = vote.getVoters().stream()
            .map(vote::nameOf)
            .filter(Objects::nonNull)
            .collect(Collectors.joining(", "));

        String logMessage = cfg.getLogVoteEnd()
            .replace("{target}", vote.getTargetName())
            .replace("{outcome}", success ? "SUCCESS" : "FAILURE")
            .replace("{current}", String.valueOf(vote.getCurrentVotes()))
            .replace("{required}", String.valueOf(vote.getRequiredVotes()))
            + " | Votants: [" + punishers + "]";
        ActionLog.write(plugin, LOG_FILE, logMessage);

        if (success) {
            if (net == null) {
                applySuccess(vote);
            } else {
                // La base décide qu'un seul vote sanctionne le joueur pour tout le réseau
                net.claimPunishment(vote, won -> {
                    if (won) {
                        applySuccess(vote);
                        net.publishEnd(vote, "success");
                    } else {
                        plugin.getLogger().info("Vote réussi contre " + vote.getTargetName() + " mais le joueur vient d'être sanctionné par un autre vote : rien n'est appliqué.");
                        net.publishEnd(vote, "duplicate");
                    }
                    forgetMessages(vote);
                });
            }
        } else {
            announceEnd(outcome, vote.getTargetName());
            if (net != null) net.publishEnd(vote, outcome == Outcome.LEFT ? "left" : "fail");
        }

        closeViewers(vote);

        // Réussite en réseau : les messages sont lus par le webhook, puis oubliés dans le rappel de la base
        if (!(success && net != null)) forgetMessages(vote);
    }

    /** Mode réseau : les derniers messages d'un joueur parti pendant le vote n'étaient gardés que pour le webhook. */
    private void forgetMessages(ActiveVote vote) {
        if (network != null && Bukkit.getPlayer(vote.getTargetUUID()) == null) {
            plugin.getPlayerListener().forget(vote.getTargetUUID());
        }
    }

    /** Vote réussi : annonce, liste des sanctionnés, commande de sanction et webhook. */
    private void applySuccess(ActiveVote vote) {
        ConfigManager cfg = plugin.getConfigManager();
        announceEnd(Outcome.SUCCESS, vote.getTargetName());

        // NOUVEAU: Ajoute le joueur à la liste des joueurs déjà sanctionnés
        plugin.getStorage().addPunishedPlayer(vote.getTargetUUID(), vote.getTargetName());

        String command = cfg.getCommandOnSuccess().replace("{player}", vote.getTargetName()).replace("{uuid}", vote.getTargetUUID().toString());
        NetworkVotes net = network;
        if (net != null && Bukkit.getPlayer(vote.getTargetUUID()) == null) {
            // Cible sur un autre serveur (ou partie) : la commande s'exécute là où elle est, une seule fois
            net.runPunishment(vote.getTargetUUID(), command);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
        }

        if (cfg.isWebhookEnabled()) {
            try {
                sendDiscordWebhook(vote);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Une erreur critique est survenue lors de la préparation du webhook Discord. La sanction a quand même été appliquée.", e);
            }
        }
    }

    // --- Annonces (communes au serveur qui porte le vote et aux copies des autres serveurs) ---

    public void announceStart(String initiatorName, String targetName) {
        String broadcastMessage = plugin.getConfigManager().getVoteBroadcastMessage()
            .replace("{initiator}", initiatorName)
            .replace("{target}", targetName);
        plugin.getServer().broadcastMessage(broadcastMessage);
    }

    public void announceEnd(Outcome outcome, String targetName) {
        ConfigManager cfg = plugin.getConfigManager();
        if (outcome == Outcome.SUCCESS) {
            plugin.getServer().broadcastMessage(cfg.getVoteSuccessMessage().replace("{player}", targetName));
            return;
        }
        plugin.getServer().broadcastMessage(cfg.getVoteFailMessage().replace("{player}", targetName));
        if (outcome == Outcome.LEFT) {
            plugin.getServer().broadcastMessage(cancelledMessage(targetName));
        }
    }

    /** Texte annoncé quand la cible se déconnecte pendant le vote. */
    public String cancelledMessage(String targetName) {
        return plugin.getConfigManager().getPrefix() +
            "§cLe vote contre §e" + targetName + "§c a été annulé car il s'est déconnecté.";
    }

    // --- Copies des votes portés par un autre serveur (mode réseau) ---

    public void putMirror(ActiveVote vote) {
        activeVotes.put(vote.getTargetUUID(), vote);
    }

    /** Retire la copie d'un vote, annule sa garde et ferme les menus qui la montraient. */
    public void dropMirror(ActiveVote vote) {
        if (!activeVotes.remove(vote.getTargetUUID(), vote)) return;
        if (vote.getExpirationTask() != null) vote.getExpirationTask().cancel();
        closeViewers(vote);
    }

    private void closeViewers(ActiveVote vote) {
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
        ConfigManager cfg = plugin.getConfigManager();
        if (cfg.getWebhookUrl() == null || cfg.getWebhookUrl().isEmpty() || cfg.getWebhookUrl().contains("VOTRE_URL")) {
            plugin.getLogger().warning("L'URL du Webhook Discord n'est pas configurée. Notification non envoyée.");
            return;
        }

        // Lecture des joueurs (hors ligne compris) sur le thread principal ; la tâche asynchrone ne fait que l'envoi
        OfflinePlayer target = Bukkit.getOfflinePlayer(vote.getTargetUUID());
        final String localFirstJoin = new SimpleDateFormat("dd/MM/yyyy HH:mm").format(new Date(target.getFirstPlayed()));
        String voters = vote.getVoters().stream().map(vote::nameOf).filter(Objects::nonNull).collect(Collectors.joining(", "));
        if (voters.isEmpty()) voters = "N/A";
        final String punishers = voters;
        final String recentMessages = plugin.getPlayerListener().getFormattedRecentMessages(vote.getTargetUUID());
        final NetworkVotes net = network;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // Avec SHNetwork, la vraie première connexion du compte vient du registre commun, pas des fichiers de ce serveur
                String firstJoin = localFirstJoin;
                if (net != null) {
                    long registered = net.firstSeen(vote.getTargetName());
                    if (registered > 0L) firstJoin = new SimpleDateFormat("dd/MM/yyyy HH:mm").format(new Date(registered));
                }

                JSONObject payload = new JSONObject();
                payload.put("username", Optional.ofNullable(cfg.getWebhookUsername()).orElse("Alerte Plugin"));
                payload.put("avatar_url", Optional.ofNullable(cfg.getWebhookAvatarUrl()).orElse("").replace("{player}", vote.getTargetName()));

                JSONObject embed = new JSONObject();
                embed.put("title", Optional.ofNullable(cfg.getWebhookEmbedTitle()).orElse("Rapport de Sanction"));
                embed.put("color", cfg.getWebhookEmbedColor() != 0 ? cfg.getWebhookEmbedColor() : 15158332);

                String finalDescription = Optional.ofNullable(cfg.getWebhookEmbedDescription())
                    .orElse("**Joueurs ayant voté:**\n{punisher_list}")
                    .replace("{punisher_list}", punishers);
                embed.put("description", finalDescription);

                String lastMessages = recentMessages;
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
            NetworkVotes net = network;
            // Avec SHNetwork, le pourcentage se compte sur tous les joueurs du réseau
            long onlinePlayers = net != null ? net.onlinePlayers() : Bukkit.getOnlinePlayers().size();
            return (int) Math.ceil(onlinePlayers * (double) cfg.getRequiredVotesValue() / 100.0);
        }
    }

    public void cancelAllVotes() {
        activeVotes.values().forEach(vote -> {
            if (vote.getExpirationTask() != null) vote.getExpirationTask().cancel();
            vote.cancelWatch();
        });
        NetworkVotes net = network;
        // Les autres serveurs retirent la copie des votes que ce serveur portait
        if (net != null && activeVotes.values().stream().anyMatch(vote -> !vote.isMirror())) net.publishClear();
        activeVotes.clear();
        plugin.getLogger().info("Tous les votes en cours ont été annulés.");
    }

    public boolean isVoteInProgress(UUID targetUUID) {
        return activeVotes.containsKey(targetUUID);
    }

    /** Vrai si ce vote est connu par le réseau (porté ici ou copie d'un autre serveur). */
    public boolean isNetworkVote(UUID targetUUID) {
        ActiveVote vote = activeVotes.get(targetUUID);
        return vote != null && vote.isNetworked();
    }

    public ActiveVote getActiveVote(UUID targetUUID) {
        return activeVotes.get(targetUUID);
    }

    public Map<UUID, ActiveVote> getActiveVotes() {
        return activeVotes;
    }

    public static class ActiveVote {
        private final UUID initiatorUUID;
        private final String initiatorName;
        private final UUID targetUUID;
        private final String targetName;
        private final int requiredVotes;
        private final Set<UUID> voters = new HashSet<>();
        private final Map<UUID, String> voterNames = new HashMap<>();
        private final Set<UUID> viewers = new HashSet<>();
        private BukkitTask expirationTask;

        // Mode réseau : serveur qui porte le vote (null sur un serveur seul), copie d'un vote porté ailleurs, fin prévue
        private String owner;
        private boolean mirror;
        private long endsAt;
        private BukkitTask watchTask;

        public ActiveVote(UUID initiatorUUID, String initiatorName, UUID targetUUID, String targetName, int requiredVotes) {
            this.initiatorUUID = initiatorUUID;
            this.initiatorName = initiatorName;
            this.targetUUID = targetUUID;
            this.targetName = targetName;
            this.requiredVotes = requiredVotes;
            addVoter(initiatorUUID, initiatorName);
        }

        public void addVoter(UUID voterUUID, String voterName) {
            this.voters.add(voterUUID);
            if (voterName != null) this.voterNames.put(voterUUID, voterName);
        }

        /** Copie d'un vote : remplace les votants par ceux que le serveur qui porte le vote vient d'annoncer. */
        public void replaceVoters(Map<UUID, String> fresh) {
            this.voters.clear();
            this.voterNames.clear();
            fresh.forEach(this::addVoter);
        }

        /** Pseudo d'un votant : celui noté au vote, ou à défaut celui connu de ce serveur. */
        public String nameOf(UUID voterUUID) {
            String name = voterNames.get(voterUUID);
            return name != null ? name : Bukkit.getOfflinePlayer(voterUUID).getName();
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

        public UUID getInitiatorUUID() {
            return initiatorUUID;
        }

        public String getInitiatorName() {
            return initiatorName;
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

        // --- Mode réseau ---

        public String getOwner() {
            return owner;
        }

        /** Ce serveur porte le vote. */
        public void setOwner(String owner) {
            this.owner = owner;
            this.mirror = false;
        }

        /** Ce serveur ne garde qu'une copie du vote que porte {@code owner}. */
        public void setMirrorOf(String owner) {
            this.owner = owner;
            this.mirror = true;
        }

        public boolean isMirror() {
            return mirror;
        }

        /** Vote connu du réseau : porté ici ou copie d'un autre serveur. */
        public boolean isNetworked() {
            return owner != null;
        }

        public long getEndsAt() {
            return endsAt;
        }

        public void setEndsAt(long endsAt) {
            this.endsAt = endsAt;
        }

        public void setWatchTask(BukkitTask watchTask) {
            this.watchTask = watchTask;
        }

        public void cancelWatch() {
            if (watchTask != null) {
                watchTask.cancel();
                watchTask = null;
            }
        }
    }
}
