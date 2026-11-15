package org.Cry_Legende.sHVotePunish.managers;

import org.Cry_Legende.sHVotePunish.SHVotePunish;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Garde-fous des votes (section protections de config.yml) : staff connecté, temps de jeu, délais entre deux votes
 * lancés par un même joueur, plafond par jour, un vote par adresse IP.
 */
public final class Protections {

    private final SHVotePunish plugin;
    private final Map<UUID, Deque<Long>> lancements = new ConcurrentHashMap<>();
    private final Map<UUID, Long> echecs = new ConcurrentHashMap<>();

    public Protections(SHVotePunish plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration config() {
        return plugin.getConfig();
    }

    /** Clé de messages.yml qui explique pourquoi ce joueur ne peut pas lancer de vote, ou null. */
    public String refusLancement(Player initiateur) {
        if (plugin.hasPermission(initiateur, plugin.getConfigManager().getAdminPermission())) return null;
        if (config().getBoolean("protections.bloquer-si-staff-connecte", true) && staffConnecte()) return "refus_staff_connecte";
        if (minutesDeJeu(initiateur) < config().getInt("protections.temps-de-jeu-lancer-minutes", 120)) return "refus_temps_de_jeu";
        long maintenant = System.currentTimeMillis();
        Long echec = echecs.get(initiateur.getUniqueId());
        if (echec != null && maintenant - echec < config().getLong("protections.delai-apres-echec-minutes", 30) * 60_000L) {
            return "refus_delai_echec";
        }
        Deque<Long> faits = lancements.get(initiateur.getUniqueId());
        if (faits != null) {
            synchronized (faits) {
                while (!faits.isEmpty() && maintenant - faits.peekFirst() > 86_400_000L) faits.removeFirst();
                Long dernier = faits.peekLast();
                if (dernier != null && maintenant - dernier < config().getLong("protections.delai-entre-lancements-minutes", 10) * 60_000L) {
                    return "refus_delai_lancement";
                }
                int plafond = config().getInt("protections.lancements-par-jour", 3);
                if (plafond > 0 && faits.size() >= plafond) return "refus_limite_jour";
            }
        }
        return null;
    }

    /** Clé de messages.yml qui explique pourquoi ce joueur ne peut pas voter, ou null. */
    public String refusVote(Player votant, VoteManager.ActiveVote vote) {
        if (plugin.hasPermission(votant, plugin.getConfigManager().getAdminPermission())) return null;
        if (minutesDeJeu(votant) < config().getInt("protections.temps-de-jeu-voter-minutes", 60)) return "refus_temps_de_jeu_vote";
        if (config().getBoolean("protections.un-vote-par-ip", true)) {
            String ip = ip(votant);
            if (ip != null && !vote.ajouterIp(ip)) return "refus_meme_ip";
        }
        return null;
    }

    public void noterLancement(UUID initiateur) {
        lancements.computeIfAbsent(initiateur, u -> new ArrayDeque<>());
        Deque<Long> faits = lancements.get(initiateur);
        synchronized (faits) {
            faits.addLast(System.currentTimeMillis());
        }
    }

    /** L'initiateur vote dès le lancement : son adresse compte pour ce vote. */
    public void noterIpInitiateur(Player initiateur, VoteManager.ActiveVote vote) {
        String ip = ip(initiateur);
        if (vote != null && ip != null) vote.ajouterIp(ip);
    }

    public void noterEchec(UUID initiateur) {
        echecs.put(initiateur, System.currentTimeMillis());
    }

    /** Un membre du staff est connecté sur ce serveur (vanish compris) et a agi dans les dernières minutes. */
    public boolean staffConnecte() {
        String permission = config().getString("protections.permission-staff", "shvotepunish.staff");
        long actif = config().getLong("protections.staff-actif-minutes", 10);
        for (Player joueur : Bukkit.getOnlinePlayers()) {
            if (joueur.hasPermission(permission) && (actif <= 0 || joueur.getIdleDuration().toMinutes() < actif)) return true;
        }
        return false;
    }

    private static long minutesDeJeu(Player joueur) {
        return joueur.getStatistic(Statistic.PLAY_ONE_MINUTE) / 1200L;
    }

    private static String ip(Player joueur) {
        InetSocketAddress adresse = joueur.getAddress();
        return adresse == null || adresse.getAddress() == null ? null : adresse.getAddress().getHostAddress();
    }
}
