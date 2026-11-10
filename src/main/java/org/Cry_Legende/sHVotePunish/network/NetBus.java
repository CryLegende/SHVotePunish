package org.Cry_Legende.sHVotePunish.network;

import com.google.gson.JsonObject;

import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Ce dont le vote en réseau a besoin du bus. Seule {@link ShNetworkBus} connaît SHNetwork : sans lui, aucune
 * classe de ce paquet n'est chargée.
 */
public interface NetBus {

    /** Joueur connecté au réseau et serveur où il se trouve. */
    record Peer(UUID uuid, String name, String server) {
    }

    /** Nom de ce serveur sur le réseau. */
    String serverName();

    /** Cherche un joueur connecté, sur ce serveur ou sur un autre (pseudo sans tenir compte de la casse, ou UUID). */
    Optional<Peer> find(String nameOrUuid);

    /** Nombre de joueurs connectés sur tout le réseau, ce serveur compris. */
    int online();

    /** Publie sur un canal, à destination des autres serveurs (pas de ce serveur). Ne bloque jamais. */
    void publish(String channel, JsonObject message);

    /** Écoute un canal sur le fil principal ; le premier argument est le serveur qui a publié. */
    void listen(String channel, BiConsumer<String, JsonObject> handler);

    /** Exécute une commande console là où se trouve le joueur, ou à sa prochaine connexion s'il est hors ligne. */
    void runFor(String player, String command);
}
