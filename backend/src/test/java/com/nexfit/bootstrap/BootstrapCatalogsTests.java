package com.nexfit.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Valida los archivos de catálogo sin necesitar base de datos (corre aunque no haya Docker). */
class BootstrapCatalogsTests {

    @Test
    void elCatalogoDeEpsEsValidoYNoRepiteNombres() {
        List<String> eps = BootstrapCatalogs.epsNames();

        assertThat(eps).hasSizeGreaterThanOrEqualTo(20);
        assertThat(eps).contains("Nueva EPS");
        assertThat(eps).allSatisfy(name -> assertThat(name).isNotBlank().hasSizeLessThanOrEqualTo(150));
        assertThat(eps.stream().map(n -> n.toLowerCase(Locale.ROOT)).distinct().count()).isEqualTo(eps.size());
    }

    @Test
    void elCatalogoDeEjerciciosEsValidoYNoRepiteNombres() {
        List<BootstrapCatalogs.Exercise> exercises = BootstrapCatalogs.globalExercises();

        assertThat(exercises).hasSizeGreaterThanOrEqualTo(30);
        assertThat(exercises).allSatisfy(e -> {
            assertThat(e.name()).isNotBlank().hasSizeLessThanOrEqualTo(100);
            assertThat(e.muscleGroup()).isNotBlank().hasSizeLessThanOrEqualTo(100);
            assertThat(e.description()).isNotBlank();
        });
        assertThat(exercises.stream().map(e -> e.name().toLowerCase(Locale.ROOT)).distinct().count())
            .isEqualTo(exercises.size());
    }
}
