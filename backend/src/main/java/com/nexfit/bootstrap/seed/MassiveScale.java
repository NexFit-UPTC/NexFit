package com.nexfit.bootstrap.seed;

import java.util.Locale;

/** Escalas del conjunto {@code massive} (variable {@code NEXFIT_SEED_SCALE}). */
enum MassiveScale {

    SMALL(3, 2, 1_500, 40_000),
    MEDIUM(8, 3, 8_000, 350_000),
    LARGE(15, 3, 30_000, 1_500_000);

    final int gyms;
    final int branchesPerGym;
    final int clients;
    final long checkIns;

    MassiveScale(int gyms, int branchesPerGym, int clients, long checkIns) {
        this.gyms = gyms;
        this.branchesPerGym = branchesPerGym;
        this.clients = clients;
        this.checkIns = checkIns;
    }

    static MassiveScale parse(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "small" -> SMALL;
            case "medium" -> MEDIUM;
            case "large" -> LARGE;
            default -> throw new IllegalArgumentException(
                "NEXFIT_SEED_SCALE debe ser small, medium o large (recibido: " + value + ").");
        };
    }
}
