package com.nexfit.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class MasterInputReaderTests {

    @Test
    void leeCorreoYContrasenaDelEntornoYUsaValoresPorDefectoParaElResto() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("NEXFIT_MASTER_EMAIL", " master@ejemplo.test ")
            .withProperty("NEXFIT_MASTER_PASSWORD", "Una-Clave-Temporal-1");

        MasterInput input = new MasterInputReader(env, null).read();

        assertThat(input.email()).isEqualTo("master@ejemplo.test");
        assertThat(input.temporaryPassword()).isEqualTo("Una-Clave-Temporal-1");
        assertThat(input.documentType()).isEqualTo("CC");
        assertThat(input.birthDate()).isEqualTo(LocalDate.of(1970, 1, 1));
    }

    @Test
    void sinVariablesYSinTerminalFallaIndicandoQueFalta() {
        assertThatThrownBy(() -> new MasterInputReader(new MockEnvironment(), null).read())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("NEXFIT_MASTER_EMAIL");
    }

    @Test
    void sinContrasenaEnElEntornoLaPideDeFormaInteractivaYLaConfirma() {
        MockEnvironment env = new MockEnvironment().withProperty("NEXFIT_MASTER_EMAIL", "master@ejemplo.test");
        Deque<String> answers = new ArrayDeque<>(java.util.List.of("Clave-Interactiva-1", "Clave-Interactiva-1"));

        MasterInput input = new MasterInputReader(env, (label, secret) -> answers.pop()).read();

        assertThat(input.temporaryPassword()).isEqualTo("Clave-Interactiva-1");
    }

    @Test
    void siLaConfirmacionNoCoincideRechaza() {
        MockEnvironment env = new MockEnvironment().withProperty("NEXFIT_MASTER_EMAIL", "master@ejemplo.test");
        Deque<String> answers = new ArrayDeque<>(java.util.List.of("Clave-Interactiva-1", "Otra-Clave-Distinta"));

        assertThatThrownBy(() -> new MasterInputReader(env, (label, secret) -> answers.pop()).read())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no coinciden");
    }

    @Test
    void unaFechaMalFormadaSeRechazaConUnMensajeClaro() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("NEXFIT_MASTER_EMAIL", "master@ejemplo.test")
            .withProperty("NEXFIT_MASTER_PASSWORD", "Una-Clave-Temporal-1")
            .withProperty("NEXFIT_MASTER_BIRTH_DATE", "01/02/1990");

        assertThatThrownBy(() -> new MasterInputReader(env, null).read())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("AAAA-MM-DD");
    }

    @Test
    void laRepresentacionEnTextoNuncaMuestraLaContrasena() {
        MasterInput input = new MasterInput("m@ejemplo.test", "CC", "1", "A", "B",
            LocalDate.of(1990, 1, 1), "Una-Clave-Temporal-1");

        assertThat(input.toString()).doesNotContain("Una-Clave-Temporal-1");
    }
}
