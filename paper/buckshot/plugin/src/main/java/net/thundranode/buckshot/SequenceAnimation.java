package net.thundranode.buckshot;

import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

public record SequenceAnimation(List<String> images, int ticksParImage) {

    public static SequenceAnimation creer(String nom, Etats.Etat etat) {
        Objects.requireNonNull(nom, "nom");
        Objects.requireNonNull(etat, "etat");
        if (nom.isBlank() || etat.frames() <= 0 || etat.ticksParFrame() <= 0) {
            throw new IllegalArgumentException("sequence d'animation invalide");
        }
        return new SequenceAnimation(IntStream.range(0, etat.frames())
                .mapToObj(i -> nom + "_" + i)
                .toList(), etat.ticksParFrame());
    }

    public SequenceAnimation {
        images = List.copyOf(images);
    }

    public int dureeTicks() {
        return images.size() * ticksParImage;
    }
}
