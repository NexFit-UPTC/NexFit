package com.nexfit.administration;

import com.nexfit.administration.HealthInsuranceService.Provider;
import com.nexfit.shared.Accion;
import com.nexfit.shared.Funcion;
import com.nexfit.shared.RequiresAccess;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lectura del catálogo de EPS: GET /api/health-insurance-providers. Es un catálogo global (no depende del
 * gimnasio) y sirve para elegir la EPS al registrar o editar un cliente. Crear, editar y activar EPS es
 * US-18 (NEX-30) y es solo del Master.
 */
@RestController
@RequestMapping("/health-insurance-providers")
class HealthInsuranceController {

    private final HealthInsuranceService providers;

    HealthInsuranceController(HealthInsuranceService providers) {
        this.providers = providers;
    }

    /** EPS activas por nombre. */
    @RequiresAccess(funcion = Funcion.CATALOGO_EPS, accion = Accion.CONSULTAR)
    @GetMapping
    List<Provider> list() {
        return providers.listActive();
    }
}
