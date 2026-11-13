<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHVotePunish

*Un système de sanction par vote communautaire.*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Un système de sanction par vote communautaire.

Utilisé sur : **Opale**. Avec `SHNetwork`, un seul vote par joueur visé vaut pour tous les serveurs Opale (voir « Mode réseau »).

## Dépendances

- Obligatoires : `Vault`
- Optionnelles : `SHLogs`, `SHNetwork`, `SHSkins` (têtes des joueurs dans les menus avec leur skin, sinon têtes sans texture : Steve ou Alex)

## Commandes

| Commande | Alias | Description | Permission |
|---|---|---|---|
| `/votepunish <joueur>` | `/punish` | Lance un vote contre le joueur, ou ouvre le menu du vote en cours. | `permissions.use` (`shvotepunish.use`) |
| `/shvotepunish ignore <joueur>` | — | Interdit au joueur de participer aux votes. | `shvotepunish.admin.ignore` |
| `/shvotepunish unignore <joueur>` | — | Autorise de nouveau le joueur à participer aux votes. | `shvotepunish.admin.unignore` |
| `/shvotepunish unpunish <joueur>` | — | Permet de nouveau un vote contre un joueur déjà sanctionné. | `shvotepunish.admin.unpunish` |
| `/shvotepunish import` | — | Avec SHNetwork : copie `ignored_players.yml` et `punished_players.yml` dans la base du réseau. | `shvotepunish.admin.import` |
| `/shvotepunish list <ignored\|punished> [page]` | — | Liste les joueurs interdits de vote, ou déjà sanctionnés par un vote. | `shvotepunish.admin.list` |
| `/shvotepunish info <joueur>` | — | Dit pourquoi un joueur ne peut pas lancer, voter ou être visé. | `shvotepunish.admin.info` |
| `/shvotepunish reload` | — | Relit `config.yml` et `messages.yml` ; un fichier illisible ne change rien, un vote en cours garde ses réglages, les listes ne sont jamais touchées. | `shvotepunish.admin.reload` |

Les anciennes formes `/votepunish ignore|unignore|unpunish|import <joueur>` marchent toujours, avec les mêmes droits. Les sous-commandes de `/shvotepunish` marchent aussi depuis la console.

## Permissions

Vérifiées avec Vault ; les noms de `use`, `bypass`, `admin` et `eligibility_bypass` se règlent dans la section `permissions` du `config.yml` (valeurs par défaut ci-dessous).

| Permission | Description | Par défaut |
|---|---|---|
| `shvotepunish.use` | Lancer un vote et voter. | `op` |
| `shvotepunish.bypass` | Ne peut pas être la cible d'un vote. | `op` |
| `shvotepunish.admin` | Toutes les sous-commandes de `/shvotepunish`. | `op` |
| `shvotepunish.admin.ignore`, `.unignore`, `.unpunish`, `.import`, `.reload`, `.list`, `.info` | Une par sous-commande du même nom. | `op` |

## Mode réseau (SHNetwork)

Quand `SHNetwork` est installé et que `reseau.actif` vaut `true`, tous les serveurs partagent les votes et les listes. Sans lui, le plugin se comporte comme sur un serveur seul : un vote ne concerne que les joueurs du serveur et les listes restent dans `ignored_players.yml` et `punished_players.yml`.

- **Serveur porteur.** Le vote contre un joueur est porté par le serveur où il se trouve au lancement. Lui seul compte les votants, tranche la réussite, vérifie les immunités (`bypass`, `eligibility_bypass`, `blacklisted_players`, déjà sanctionné) avec les vraies permissions du joueur, écrit le fichier `votes` de SHLogs et envoie le webhook. Les votes contre des joueurs différents restent indépendants, comme sur un serveur seul ; il ne peut pas y avoir deux votes contre le même joueur.
- **Lancer et voter depuis l'autre serveur.** `/votepunish <joueur>` sur un joueur d'un autre serveur interroge le serveur porteur (immunités), ouvre le menu de confirmation, puis lui envoie le lancement. Chaque vote cliqué dans le menu part vers le porteur, qui ne compte qu'un vote par joueur (UUID).
- **Mêmes annonces et mêmes menus.** Le porteur publie l'état complet du vote à chaque changement ; les autres serveurs en gardent une copie, annoncent le début et la fin avec les mêmes textes et mettent à jour les menus ouverts. Si le porteur s'arrête, les copies disparaissent ; sans message de fin, une copie s'efface toute seule peu après la fin prévue du vote.
- **Votes requis.** `required_votes.type: PERCENTAGE` se compte sur tous les joueurs connectés au réseau (`online()` de SHNetwork). `FIXED` est inchangé.
- **Serveur qui ne répond pas.** Un joueur connecté sur un serveur sans SHVotePunish ne peut pas être visé : sans réponse au bout de `reseau.delai-reponse-ms`, `/votepunish` répond « pas en ligne ».
- **Cible qui change de serveur.** Le vote continue : le porteur surveille la présence de la cible sur le réseau et ne l'annule que si elle en reste absente `reseau.absence-max-secondes` secondes (déconnexion).
- **Sanction appliquée une fois.** À la réussite, la base tranche par un `INSERT IGNORE` de la ligne `punished` : un seul vote l'emporte pour tout le réseau. `vote.command_on_success` est alors exécutée une fois, par la console du serveur où se trouve la cible, ou par `runFor` de SHNetwork si elle a changé de serveur (ou à sa prochaine connexion si elle est partie). Une base injoignable ne bloque pas la sanction.
- **Derniers messages du webhook.** Ce sont ceux écrits depuis l'arrivée de la cible sur le serveur porteur ; ils sont gardés jusqu'à la fin du vote même si elle change de serveur. La date de première connexion vient du registre `reseau.joueurs-connus.table`.

### Listes en base

La table `votepunish_players (list, uuid, name, since)` de la base `bo_net` contient les joueurs `ignored` et `punished`. Un ajout est un `INSERT IGNORE` et un retrait un `DELETE` d'un seul joueur, puis un message sur le canal `<canal>.liste` que les autres serveurs appliquent à leur mémoire. Les listes ne sont plus écrites dans les fichiers. Une relecture toutes les `reseau.rechargement-listes-secondes` secondes rattrape un message perdu.

`/shvotepunish import` reprend les deux fichiers dans la base (à faire une fois, sur un serveur, au premier démarrage en réseau : tant que la base est vide, les joueurs déjà sanctionnés des fichiers ne sont pas pris en compte) : un joueur déjà inscrit n'est pas modifié, donc la commande peut être relancée. `ignore`, `unignore` et `unpunish` (`/shvotepunish`) retrouvent le joueur parmi les connectés du réseau, puis dans le registre des premières connexions, sans interroger Mojang.

### Installation

1. Base : `mysql -u root < sql/schema.sql` (crée `bo_net.votepunish_players` et l'utilisateur `shvotepunish`). Remplacer `CHANGE_ME` par le mot de passe.
2. `plugins/SHVotePunish/config.yml`, section `reseau` : `url` et `mot-de-passe`. La même base sur tous les serveurs, la même configuration de vote.
3. Poser le jar sur chaque serveur, avec SHNetwork. Le jar charge HikariCP et le pilote MariaDB par `libraries:` (le serveur ne les embarque pas) ; sans SHNetwork, ils ne sont pas utilisés.
4. `/shvotepunish import` sur un serveur.

Au démarrage, la console indique `SHNetwork détecté : un vote par joueur visé pour tout le réseau`.

## Configuration

Fichiers par défaut, avec des valeurs d'exemple. Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHVotePunish/
├── <a href="src/main/resources/config.yml">config.yml</a>
└── <a href="src/main/resources/messages.yml">messages.yml</a>
</pre>

Le début, la participation et le résultat de chaque vote sont écrits dans le fichier `votes` du dossier `SHVotePunish` de SHLogs (`plugins/SHLogs/`), avec les lignes `log_*` de la section `messages`. Sans SHLogs, elles passent dans la console.

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`.

SHSkins doit être installé dans le dépôt Maven local (`mvn install` dans `SHSkins-Velocity`).

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
