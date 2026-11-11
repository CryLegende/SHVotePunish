package org.Cry_Legende.sHVotePunish.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.Cry_Legende.sHVotePunish.SHVotePunish;
import org.Cry_Legende.sHVotePunish.data.SharedLists;
import org.Cry_Legende.sHVotePunish.managers.ConfigManager;
import org.Cry_Legende.sHVotePunish.managers.GUIManager;
import org.Cry_Legende.sHVotePunish.managers.VoteManager;
import org.Cry_Legende.sHVotePunish.managers.VoteManager.ActiveVote;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.scheduler.BukkitTask;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Un seul vote par joueur visé pour tout le réseau, avec SHNetwork.
 *
 * <p>Le serveur où se trouve la cible au lancement porte le vote : il compte les votants, tranche la réussite, vérifie
 * les immunités avec la vraie permission de la cible et lance la sanction. Les autres serveurs gardent une copie du vote
 * (état complet republié à chaque changement) pour afficher les mêmes annonces et les mêmes menus, et lui envoient les
 * votes et les demandes de lancement de leurs joueurs. Les votes contre des joueurs différents restent indépendants,
 * comme sur un serveur seul.</p>
 *
 * <pre>
 * canal              request  {id, action: check|start|vote, to, from, actor, target}   serveur du joueur -> porteur
 *                    reply    {id, to, result, state?}                                  porteur -> serveur du joueur
 *                    state    {start, owner, target, initiator, required, remaining, voters}   porteur -> tous
 *                    end      {target, outcome: success|fail|left|duplicate}            porteur -> tous
 *                    clear    {}                                                        porteur arrêté -> tous
 * canal.liste        {list, op, uuid}                                                  voir NetworkLists
 * </pre>
 *
 * <p>Tout se passe sur le fil principal ; la base n'est lue et écrite que sur son propre fil.</p>
 */
public final class NetworkVotes {

    /** Délai, après la fin prévue, au bout duquel une copie sans message de fin est retirée (porteur tombé). */
    private static final long GUARD_GRACE_TICKS = 200L;

    private record Pending(Consumer<JsonObject> callback, BukkitTask timeout) {
    }

    private final SHVotePunish plugin;
    private final NetBus bus;
    private final VoteDatabase db;
    private final NetworkLists lists;
    private final String channel;
    private final long replyTicks;
    private final long absenceMillis;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public NetworkVotes(SHVotePunish plugin, NetBus bus, VoteDatabase db, String channel, long replyMillis, long absenceMillis) {
        this.plugin = plugin;
        this.bus = bus;
        this.db = db;
        this.channel = channel;
        this.replyTicks = Math.max(1L, replyMillis / 50L);
        this.absenceMillis = absenceMillis;
        this.lists = new NetworkLists(plugin, bus, db, plugin.getStorage(), channel + ".liste");
    }

    public void start(long reloadSeconds) {
        plugin.getStorage().useSharedLists(lists);
        bus.listen(channel, this::onMessage);
        lists.start(reloadSeconds);
    }

    public void close() {
        plugin.getStorage().useSharedLists(null);
        pending.values().forEach(p -> p.timeout().cancel());
        pending.clear();
        lists.close();
        db.close();
    }

    public String serverName() {
        return bus.serverName();
    }

    /** Joueurs connectés sur tout le réseau, pour le pourcentage de votes requis. */
    public int onlinePlayers() {
        return bus.online();
    }

    // ------------------------------------------------------------------ côté joueur qui agit (autre serveur que la cible)

    /**
     * /votepunish sur un joueur qui n'est pas sur ce serveur : ouvre le menu du vote en cours, ou demande au serveur du
     * joueur s'il peut être la cible d'un vote avant d'ouvrir le menu de confirmation.
     *
     * @return faux si le joueur n'est connecté nulle part (message « introuvable » à la charge de l'appelant)
     */
    public boolean handleRemote(Player player, String name) {
        Optional<NetBus.Peer> found = bus.find(name);
        if (found.isEmpty() || isHere(found.get())) return false;
        NetBus.Peer target = found.get();

        ActiveVote existing = plugin.getVoteManager().getActiveVote(target.uuid());
        if (existing != null) {
            new GUIManager(plugin).openVoteMenu(player, existing);
            return true;
        }
        request(target.server(), "check", player, target.uuid(), target.name(), reply -> {
            if (player.isOnline()) showVerdict(player, target, reply, false);
        });
        return true;
    }

    /**
     * Clic sur « Valider » pour une cible d'un autre serveur : le serveur de la cible revérifie et lance le vote.
     *
     * @return faux si la cible n'est connectée nulle part
     */
    public boolean startRemote(Player initiator, UUID targetId) {
        Optional<NetBus.Peer> found = bus.find(targetId.toString());
        if (found.isEmpty() || isHere(found.get())) return false;
        NetBus.Peer target = found.get();
        request(target.server(), "start", initiator, target.uuid(), target.name(), reply -> {
            if (initiator.isOnline()) showVerdict(initiator, target, reply, true);
        });
        return true;
    }

    /** Vote d'un joueur de ce serveur pour un vote que porte un autre serveur. */
    public void sendVote(ActiveVote vote, Player voter) {
        request(vote.getOwner(), "vote", voter, vote.getTargetUUID(), vote.getTargetName(), reply -> {
            // Pas de réponse : le serveur qui porte le vote ne répond plus, le vote ne peut plus aboutir
            if (voter.isOnline() && (reply == null || "finished".equals(text(reply, "result")))) {
                voter.sendMessage("§cCe vote est terminé.");
            }
        });
    }

    /** Réponse du serveur de la cible : le message qui convient, ou le menu qui s'ouvre. */
    private void showVerdict(Player player, NetBus.Peer target, JsonObject reply, boolean starting) {
        ConfigManager cfg = plugin.getConfigManager();
        // Sans réponse, la cible n'est pas joignable pour un vote : même message qu'un joueur absent
        String result = reply == null ? null : text(reply, "result");
        if (result == null) result = "absent";
        switch (result) {
            case "ok" -> {
                // Au lancement, l'annonce arrive avec l'état du vote publié par le serveur de la cible
                if (!starting) {
                    player.sendMessage(cfg.getVoteStartConfirmationMessage().replace("{player}", target.name()));
                    new GUIManager(plugin).openConfirmationMenu(player, target.uuid(), target.name());
                }
            }
            case "progress" -> {
                JsonObject state = reply.getAsJsonObject("state");
                if (state != null) applyState(text(reply, "origin"), state);
                ActiveVote vote = plugin.getVoteManager().getActiveVote(target.uuid());
                if (vote != null) new GUIManager(plugin).openVoteMenu(player, vote);
            }
            case "self" -> player.sendMessage(cfg.getCannotPunishSelfMessage());
            case "bypass", "blacklist" -> player.sendMessage(cfg.getPlayerHasBypassMessage());
            case "punished" -> player.sendMessage(cfg.getPlayerAlreadyPunishedMessage());
            default -> player.sendMessage(cfg.getPlayerNotFoundMessage());
        }
    }

    private void request(String owner, String action, Player actor, UUID targetId, String targetName, Consumer<JsonObject> callback) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        JsonObject message = new JsonObject();
        message.addProperty("event", "request");
        message.addProperty("id", id);
        message.addProperty("action", action);
        message.addProperty("to", owner);
        message.addProperty("from", bus.serverName());
        message.add("actor", person(actor.getUniqueId(), actor.getName()));
        message.add("target", person(targetId, targetName));
        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Pending lost = pending.remove(id);
            if (lost != null) lost.callback().accept(null);
        }, replyTicks);
        pending.put(id, new Pending(callback, timeout));
        bus.publish(channel, message);
    }

    // ------------------------------------------------------------------ côté serveur qui porte le vote

    private void onRequest(JsonObject message) {
        VoteManager votes = plugin.getVoteManager();
        JsonObject actor = message.getAsJsonObject("actor");
        JsonObject target = message.getAsJsonObject("target");
        UUID actorId = UUID.fromString(text(actor, "uuid"));
        String actorName = text(actor, "name");
        UUID targetId = UUID.fromString(text(target, "uuid"));

        String result = switch (String.valueOf(text(message, "action"))) {
            case "check" -> validate(actorId, targetId);
            case "start" -> {
                String verdict = validate(actorId, targetId);
                Player player = Bukkit.getPlayer(targetId);
                if (verdict.equals("ok") && player != null) {
                    votes.startVote(actorId, actorName, player);
                }
                yield verdict;
            }
            case "vote" -> votes.addRemoteVote(actorId, actorName, targetId) ? "ok" : "finished";
            default -> null;
        };
        if (result == null) return;

        JsonObject reply = new JsonObject();
        reply.addProperty("event", "reply");
        reply.addProperty("id", text(message, "id"));
        reply.addProperty("to", text(message, "from"));
        reply.addProperty("result", result);
        if (result.equals("progress")) {
            ActiveVote existing = votes.getActiveVote(targetId);
            if (existing != null) reply.add("state", stateJson(existing, false));
        }
        bus.publish(channel, reply);
    }

    /**
     * Ce que /votepunish vérifie sur un joueur de ce serveur, dans le même ordre : un vote est-il déjà en cours, est-ce
     * l'initiateur lui-même, immunité par permission, joueur jamais sanctionnable, déjà sanctionné.
     *
     * @return ok, progress, absent, self, bypass, blacklist ou punished
     */
    private String validate(UUID actorId, UUID targetId) {
        ConfigManager cfg = plugin.getConfigManager();
        VoteManager votes = plugin.getVoteManager();
        Player target = Bukkit.getPlayer(targetId);
        boolean inProgress = votes.isVoteInProgress(targetId);
        if (target == null && !inProgress) return "absent";
        if (inProgress) return "progress";
        if (target.getUniqueId().equals(actorId)) return "self";
        if (plugin.hasPermission(target, cfg.getBypassPermission()) || plugin.hasPermission(target, cfg.getEligibilityBypassPermission())) {
            return "bypass";
        }
        if (cfg.getBlacklistedPlayers().stream().anyMatch(name -> name.equalsIgnoreCase(target.getName()))) {
            return "blacklist";
        }
        if (plugin.getStorage().isPlayerPunished(targetId)) return "punished";
        return "ok";
    }

    private void onReply(String origin, JsonObject reply) {
        Pending waiting = pending.remove(String.valueOf(text(reply, "id")));
        if (waiting == null) return;
        waiting.timeout().cancel();
        reply.addProperty("origin", origin);
        waiting.callback().accept(reply);
    }

    /**
     * Surveille la présence de la cible sur le réseau : une sortie de serveur peut n'être qu'un changement de serveur,
     * le vote n'est annulé que si la cible reste absente du réseau pendant {@code reseau.absence-max-secondes}.
     */
    public void watch(ActiveVote vote) {
        long[] absentSince = {0L};
        vote.setWatchTask(Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (bus.find(vote.getTargetUUID().toString()).isPresent()) {
                absentSince[0] = 0L;
                return;
            }
            long now = System.currentTimeMillis();
            if (absentSince[0] == 0L) {
                absentSince[0] = now;
            } else if (now - absentSince[0] >= absenceMillis && plugin.getVoteManager().getActiveVote(vote.getTargetUUID()) == vote) {
                plugin.getVoteManager().endVote(vote, VoteManager.Outcome.LEFT);
            }
        }, 20L, 20L));
    }

    /** Décide, avec la base, que ce vote est le seul à sanctionner le joueur ; {@code next} reprend sur le fil principal. */
    public void claimPunishment(ActiveVote vote, Consumer<Boolean> next) {
        plugin.getStorage().claimPunished(vote.getTargetUUID(), vote.getTargetName()).whenComplete((won, error) -> {
            boolean apply = error != null || Boolean.TRUE.equals(won);
            try {
                Bukkit.getScheduler().runTask(plugin, () -> next.accept(apply));
            } catch (IllegalPluginAccessException ignored) {
                // plugin en cours d'arrêt
            }
        });
    }

    /** Exécute la commande de sanction là où se trouve la cible, une seule fois (ou à sa prochaine connexion). */
    public void runPunishment(UUID targetId, String command) {
        bus.runFor(targetId.toString(), command);
    }

    public void publishState(ActiveVote vote, boolean start) {
        JsonObject message = stateJson(vote, start);
        message.addProperty("event", "state");
        bus.publish(channel, message);
    }

    public void publishEnd(ActiveVote vote, String outcome) {
        JsonObject message = new JsonObject();
        message.addProperty("event", "end");
        message.add("target", person(vote.getTargetUUID(), vote.getTargetName()));
        message.addProperty("outcome", outcome);
        bus.publish(channel, message);
    }

    public void publishClear() {
        JsonObject message = new JsonObject();
        message.addProperty("event", "clear");
        bus.publish(channel, message);
    }

    private JsonObject stateJson(ActiveVote vote, boolean start) {
        JsonObject state = new JsonObject();
        state.addProperty("start", start);
        state.addProperty("owner", vote.getOwner());
        state.add("target", person(vote.getTargetUUID(), vote.getTargetName()));
        state.add("initiator", person(vote.getInitiatorUUID(), vote.getInitiatorName()));
        state.addProperty("required", vote.getRequiredVotes());
        state.addProperty("remaining", Math.max(0L, vote.getEndsAt() - System.currentTimeMillis()));
        JsonArray voters = new JsonArray();
        for (UUID voter : vote.getVoters()) {
            voters.add(person(voter, vote.nameOf(voter)));
        }
        state.add("voters", voters);
        return state;
    }

    // ------------------------------------------------------------------ copies chez les autres serveurs

    /** Crée ou met à jour la copie d'un vote porté par {@code origin}. */
    private void applyState(String origin, JsonObject state) {
        VoteManager votes = plugin.getVoteManager();
        JsonObject target = state.getAsJsonObject("target");
        UUID targetId = UUID.fromString(text(target, "uuid"));
        ActiveVote vote = votes.getActiveVote(targetId);
        if (vote != null && !vote.isMirror()) return; // ce serveur porte déjà un vote contre ce joueur

        Map<UUID, String> voters = new LinkedHashMap<>();
        for (JsonElement element : state.getAsJsonArray("voters")) {
            JsonObject voter = element.getAsJsonObject();
            voters.put(UUID.fromString(text(voter, "uuid")), text(voter, "name"));
        }

        if (vote == null) {
            JsonObject initiator = state.getAsJsonObject("initiator");
            ActiveVote copy = new ActiveVote(UUID.fromString(text(initiator, "uuid")), text(initiator, "name"),
                targetId, text(target, "name"), (int) number(state, "required"));
            long remaining = number(state, "remaining");
            // Une réponse peut venir d'un serveur qui ne garde lui-même qu'une copie : le vrai porteur est dans l'état
            String owner = text(state, "owner");
            copy.setMirrorOf(owner != null ? owner : origin);
            copy.setEndsAt(System.currentTimeMillis() + remaining);
            votes.putMirror(copy);
            // Garde : sans message de fin (serveur tombé), la copie s'efface d'elle-même peu après la fin prévue
            copy.setExpirationTask(Bukkit.getScheduler().runTaskLater(plugin, () -> votes.dropMirror(copy),
                remaining / 50L + GUARD_GRACE_TICKS));
            if (state.has("start") && state.get("start").getAsBoolean()) {
                votes.announceStart(copy.getInitiatorName(), copy.getTargetName());
            }
            vote = copy;
        }
        vote.replaceVoters(voters);
        votes.updateGUIsForVote(vote);
    }

    private void onEnd(String origin, JsonObject message) {
        VoteManager votes = plugin.getVoteManager();
        UUID targetId = UUID.fromString(text(message.getAsJsonObject("target"), "uuid"));
        ActiveVote vote = votes.getActiveVote(targetId);
        if (vote == null || !vote.isMirror() || !origin.equalsIgnoreCase(vote.getOwner())) return;
        votes.dropMirror(vote);
        VoteManager.Outcome outcome = switch (String.valueOf(text(message, "outcome"))) {
            case "success" -> VoteManager.Outcome.SUCCESS;
            case "fail" -> VoteManager.Outcome.FAIL;
            case "left" -> VoteManager.Outcome.LEFT;
            default -> null; // « duplicate » : un autre vote vient de sanctionner le joueur, il a déjà été annoncé
        };
        if (outcome != null) votes.announceEnd(outcome, vote.getTargetName());
    }

    private void onClear(String origin) {
        VoteManager votes = plugin.getVoteManager();
        for (ActiveVote vote : new ArrayList<>(votes.getActiveVotes().values())) {
            if (vote.isMirror() && origin.equalsIgnoreCase(vote.getOwner())) votes.dropMirror(vote);
        }
    }

    private void onMessage(String origin, JsonObject message) {
        try {
            String event = text(message, "event");
            if (event == null) return;
            switch (event) {
                case "request" -> {
                    if (isForMe(message)) onRequest(message);
                }
                case "reply" -> {
                    if (isForMe(message)) onReply(origin, message);
                }
                case "state" -> applyState(origin, message);
                case "end" -> onEnd(origin, message);
                case "clear" -> onClear(origin);
                default -> {
                }
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Message de vote illisible : " + message, e);
        }
    }

    // ------------------------------------------------------------------ joueurs, importation, registre

    /**
     * Retrouve un joueur par son pseudo pour les commandes d'administration : connecté n'importe où sur le réseau, sinon
     * inscrit au registre des premières connexions ou connu de ce serveur. Le rappel reçoit {@code (null, null)} s'il
     * n'est connu nulle part, toujours sur le fil principal.
     */
    public void resolvePlayer(String name, BiConsumer<UUID, String> callback) {
        Optional<NetBus.Peer> online = bus.find(name);
        if (online.isPresent()) {
            callback.accept(online.get().uuid(), online.get().name());
            return;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        lists.execute(() -> {
            String registered = null;
            try {
                registered = db.known(lower).map(VoteDatabase.Known::name).orElse(null);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Registre des premières connexions illisible : " + e.getMessage());
            }
            String shown = registered;
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    String known = shown;
                    if (known == null) {
                        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
                        if (cached != null && cached.hasPlayedBefore() && cached.getName() != null) known = cached.getName();
                    }
                    if (known == null) {
                        callback.accept(null, null);
                    } else {
                        callback.accept(offlineUuid(known), known);
                    }
                });
            } catch (IllegalPluginAccessException ignored) {
                // plugin en cours d'arrêt
            }
        });
    }

    /** Copie les listes des fichiers dans la base du réseau, sans toucher aux joueurs déjà inscrits. */
    public void importLists(CommandSender admin) {
        lists.execute(() -> {
            String message;
            boolean done = false;
            ConfigManager cfg = plugin.getConfigManager();
            try {
                Set<UUID> ignored = plugin.getStorage().readFile(SharedLists.Kind.IGNORED);
                Set<UUID> punished = plugin.getStorage().readFile(SharedLists.Kind.PUNISHED);
                int[] counts = db.importLists(ignored, punished);
                message = cfg.getImportSuccessMessage()
                    .replace("{imported}", String.valueOf(counts[0]))
                    .replace("{present}", String.valueOf(counts[1]));
                done = true;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Importation des listes impossible : " + e.getMessage());
                message = cfg.getImportErrorMessage();
            }
            String shown = message;
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!(admin instanceof Player player) || player.isOnline()) admin.sendMessage(shown);
                });
            } catch (IllegalPluginAccessException ignored) {
                // plugin en cours d'arrêt
            }
            if (done) {
                lists.announceReload();
                lists.reload();
            }
        });
    }

    /** Première connexion du compte au réseau (millisecondes), ou 0 si le registre ne la connaît pas. Bloque : hors du fil principal. */
    public long firstSeen(String name) {
        try {
            return db.known(name.toLowerCase(Locale.ROOT)).map(VoteDatabase.Known::firstPlayed).orElse(0L);
        } catch (SQLException e) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------ outils

    private boolean isHere(NetBus.Peer peer) {
        return peer.server().equalsIgnoreCase(bus.serverName());
    }

    private boolean isForMe(JsonObject message) {
        return bus.serverName().equalsIgnoreCase(text(message, "to"));
    }

    /** UUID « hors ligne » d'un pseudo, celui de tout le réseau (pas de recherche Mojang). */
    private static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static JsonObject person(UUID uuid, String name) {
        JsonObject person = new JsonObject();
        person.addProperty("uuid", uuid.toString());
        if (name != null && !name.isEmpty()) person.addProperty("name", name);
        return person;
    }

    private static String text(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    private static long number(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? 0L : element.getAsLong();
    }
}
