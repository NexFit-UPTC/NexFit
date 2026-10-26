package com.nexfit;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Falla si un módulo usa clases internas de otro módulo (RNF-05).
 */
class ModularityTests {

    @Test
    void losModulosRespetanSusLimites() {
        ApplicationModules.of(NexfitApplication.class).verify();
    }
}
