#!/usr/bin/env python3
"""Fusionne un pack de base et un overlay sans collision silencieuse."""

import argparse
import hashlib
import posixpath
import zipfile
from pathlib import Path, PurePosixPath

DATE_FIXE = (1980, 1, 1, 0, 0, 0)


def chemin_sur(nom):
    nom = nom.replace("\\", "/")
    normalise = posixpath.normpath(nom).lstrip("/")
    if not normalise or normalise == "." or normalise.startswith("../"):
        raise ValueError(f"chemin zip dangereux: {nom!r}")
    return normalise


def lire_base(fichier):
    entrees = {}
    with zipfile.ZipFile(fichier) as archive:
        for info in archive.infolist():
            if info.is_dir():
                continue
            nom = chemin_sur(info.filename)
            if nom in entrees:
                raise ValueError(f"doublon dans le pack de base: {nom}")
            entrees[nom] = archive.read(info)
    if "pack.mcmeta" not in entrees:
        raise ValueError("pack.mcmeta absent du pack de base")
    return entrees


def lire_overlay(dossier):
    dossier = Path(dossier)
    entrees = {}
    for chemin in sorted(p for p in dossier.rglob("*") if p.is_file()):
        nom = chemin_sur(chemin.relative_to(dossier).as_posix())
        if nom == "pack.mcmeta":
            continue  # Le pack de production reste l'autorité sur son format.
        entrees[nom] = chemin.read_bytes()
    return entrees


def fusionner(base, overlay, sortie, remplacer_prefixe=None):
    """Fusionne ; une collision non identique est une erreur, sauf sous
    `remplacer_prefixe` (ex. "assets/rr/", notre namespace : l'overlay y fait
    autorite, ce qui permet de repartir du pack fusionne en production)."""
    entrees = lire_base(base)
    ajouts = lire_overlay(overlay)
    for nom, contenu in ajouts.items():
        if nom in entrees and entrees[nom] != contenu:
            if not (remplacer_prefixe and nom.startswith(remplacer_prefixe)):
                raise ValueError(f"collision non identique: {nom}")
        entrees[nom] = contenu

    sortie = Path(sortie)
    sortie.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(sortie, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for nom in sorted(entrees):
            info = zipfile.ZipInfo(PurePosixPath(nom).as_posix(), DATE_FIXE)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, entrees[nom])
    digest = hashlib.sha1(sortie.read_bytes()).hexdigest()
    return len(entrees), sortie.stat().st_size, digest


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True, type=Path)
    parser.add_argument("--overlay", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--remplacer", default=None,
                        help="prefixe zip ou l'overlay ecrase la base (ex. assets/rr/)")
    args = parser.parse_args()
    nombre, taille, digest = fusionner(args.base, args.overlay, args.output, args.remplacer)
    print(f"{args.output}: {nombre} fichiers, {taille} octets, sha1={digest}")


if __name__ == "__main__":
    main()
