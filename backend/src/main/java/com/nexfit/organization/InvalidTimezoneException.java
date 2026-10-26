package com.nexfit.organization;

/** La base de datos rechazó la zona horaria (respaldo de la validación con ZoneId). */
class InvalidTimezoneException extends RuntimeException {

    InvalidTimezoneException() {
        super("Zona horaria inválida");
    }
}
