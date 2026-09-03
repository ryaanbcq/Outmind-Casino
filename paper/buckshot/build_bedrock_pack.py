#!/usr/bin/env python3
"""Construit le pack Bedrock OutMind (.mcpack) pour Geyser.

Phase 1 (2026-08-29) : les SONS uniquement. Les joueurs Bedrock n'entendent
aujourd'hui ni musiques, ni voix de DrDonutt, ni tirs custom : Geyser fait
suivre tel quel tout identifiant de son inconnu (« rr:music.dealer »), il
suffit donc que le pack Bedrock definisse des evenements du MEME nom.

Le pack se depose ensuite dans plugins/Geyser-Spigot/packs/ sur le serveur
(force-resource-packs est deja actif) ; Geyser ne le charge qu'au demarrage.

Phase 2 (2026-08-29) : icones 2D des items custom (rendues par
tools/rendu_icone.py depuis les modeles Java, deposees dans build/icones/) +
item_texture.json. Le fichier de mappings Geyser (custom_mappings) est genere
a cote du pack, dans build/outmind_mappings.json. Sans attachable 3D, Bedrock
affiche l'icone en main comme un sprite Java : deja infiniment mieux que
l'algue. Regenerer une icone : rendu_icone.py item/mallette build/icones/mallette.png 225 30
(fusil : item/anim/hold_0, vue 155 25).
"""
import json
import os
import sys as _sys
_sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shutil
import sys
import uuid
import zipfile

RACINE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_SOUNDS = os.path.join(RACINE, "resourcepack", "assets", "rr", "sounds")
SRC_SOUNDS_JSON = os.path.join(RACINE, "resourcepack", "assets", "rr", "sounds.json")
BUILD = os.path.join(RACINE, "build", "bedrock-pack")
MCPACK = os.path.join(RACINE, "build", "OutMind.mcpack")

# UUIDs STABLES : Bedrock identifie un pack par son uuid, en changer ferait
# retelecharger et dupliquer le pack chez les joueurs. Ne jamais les regenerer.
UUID_HEADER = "7c1f4e62-9b3a-4a1e-8f2d-0e5a6c1b9d47"
UUID_MODULE = "b02d7a19-4c8e-4f5b-a6d1-3e9f8c2a5b60"
VERSION = [1, 4, 0]


def construire():
    if os.path.exists(BUILD):
        shutil.rmtree(BUILD)
    os.makedirs(os.path.join(BUILD, "sounds"))

    # manifest
    manifest = {
        "format_version": 2,
        "header": {
            "name": "OutMind Casino",
            "description": "Sounds of the OutMind Buckshot experience",
            "uuid": UUID_HEADER,
            "version": VERSION,
            "min_engine_version": [1, 20, 0],
        },
        "modules": [{
            "type": "resources",
            "uuid": UUID_MODULE,
            "version": VERSION,
        }],
    }
    with open(os.path.join(BUILD, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=2)

    # sons : copie du dossier Java tel quel sous sounds/rr/
    shutil.copytree(SRC_SOUNDS, os.path.join(BUILD, "sounds", "rr"))

    # sound_definitions : memes noms d'evenements que cote Java, prefixe
    # namespace compris (« rr:music.dealer ») -- c'est la cle que Geyser
    # transmet au client Bedrock dans le paquet PlaySound.
    java = json.load(open(SRC_SOUNDS_JSON))
    definitions = {}
    for evenement, contenu in java.items():
        sons = []
        for son in contenu.get("sounds", []):
            if isinstance(son, str):
                nom, extra = son, {}
            else:
                nom, extra = son.get("name", ""), son
            # « rr:voix/gotshot_0 » -> « sounds/rr/voix/gotshot_0 »
            chemin = "sounds/rr/" + nom.split(":", 1)[-1]
            entree = {"name": chemin}
            if extra.get("stream"):
                entree["stream"] = True
                entree["load_on_low_memory"] = True
            if "volume" in extra:
                entree["volume"] = extra["volume"]
            if "pitch" in extra:
                entree["pitch"] = extra["pitch"]
            sons.append(entree)
        definitions["rr:" + evenement] = {
            "category": "music" if evenement.startswith("music.") else "player",
            "sounds": sons,
        }
    with open(os.path.join(BUILD, "sounds", "sound_definitions.json"), "w") as f:
        json.dump({
            "format_version": "1.20.20",
            "sound_definitions": definitions,
        }, f, indent=2)

    # icones des items custom + atlas item_texture.json
    icones = {
        "rr.shotgun": "shotgun.png",
        "rr.cigarette": "cigarette_s0.png",
        "rr.paquet_cigarettes": "paquet_cigarettes.png",
        "rr.mallette": "mallette.png",
        "rr.biere": "biere.png",
        "rr.menottes": "menottes.png",
        "rr.shell": "shell.png",
        "rr.defib": "defib.png",
        "rr.vide": "vide.png",
    }
    src_icones = os.path.join(RACINE, "build", "icones")
    os.makedirs(os.path.join(BUILD, "textures", "items"), exist_ok=True)
    texture_data = {}
    for cle, fichier in icones.items():
        nom = cle.replace(".", "_")
        shutil.copy(os.path.join(src_icones, fichier),
                    os.path.join(BUILD, "textures", "items", nom + ".png"))
        texture_data[cle] = {"textures": "textures/items/" + nom}
    with open(os.path.join(BUILD, "textures", "item_texture.json"), "w") as f:
        json.dump({
            "resource_pack_name": "OutMind Casino",
            "texture_name": "atlas.items",
            "texture_data": texture_data,
        }, f, indent=2)

    # mappings Geyser v2 : java item + item_model -> item custom Bedrock.
    # Va dans plugins/Geyser-Spigot/custom_mappings/ sur le serveur.
    def definition(modele, icone, **options):
        return {
            "type": "definition",
            "model": "rr:" + modele,
            "bedrock_identifier": "rr:" + modele,
            "bedrock_options": {"icon": icone, **options},
        }
    mappings = {
        "format_version": 2,
        "items": {
            "minecraft:kelp": [
                definition("shotgun", "rr.shotgun",
                           display_handheld=True, allow_offhand=False),
            ],
            "minecraft:paper": [
                definition("cigarette", "rr.cigarette"),
                definition("paquet_cigarettes", "rr.paquet_cigarettes"),
                definition("mallette", "rr.mallette"),
                definition("shell", "rr.shell"),
                definition("defib", "rr.defib"),
                # l item CUFFED est un paper au modele menottes
                {"type": "definition", "model": "rr:menottes",
                 "bedrock_identifier": "rr:menottes_papier",
                 "bedrock_options": {"icon": "rr.menottes"}},
            ],
            # l'arbalete de pose du dealer (bras leves) est invisible cote
            # Java via le pack ; sans mapping, Bedrock montrait une vraie
            # crossbow pendant la visee. Icone transparente = disparue.
            "minecraft:crossbow": [
                {"type": "definition", "model": "rr:shotgun",
                 "bedrock_identifier": "rr:shotgun_cache",
                 "bedrock_options": {"icon": "rr.vide"}},
            ],
            "minecraft:potion": [definition("biere", "rr.biere")],
            "minecraft:tripwire_hook": [definition("menottes", "rr.menottes")],
        },
    }
    with open(os.path.join(RACINE, "build", "outmind_mappings.json"), "w") as f:
        json.dump(mappings, f, indent=2)
    print("mappings :", os.path.join(RACINE, "build", "outmind_mappings.json"))

    # attachable 3D du fusil (phase 2B) : geometrie + animations + liaison
    # a l'item custom rr:shotgun, converties du modele Java par java_vers_geo.
    import importlib
    import java_vers_geo
    java_vers_geo.convertir("item/anim/hold_0", "rr:shotgun", 64, BUILD)

    # zip -> .mcpack (un mcpack est un zip avec manifest a la racine)
    if os.path.exists(MCPACK):
        os.remove(MCPACK)
    with zipfile.ZipFile(MCPACK, "w", zipfile.ZIP_DEFLATED) as z:
        for dossier, _, fichiers in os.walk(BUILD):
            for fichier in fichiers:
                complet = os.path.join(dossier, fichier)
                z.write(complet, os.path.relpath(complet, BUILD))
    taille = os.path.getsize(MCPACK)
    print(f"OK : {MCPACK} ({taille:,} octets, {len(definitions)} evenements)")


if __name__ == "__main__":
    construire()
