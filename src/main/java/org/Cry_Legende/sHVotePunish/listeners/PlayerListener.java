package org.Cry_Legende.sHVotePunish.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.Cry_Legende.sHVotePunish.SHVotePunish;
import org.Cry_Legende.sHVotePunish.managers.VoteManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Garde les derniers messages de chaque joueur (pour le webhook et le menu de vote) et annule le vote d'une cible qui
 * se déconnecte. Les messages refusés par le filtre du chat sont gardés aussi, marqués comme bloqués.
 */
public class PlayerListener implements Listener {

    /** Message de chat gardé. */
    private record Message(String texte, boolean bloque) {
    }

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final SHVotePunish plugin;
    private final VoteManager voteManager;

    // Le chat arrive hors du fil principal : file concurrente par joueur
    private final Map<UUID, Deque<Message>> recentMessages = new ConcurrentHashMap<>();
    private volatile int maxMessagesToStore;

    public PlayerListener(SHVotePunish plugin) {
        this.plugin = plugin;
        this.voteManager = plugin.getVoteManager();
        this.maxMessagesToStore = plugin.getConfig().getInt("discord_webhook.last_messages_limit", 10);
    }

    /** Relit la limite de messages gardés (rechargement) ; ceux déjà gardés restent. */
    public void refreshLimit() {
        this.maxMessagesToStore = plugin.getConfig().getInt("discord_webhook.last_messages_limit", 10);
    }

    /** Lu en dernier (MONITOR), annulé compris : le texte est celui que les autres voient, ou le message bloqué. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChat(AsyncChatEvent event) {
        int limite = Math.max(maxMessagesToStore, 5);
        Deque<Message> messages = recentMessages.computeIfAbsent(event.getPlayer().getUniqueId(), k -> new ConcurrentLinkedDeque<>());
        messages.addLast(new Message(PLAIN.serialize(event.message()), event.isCancelled()));
        while (messages.size() > limite) messages.pollFirst();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        // Si le joueur qui se déconnecte est la cible d'un vote, on annule le vote.
        // Avec SHNetwork, une sortie peut n'être qu'un changement de serveur : le serveur qui porte le vote
        // surveille la présence de la cible sur le réseau (NetworkVotes) et l'annule seulement si elle est partie.
        boolean network = plugin.getNetworkVotes() != null;
        VoteManager.ActiveVote vote = voteManager.getActiveVote(playerUUID);
        if (vote != null && !network) {
            voteManager.endVote(vote, false);
            plugin.getServer().broadcastMessage(voteManager.cancelledMessage(player.getName()));
        }

        // Les derniers messages de la cible d'un vote porté ici restent pour le webhook (réseau : elle a pu changer de serveur)
        if (network && vote != null && !vote.isMirror()) {
            return;
        }
        recentMessages.remove(playerUUID);
    }

    /** Oublie les derniers messages d'un joueur (vote terminé alors qu'il n'est plus sur ce serveur). */
    public void forget(UUID uuid) {
        recentMessages.remove(uuid);
    }

    /** Derniers messages pour le webhook du staff, messages bloqués compris (marqués). */
    public String getFormattedRecentMessages(UUID uuid) {
        Deque<Message> messages = recentMessages.get(uuid);
        if (messages == null || messages.isEmpty()) {
            return "Aucun message récent enregistré.";
        }
        StringBuilder formattedMessages = new StringBuilder();
        int ignores = Math.max(0, messages.size() - maxMessagesToStore);
        for (Message msg : messages) {
            if (ignores-- > 0) continue;
            // Pas d'échappement JSON ici : JSONObject#put s'en charge
            formattedMessages.append(msg.bloque() ? "🚫 `" : "`").append(msg.texte().replace('`', '\'')).append("`\n");
        }
        return formattedMessages.toString();
    }

    /**
     * Lignes du menu de vote : les {@code max} derniers messages, du plus ancien au plus récent. Un message bloqué
     * n'est pas recopié : on montre {@code bloque} à sa place.
     */
    public List<String> menuLines(UUID uuid, int max, String format, String bloque) {
        Deque<Message> messages = recentMessages.get(uuid);
        List<String> lignes = new ArrayList<>();
        if (messages == null) return lignes;
        Iterator<Message> it = messages.descendingIterator();
        while (it.hasNext() && lignes.size() < max) {
            Message msg = it.next();
            String texte = msg.texte().length() > 40 ? msg.texte().substring(0, 40) + "…" : msg.texte();
            lignes.add(0, msg.bloque() ? bloque : format.replace("{message}", texte.replace('§', '&')));
        }
        return lignes;
    }
}
