package net.thundranode.buckshot.paper;

record ChronologieTir(int viseeTicks, int attenteAvantResolutionTicks, int blackoutTicks) {

    static ChronologieTir creer(int viseeTicks, boolean cartoucheReelle, int blackoutConfigure) {
        if (viseeTicks <= 0 || blackoutConfigure <= 0) {
            throw new IllegalArgumentException("chronologie invalide");
        }
        return new ChronologieTir(viseeTicks, 10, cartoucheReelle ? blackoutConfigure : 0);
    }

    static int animationApresClicAnnule(int dureeTicks) {
        if (dureeTicks <= 0) throw new IllegalArgumentException("durée invalide");
        return dureeTicks + 1;
    }
}
