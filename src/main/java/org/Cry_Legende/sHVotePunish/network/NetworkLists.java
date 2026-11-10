package org.Cry_Legende.sHVotePunish.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.Cry_Legende.sHVotePunish.SHVotePunish;
import org.Cry_Legende.sHVotePunish.data.SharedLists;
import org.Cry_Legende.sHVotePunish.data.Storage;
import org.bukkit.Bukkit;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Listes des ignorés et des sanctionnés en base, avec la même mémoire qu'avant dans {@link Storage}.
 *
 * <p>Une écriture se fait sur un fil à part, dans l'ordre des appels : mémoire tout de suite, puis un INSERT IGNORE
 * ou un DELETE d'un seul joueur, puis un message sur le bus que les autres serveurs appliquent à leur mémoire sans
 * relire la base. Une relecture périodique rattrape un message perdu ; elle est abandonnée si une écriture a eu lieu
 * pendant la lecture, pour ne jamais effacer un ajout pas encore écrit.</p>
 *
 * <p>Message du bus : {@code {list, op: add|remove, uuid}} ou {@code {op: reload}}.</p>
 */
final class NetworkLists implements SharedLists {

    private final SHVotePunish plugin;
    private final NetBus bus;
    private final VoteDatabase db;
    private final Storage storage;
    private final String channel;
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SHVotePunish-base");
        thread.setDaemon(true);
        return thread;
    });
    /** Écritures demandées pas encore terminées. */
    private final AtomicInteger pending = new AtomicInteger();
    /** Compte les changements de listes faits sur le fil principal : une lecture de la base trop ancienne s'en aperçoit. */
    private volatile int epoch;
    private volatile long lastWarning;
    private BukkitTask periodic;

    NetworkLists(SHVotePunish plugin, NetBus bus, VoteDatabase db, Storage storage, String channel) {
        this.plugin = plugin;
        this.bus = bus;
        this.db = db;
        this.storage = storage;
        this.channel = channel;
    }

    void start(long reloadSeconds) {
        bus.listen(channel, this::onMessage);
        execute(() -> {
            try {
                db.createTable();
            } catch (SQLException e) {
                warn("Table des listes inaccessible", e);
            }
            reload();
        });
        if (reloadSeconds > 0) {
            long ticks = reloadSeconds * 20L;
            periodic = Bukkit.getScheduler().runTaskTimer(plugin, () -> execute(this::reload), ticks, ticks);
        }
    }

    void close() {
        if (periodic != null) {
            periodic.cancel();
        }
        io.shutdown();
        try {
            io.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Lance une tâche sur le fil de la base (les écritures et lectures passent toutes par lui, dans l'ordre). */
    void execute(Runnable task) {
        try {
            io.execute(task);
        } catch (RejectedExecutionException ignored) {
            // arrêt en cours
        }
    }

    // ------------------------------------------------------------------ écritures (fil principal)

    @Override
    public void add(Kind kind, UUID uuid, String name) {
        epoch++;
        pending.incrementAndGet();
        execute(() -> {
            try {
                db.add(kind, uuid, name);
                announce(kind, "add", uuid);
            } catch (SQLException e) {
                warn("Écriture impossible, " + kind.id() + " " + uuid + " n'est pas partagé avec les autres serveurs", e);
            } finally {
                pending.decrementAndGet();
            }
        });
    }

    @Override
    public void remove(Kind kind, UUID uuid) {
        epoch++;
        pending.incrementAndGet();
        execute(() -> {
            try {
                db.remove(kind, uuid);
                announce(kind, "remove", uuid);
            } catch (SQLException e) {
                warn("Écriture impossible, " + kind.id() + " " + uuid + " n'est pas retiré chez les autres serveurs", e);
            } finally {
                pending.decrementAndGet();
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> claimPunished(UUID uuid, String name) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        epoch++;
        pending.incrementAndGet();
        execute(() -> {
            try {
                boolean won = db.add(Kind.PUNISHED, uuid, name);
                if (won) {
                    announce(Kind.PUNISHED, "add", uuid);
                }
                result.complete(won);
            } catch (SQLException e) {
                warn("Base injoignable : la sanction de " + uuid + " est appliquée sans passer par elle", e);
                result.completeExceptionally(e);
            } finally {
                pending.decrementAndGet();
            }
        });
        return result;
    }

    /** Demande à tous les serveurs de relire la base (après une importation). */
    void announceReload() {
        JsonObject message = new JsonObject();
        message.addProperty("op", "reload");
        bus.publish(channel, message);
    }

    // ------------------------------------------------------------------ lecture

    /** Fil de la base : relit les deux listes et les applique sur le fil principal si rien n'a changé entre-temps. */
    void reload() {
        int seen = epoch;
        Set<UUID> ignored;
        Set<UUID> punished;
        try {
            ignored = db.load(Kind.IGNORED);
            punished = db.load(Kind.PUNISHED);
        } catch (SQLException e) {
            warn("Listes illisibles, celles de la mémoire sont gardées", e);
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (epoch == seen && pending.get() == 0) {
                    storage.replaceShared(Kind.IGNORED, ignored);
                    storage.replaceShared(Kind.PUNISHED, punished);
                }
            });
        } catch (IllegalPluginAccessException ignoredStop) {
            // plugin en cours d'arrêt
        }
    }

    /** Fil principal : changement fait par un autre serveur. */
    private void onMessage(String origin, JsonObject message) {
        String op = text(message, "op");
        if ("reload".equals(op)) {
            execute(this::reload);
            return;
        }
        Kind kind = Kind.of(text(message, "list"));
        String raw = text(message, "uuid");
        if (kind == null || raw == null || op == null) {
            return;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return;
        }
        epoch++;
        storage.applyShared(kind, uuid, op.equals("add"));
    }

    private void announce(Kind kind, String op, UUID uuid) {
        JsonObject message = new JsonObject();
        message.addProperty("list", kind.id());
        message.addProperty("op", op);
        message.addProperty("uuid", uuid.toString());
        bus.publish(channel, message);
    }

    private static String text(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    private void warn(String message, SQLException e) {
        long now = System.currentTimeMillis();
        if (now - lastWarning > 10_000L) {
            lastWarning = now;
            plugin.getLogger().log(Level.WARNING, message + " : " + e.getMessage());
        }
    }
}
