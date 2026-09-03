package net.thundranode.buckshot.ia;

import net.thundranode.buckshot.jeu.Chargeur;
import net.thundranode.buckshot.jeu.TypeCartouche;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertFalse;

class VueIAArchitectureTest {

    @Test
    void laVueNePeutPasTransporterLeChargeurOuUneSequenceDeCartouches() {
        for (RecordComponent composant : VueIA.class.getRecordComponents()) {
            assertFalse(Chargeur.class.isAssignableFrom(composant.getType()), composant.getName());
            if (Collection.class.isAssignableFrom(composant.getType())
                    && composant.getGenericType() instanceof ParameterizedType type) {
                for (java.lang.reflect.Type argument : type.getActualTypeArguments()) {
                    assertFalse(argument.getTypeName().equals(TypeCartouche.class.getName()),
                            "collection de cartouches interdite : " + composant.getName());
                }
            }
        }
    }
}
