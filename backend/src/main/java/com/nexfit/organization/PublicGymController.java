package com.nexfit.organization;

import com.nexfit.organization.PublicGymService.GymPage;
import com.nexfit.shared.AnyAuthenticated;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Gimnasios visibles sin sesión (NEX-97): /api/public/gyms. Solo lectura y solo datos públicos. */
@RestController
@RequestMapping("/public/gyms")
class PublicGymController {

    private final PublicGymService gyms;

    PublicGymController(PublicGymService gyms) {
        this.gyms = gyms;
    }

    // Declarativo: estas dos rutas GET son públicas por SecurityConfig, no por esta anotación.
    @AnyAuthenticated
    @GetMapping
    GymPage list(@RequestParam(defaultValue = "0") int page,
                 @RequestParam(defaultValue = "" + PublicGymService.DEFAULT_SIZE) int size) {
        return gyms.list(page, size);
    }

    // Declarativo: ruta pública por SecurityConfig.
    @AnyAuthenticated
    @GetMapping("/{id}")
    ResponseEntity<?> get(@PathVariable long id) {
        return gyms.find(id)
            .map(gym -> ResponseEntity.ok((Object) gym))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
