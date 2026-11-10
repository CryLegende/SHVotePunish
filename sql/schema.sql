-- Table des listes de SHVotePunish avec SHNetwork. Le plugin la crée seul au démarrage si l'utilisateur a le droit CREATE ;
-- ce fichier sert à la créer à la main et à ne donner à l'utilisateur que les droits de lecture et d'écriture.
--
-- Utilisation (base bo_net pour les essais multi-Opale) :
--   mysql -u root < sql/schema.sql   puis remplacer CHANGE_ME par le mot de passe de config.yml

CREATE DATABASE IF NOT EXISTS bo_net;

CREATE TABLE IF NOT EXISTS bo_net.votepunish_players (
    list  VARCHAR(16)  NOT NULL,                       -- 'ignored' (ne peut pas voter) ou 'punished' (déjà sanctionné par un vote)
    uuid  CHAR(36)     NOT NULL,                       -- uuid « hors ligne » du joueur, le même sur tous les serveurs
    name  VARCHAR(32)  NOT NULL DEFAULT '',            -- dernier pseudo connu, vide pour une ligne importée
    since TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (list, uuid)                           -- INSERT IGNORE : un seul vote inscrit un joueur comme sanctionné
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE USER IF NOT EXISTS 'shvotepunish'@'127.0.0.1' IDENTIFIED BY 'CHANGE_ME';
GRANT SELECT, INSERT, DELETE ON bo_net.votepunish_players TO 'shvotepunish'@'127.0.0.1';
-- Registre des premières connexions (reseau.joueurs-connus.table), en lecture seule
GRANT SELECT ON Global.sh_track_firstseen TO 'shvotepunish'@'127.0.0.1';
