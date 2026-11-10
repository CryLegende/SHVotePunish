package org.Cry_Legende.sHVotePunish.network;

import com.google.gson.JsonObject;
import org.Cry_Legende.sHNetwork.api.SHNetworkAPI;

import java.util.Optional;
import java.util.function.BiConsumer;

/** Pont vers SHNetwork. Cette classe n'est chargée que si SHNetwork est installé. */
public final class ShNetworkBus implements NetBus {

    private final SHNetworkAPI api;

    private ShNetworkBus() {
        this.api = SHNetworkAPI.get();
    }

    /** Point d'entrée : le plugin ne résout cette classe qu'à cet appel, donc jamais sans SHNetwork. */
    public static NetBus open() {
        return new ShNetworkBus();
    }

    @Override
    public String serverName() {
        return api.serverName();
    }

    @Override
    public Optional<Peer> find(String nameOrUuid) {
        return api.find(nameOrUuid).map(p -> new Peer(p.uuid(), p.name(), p.server()));
    }

    @Override
    public int online() {
        return api.online().size();
    }

    @Override
    public void publish(String channel, JsonObject message) {
        api.publish(channel, message.toString(), false);
    }

    @Override
    public void listen(String channel, BiConsumer<String, JsonObject> handler) {
        api.subscribe(channel, message -> {
            JsonObject json;
            try {
                json = message.object();
            } catch (RuntimeException e) {
                return; // message illisible : le bus ne s'arrête jamais pour lui
            }
            handler.accept(message.origin(), json);
        }, true);
    }

    @Override
    public void runFor(String player, String command) {
        api.runFor(player, command);
    }
}
