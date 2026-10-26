package com.nexfit.bootstrap;

import java.time.LocalDate;

/** Datos para crear la cuenta del Administrador Master. La contraseña es temporal (D-13, RNF-14). */
record MasterInput(String email, String documentType, String documentNumber,
                   String firstName, String lastName, LocalDate birthDate,
                   String temporaryPassword) {

    /** Nunca imprime la contraseña, aunque alguien registre el objeto por error. */
    @Override
    public String toString() {
        return "MasterInput[email=%s, documentType=%s, documentNumber=%s, temporaryPassword=****]"
            .formatted(email, documentType, documentNumber);
    }
}
