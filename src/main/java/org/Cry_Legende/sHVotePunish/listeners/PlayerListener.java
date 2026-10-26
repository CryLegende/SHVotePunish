package org.Cry_Legende.sHVotePunish.listeners;

import org.Cry_Legende.sHVotePunish.SHVotePunish;
import org.Cry_Legende.sHVotePunish.managers.VoteManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Gère les événements liés aux joueurs, comme le chat et la déconnexion.
 */
public class PlayerListener implements Listener {

    private final SHVotePunish plugin;
    private final VoteManager voteManager;

    // Utilise une structure de données thread-safe car l'événement de chat est asynchrone.
    // La clé est l'UUID du joueur, la valeur est une liste de ses messages récents.
    private final Map<UUID, Queue<String>> recentMessages = new ConcurrentHashMap<>();
    private final int maxMessagesToStore;

    public PlayerListener(SHVotePunish plugin) {
        this.plugin = plugin;
        this.voteManager = plugin.getVoteManager();
        // Récupère la limite depuis la config, avec une valeur par défaut de 10.
        this.maxMessagesToStore = plugin.getConfig().getInt("discord_webhook.last_messages_limit", 10);
    }

    /**
     * Capture les messages des joueurs pour les stocker.
     * La priorité est MONITEUR pour s'assurer qu'on lit le message final,
     * après que d'autres plugins aient pu le modifier ou l'annuler.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (maxMessagesToStore <= 0) return; // Ne fait rien si la fonctionnalité est désactivée.

        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();
        String message = event.getMessage();

        // Récupère la liste des messages du joueur, ou en crée une nouvelle si elle n'existe pas.
        // File concurrente : le webhook la lit depuis un autre thread pendant que le chat y écrit
        Queue<String> messages = recentMessages.computeIfAbsent(playerUUID, k -> new ConcurrentLinkedQueue<>());

        // Ajoute le nouveau message.
        messages.add(message);

        // Si la liste dépasse la taille maximale, on retire le message le plus ancien.
        while (messages.size() > maxMessagesToStore) {
            messages.poll();
        }
    }

    /**
     * Gère la déconnexion d'un joueur.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        // Si le joueur qui se déconnecte est la cible d'un vote, on annule le vote.
        VoteManager.ActiveVote vote = voteManager.getActiveVote(playerUUID);
        if (vote != null) {
            voteManager.endVote(vote, false); // Annule le vote, le considérant comme un échec.
            plugin.getServer().broadcastMessage(plugin.getConfigManager().getPrefix() +
                "§cLe vote contre §e" + player.getName() + "§c a été annulé car il s'est déconnecté.");
        }

        // Nettoie les messages stockés pour le joueur qui se déconnecte pour libérer de la mémoire.
        recentMessages.remove(playerUUID);
    }

    /**
     * Permet au VoteManager de récupérer les derniers messages d'un joueur.
     *
     * @param uuid L'UUID du joueur.
     * @return Une liste formatée des derniers messages, ou un message par défaut.
     */
    public String getFormattedRecentMessages(UUID uuid) {
        Queue<String> messages = recentMessages.get(uuid);
        if (messages == null || messages.isEmpty()) {
            return "Aucun message récent enregistré.";
        }

        // Utilise un StringBuilder pour une construction de chaîne efficace.
        StringBuilder formattedMessages = new StringBuilder();
        for (String msg : messages) {
            // Pas d'échappement JSON ici : JSONObject#put s'en charge, un second passage doublait les \ et les "
            formattedMessages.append("`").append(msg).append("`\n");
        }
        return formattedMessages.toString();
    }
}
