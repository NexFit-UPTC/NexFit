package com.nexfit.dashboard;

import com.nexfit.dashboard.DashboardService.GymPanel;
import com.nexfit.dashboard.DashboardService.PlatformFigures;
import com.nexfit.shared.Accion;
import com.nexfit.shared.ActiveOrganization;
import com.nexfit.shared.Funcion;
import com.nexfit.shared.RequiresAccess;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Indicadores (US-45, US-46): /api/dashboard. El panel del gimnasio es del Administrador y usa siempre el gimnasio
 * activo de la sesión; las cifras globales son solo del Master y no listan personas.
 */
@RestController
@RequestMapping("/dashboard")
class DashboardController {

    private final DashboardService dashboard;

    DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @RequiresAccess(funcion = Funcion.INDICADORES, accion = Accion.CONSULTAR)
    @GetMapping("/gym")
    GymPanel gym() {
        return dashboard.gym(ActiveOrganization.id());
    }

    @RequiresAccess(funcion = Funcion.INDICADORES, accion = Accion.CONSULTAR_AGREGADO)
    @GetMapping("/platform")
    PlatformFigures platform() {
        return dashboard.platform();
    }
}
