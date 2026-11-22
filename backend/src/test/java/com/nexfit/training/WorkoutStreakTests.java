package com.nexfit.training;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Cálculo puro de la racha (RN-42): sin base de datos. */
class WorkoutStreakTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 7);

    @Test
    void sinEntrenamientosLaRachaEsCero() {
        assertThat(WorkoutService.streak(List.of(), TODAY)).isZero();
    }

    @Test
    void cuentaLosDiasSeguidosTerminandoHoy() {
        assertThat(WorkoutService.streak(List.of(TODAY, TODAY.minusDays(1), TODAY.minusDays(2)), TODAY)).isEqualTo(3);
    }

    @Test
    void siElUltimoFueAyerLaRachaSigueViva() {
        assertThat(WorkoutService.streak(List.of(TODAY.minusDays(1), TODAY.minusDays(2)), TODAY)).isEqualTo(2);
    }

    @Test
    void siElUltimoFueAntesDeAyerLaRachaEsCero() {
        assertThat(WorkoutService.streak(List.of(TODAY.minusDays(2), TODAY.minusDays(3)), TODAY)).isZero();
    }

    @Test
    void unHuecoCortaLaRacha() {
        assertThat(WorkoutService.streak(List.of(TODAY, TODAY.minusDays(2), TODAY.minusDays(3)), TODAY)).isEqualTo(1);
    }

    @Test
    void cruzaLimitesDeMesYAnio() {
        LocalDate newYear = LocalDate.of(2027, 1, 1);
        assertThat(WorkoutService.streak(List.of(newYear, LocalDate.of(2026, 12, 31), LocalDate.of(2026, 12, 30)),
            newYear)).isEqualTo(3);
    }
}
