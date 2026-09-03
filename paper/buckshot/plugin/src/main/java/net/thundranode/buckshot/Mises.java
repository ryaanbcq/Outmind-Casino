package net.thundranode.buckshot;

import java.util.Locale;

/**
 * Lecture et affichage des montants de mise, memes conventions que le bot
 * Outmind : suffixes k/m/b, virgule ou point decimal, dollar tolere.
 */
public final class Mises {

    /**
     * Lit "5M", "1,5m", "500k", "40$", "$2.5b" ou "5000000".
     *
     * @return le montant en dollars, ou -1 si le texte n'est pas une mise --
     *         le chat normal doit passer, seul un montant lisible est capture.
     */
    public static long parser(String texte) {
        if (texte == null) return -1;
        String brut = texte.trim().toLowerCase(Locale.ROOT)
                .replace("$", "").replace(" ", "").replace(",", ".");
        if (brut.isEmpty()) return -1;
        long facteur = 1;
        char dernier = brut.charAt(brut.length() - 1);
        if (dernier == 'k') facteur = 1_000L;
        else if (dernier == 'm') facteur = 1_000_000L;
        else if (dernier == 'b') facteur = 1_000_000_000L;
        if (facteur > 1) brut = brut.substring(0, brut.length() - 1);
        if (brut.isEmpty() || !brut.matches("\\d+(\\.\\d+)?")) return -1;
        double valeur;
        try {
            valeur = Double.parseDouble(brut);
        } catch (NumberFormatException e) {
            return -1;
        }
        // Une decimale sans suffixe ("1.5") n'est pas un montant en dollars.
        if (facteur == 1 && brut.contains(".")) return -1;
        long montant = Math.round(valeur * facteur);
        return montant > 0 ? montant : -1;
    }

    /** Ecrit 5000000 en "5M", 1500000 en "1.5M", 500000 en "500K". */
    public static String formater(long montant) {
        if (montant >= 1_000_000_000L && montant % 100_000_000L == 0) {
            return sansZero(montant / 1_000_000_000.0) + "B";
        }
        if (montant >= 1_000_000L && montant % 100_000L == 0) {
            return sansZero(montant / 1_000_000.0) + "M";
        }
        if (montant >= 1_000L && montant % 100L == 0) {
            return sansZero(montant / 1_000.0) + "K";
        }
        return String.valueOf(montant);
    }

    private static String sansZero(double valeur) {
        return valeur == Math.floor(valeur)
                ? String.valueOf((long) valeur)
                : String.format(Locale.ROOT, "%.1f", valeur);
    }

    private Mises() {
    }
}
