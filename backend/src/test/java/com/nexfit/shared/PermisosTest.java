package com.nexfit.shared;

import static com.nexfit.shared.Accion.*;
import static com.nexfit.shared.Funcion.*;
import static com.nexfit.shared.Rol.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.nexfit.ReglaDeNegocio;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Celdas críticas de la matriz 3.7 (función + acción → roles). */
class PermisosTest {

    private static Set<Rol> roles(Funcion f, Accion a) {
        return Permisos.rolesPermitidos(f, a);
    }

    @Test
    void todaFuncionTieneAlgunPermiso() {
        for (Funcion f : Funcion.values()) {
            assertThat(java.util.Arrays.stream(Accion.values()).anyMatch(a -> !roles(f, a).isEmpty()))
                .as("función sin ningún permiso: %s", f).isTrue();
        }
    }

    @Test
    @ReglaDeNegocio("RN-08")
    void elMasterNoAccedeADatosDeClientesSoloAAgregados() {
        for (Funcion f : List.of(CLIENTES, PAGOS, RUTINAS, ASISTENCIA, EPS_Y_CONTACTOS, MEMBRESIAS)) {
            for (Accion a : Accion.values()) {
                if (a != CONSULTAR_AGREGADO) {
                    assertThat(roles(f, a)).as("%s %s", f, a).doesNotContain(MASTER);
                }
            }
        }
        assertThat(roles(MEMBRESIAS, CONSULTAR_AGREGADO)).contains(MASTER);
        assertThat(roles(PAGOS, CONSULTAR_AGREGADO)).doesNotContain(MASTER);
    }

    @Test
    @ReglaDeNegocio("RN-24")
    void lasRutinasSoloLasTocanElEntrenadorYElCliente() {
        for (Accion a : Accion.values()) {
            assertThat(roles(RUTINAS, a)).as("%s", a).isSubsetOf(TRAINER, CLIENT);
        }
        assertThat(roles(RUTINAS, CONSULTAR)).containsExactlyInAnyOrder(TRAINER, CLIENT);
        assertThat(roles(RUTINAS, CREAR)).containsExactly(TRAINER);
    }

    @Test
    @ReglaDeNegocio("RN-07")
    void elEntrenadorNoCreaClientesPeroElStaffSi() {
        assertThat(roles(CLIENTES, CREAR)).containsExactly(STAFF);
        assertThat(roles(CLIENTES, CONSULTAR)).contains(TRAINER, STAFF);
    }

    @Test
    @ReglaDeNegocio("RN-06")
    void elClienteNoAccedeAPersonalYRolesNiCreaPlanes() {
        for (Accion a : Accion.values()) {
            assertThat(roles(PERSONAL_Y_ROLES, a)).as("%s", a).doesNotContain(CLIENT);
        }
        assertThat(roles(PLANES, CREAR)).containsExactly(ADMIN);
        assertThat(roles(PLANES, MODIFICAR)).containsExactly(ADMIN);
        assertThat(roles(PLANES, CONSULTAR)).contains(CLIENT);
    }

    @Test
    @ReglaDeNegocio("RN-03")
    void losPermisosSonLaUnionDeLosRolesDelEstablecimiento() {
        var adminYStaff = List.of(new SimpleGrantedAuthority("admin:7"), new SimpleGrantedAuthority("staff:7"));
        assertThat(Permisos.permite(PLANES, CREAR, adminYStaff)).isTrue();
        assertThat(Permisos.permite(PAGOS, ANULAR, adminYStaff)).isTrue();
        assertThat(Permisos.permite(RUTINAS, CONSULTAR, adminYStaff)).isFalse();
        // El rol de Administrador por sí solo no concede permisos operativos.
        assertThat(Permisos.permite(MEMBRESIAS, CREAR, List.of(new SimpleGrantedAuthority("admin:7")))).isFalse();
        assertThat(Permisos.permite(PLANES, CREAR, List.of(new SimpleGrantedAuthority("PLATFORM_ADMIN")))).isFalse();
    }
}
