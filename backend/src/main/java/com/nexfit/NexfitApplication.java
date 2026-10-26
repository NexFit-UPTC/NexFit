package com.nexfit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Punto de entrada de la API.
 *
 * Cada subpaquete directo de com.nexfit es un módulo del dominio
 * (Spring Modulith). Ver docs/CONVENCIONES.md.
 */
@SpringBootApplication
public class NexfitApplication {

    public static void main(String[] args) {
        SpringApplication.run(NexfitApplication.class, args);
    }
}
