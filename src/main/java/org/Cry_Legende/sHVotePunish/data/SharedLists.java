package org.Cry_Legende.sHVotePunish.data;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Listes des joueurs ignorés et sanctionnés tenues par tous les serveurs (mode réseau). Storage ne connaît que cette
 * interface : sans SHNetwork, elle n'existe pas et les listes restent dans les fichiers YAML.
 *
 * <p>Aucune méthode ne bloque : les écritures partent sur un fil à part, dans l'ordre des appels.</p>
 */
public interface SharedLists {

    /** Les deux listes du plugin. */
    enum Kind {
        IGNORED("ignored"),
        PUNISHED("punished");

        private final String id;

        Kind(String id) {
            this.id = id;
        }

        /** Valeur écrite dans la colonne list de la base et dans les messages du bus. */
        public String id() {
            return id;
        }

        public static Kind of(String id) {
            for (Kind kind : values()) {
                if (kind.id.equals(id)) {
                    return kind;
                }
            }
            return null;
        }
    }

    /** Ajoute le joueur à la liste, en base et chez les autres serveurs. Le pseudo peut être null. */
    void add(Kind kind, UUID uuid, String name);

    /** Retire le joueur de la liste, en base et chez les autres serveurs. */
    void remove(Kind kind, UUID uuid);

    /**
     * Inscrit le joueur comme sanctionné en une seule instruction atomique (INSERT IGNORE).
     *
     * @return vrai si cet appel a inscrit la ligne, faux si un autre vote l'avait déjà fait ; en erreur, la base est injoignable
     */
    CompletableFuture<Boolean> claimPunished(UUID uuid, String name);
}
