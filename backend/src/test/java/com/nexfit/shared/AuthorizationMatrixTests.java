package com.nexfit.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.nexfit.ReglaDeNegocio;
import com.nexfit.shared.AccessFixtures.Account;
import com.nexfit.shared.AccessFixtures.Org;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * NEX-28 (TT-11): suite de aislamiento y autorización. Invoca cada endpoint de
 * la aplicación con cada uno de
 * los 5 roles, sin sesión y con recursos de otro establecimiento, para
 * demostrar 0 accesos indebidos (E1, RNF-01).
 *
 * <p>
 * Cómo funciona:
 * <ul>
 * <li>{@link #cases()} es el registro de endpoints: para cada uno declara qué
 * roles deja pasar la matriz 3.7
 * (escrito aquí a mano, independiente del código) y, si recibe un
 * identificador, cómo apuntarlo a un
 * recurso del establecimiento B.</li>
 * <li>Las fábricas dinámicas recorren el registro: sin sesión → 401; con sesión
 * pero sin permiso → 403 y ningún
 * cambio en la base; con permiso sobre un recurso de otro establecimiento →
 * rechazo idéntico al de un id
 * inexistente, sin revelar nada y sin cambios.</li>
 * <li>{@link #todoEndpointTieneCasoEnLaSuite()} recorre los endpoints reales de
 * la aplicación y falla si alguno
 * no está en el registro (o si el registro no coincide con la matriz). <b>Si
 * agregas un endpoint, agrégalo
 * en {@link #cases()}</b>: sin eso, este test falla y lo reporta como no
 * cubierto.</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AuthorizationMatrixTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    private AccessFixtures fx;

    /**
     * Datos de A y B: se crean una sola vez (la primera prueba que los necesita) y
     * los comparte toda la clase.
     */
    private static Ctx ctx;
    private static List<Case> cases;

    // ---- modelo
    // ----------------------------------------------------------------------------

    /**
     * Roles que se prueban con sesión del establecimiento A (el master no pertenece
     * a ninguno).
     */
    private static final List<Rol> TENANT_ROLES = List.of(Rol.ADMIN, Rol.STAFF, Rol.TRAINER, Rol.CLIENT);

    private static final Set<Rol> ALL = EnumSet.allOf(Rol.class);

    private static final String SECRET_PLAN = "Plan secreto B";
    private static final String SECRET_BRANCH = "Sede secreta B";
    private static final String SECRET_EVENT = "evento-secreto-B";
    private static final String SECRET_CLASS = "Clase secreta B";

    /**
     * Datos de dos establecimientos (A y B) y una sesión por rol en A. B es "el
     * otro establecimiento".
     */
    private record Ctx(Org a, Org b, Map<Rol, Account> accounts, Map<Rol, Cookie> cookies,
            long planA, long planB, long branchA, long branchB, long classB, long userB, long memberB) {
    }

    private enum Kind {
        PUBLIC, ANY_AUTHENTICATED, GATED
    }

    /**
     * Cómo apuntar el endpoint a un recurso del establecimiento B y a uno
     * inexistente.
     * {@code statuses}: respuestas aceptables al intentarlo con un rol de A que
     * pasa la compuerta.
     */
    private record Foreign(Function<Ctx, String> uri, Function<Ctx, String> missingUri,
            Function<Ctx, String> body, Function<Ctx, String> missingBody,
            Set<Integer> statuses) {
    }

    private record Case(String key, Kind kind, Set<Rol> gate, Function<Ctx, String> uri,
            Function<Ctx, String> body, Foreign foreign, boolean freshSession) {
        HttpMethod method() {
            return HttpMethod.valueOf(key.substring(0, key.indexOf(' ')));
        }
    }

    private static Set<Rol> roles(Rol... roles) {
        return EnumSet.copyOf(List.of(roles));
    }

    private static Case gated(String key, Set<Rol> gate, String uri, String body) {
        return new Case(key, Kind.GATED, gate, c -> uri, c -> body, null, false);
    }

    private static Case gated(String key, Set<Rol> gate, Function<Ctx, String> uri, String body, Foreign foreign) {
        return new Case(key, Kind.GATED, gate, uri, c -> body, foreign, false);
    }

    private static Case any(String key, String uri, Function<Ctx, String> body, Foreign foreign, boolean fresh) {
        return new Case(key, Kind.ANY_AUTHENTICATED, ALL, c -> uri, body, foreign, fresh);
    }

    /** El identificador está en la ruta: {@code template} lleva un %d. */
    private static Foreign byPath(String template, ToLongFunction<Ctx> id, String body, Set<Integer> statuses) {
        return new Foreign(c -> template.formatted(id.applyAsLong(c)),
                c -> template.formatted(id.applyAsLong(c) + 1_000_000), c -> body, c -> body, statuses);
    }

    /** El identificador viaja en el cuerpo: {@code bodyTemplate} lleva un %d. */
    private static Foreign byBody(String uri, String bodyTemplate, ToLongFunction<Ctx> id, Set<Integer> statuses) {
        return new Foreign(c -> uri, c -> uri, c -> bodyTemplate.formatted(id.applyAsLong(c)),
                c -> bodyTemplate.formatted(id.applyAsLong(c) + 1_000_000), statuses);
    }

    private static final Set<Integer> NOT_FOUND = Set.of(404);
    private static final Set<Integer> FORBIDDEN_OR_NOT_FOUND = Set.of(403, 404);

    private static final String PLAN_BODY = "{\"name\":\"Plan matriz\",\"price\":1000,\"durationDays\":30}";
    private static final String BRANCH_BODY = "{\"name\":\"Sede matriz\",\"addressLine1\":\"Calle 1\","
            + "\"city\":\"Tunja\",\"department\":\"Boyacá\"}";
    private static final String CLASS_BODY_TEMPLATE = "{\"name\":\"Clase matriz\",\"branchId\":%d,"
            + "\"startsAt\":\"2031-03-10T18:00:00-05:00\",\"durationMinutes\":60,\"capacity\":10}";
    private static final String ESTABLISHMENT_BODY = "{\"name\":\"Gimnasio matriz\",\"contactEmail\":\"matriz@nexfit.test\",\"timezone\":\"America/Bogota\"}";

    private static final String STAFF_BODY = "{\"documentType\":\"CC\",\"documentNumber\":\"TEST-MATRIZ-STAFF-1\",\"firstName\":\"Nuevo\","
            + "\"lastName\":\"Staff\",\"birthDate\":\"1990-01-01\",\"email\":\"matriz.staff@nexfit.test\","
            + "\"roles\":[\"staff\"]}";

    /**
     * Registro de endpoints de la aplicación. La clave es "MÉTODO ruta" tal como la
     * declara el controlador.
     * El conjunto de roles es lo que la matriz 3.7 deja pasar a ese endpoint.
     */
    private static final String CLIENT_BODY = "{\"documentType\":\"CC\",\"documentNumber\":\"TEST-MATRIZ-CLIENTE-1\",\"firstName\":\"Nuevo\","
            + "\"lastName\":\"Cliente\",\"birthDate\":\"1990-01-01\",\"email\":\"matriz.cliente@nexfit.test\","
            + "\"phone\":\"3001234567\",\"epsId\":1,"
            + "\"emergencyContact\":{\"fullName\":\"Luis Pérez\",\"relationship\":\"Padre\",\"phone\":\"3000000000\"},"
            + "\"healthConsent\":true,\"privacyAccepted\":true}";
    private static final String CONTACT_BODY = "{\"phone\":\"3001234567\"}";

    /** Pago pendiente del gimnasio B (NEX-45): ningún rol de A puede verlo, anularlo ni completarlo. */
    private long paymentB;

    /** Membresía del gimnasio B (NEX-49): indistinguible de una inexistente para los roles de A. */
    private long membershipB;

    /** Reserva confirmada del gimnasio B (NEX-58): indistinguible de una inexistente para los roles de A. */
    private long bookingB;

    /** Ingreso abierto del gimnasio B (NEX-59): indistinguible de uno inexistente para los roles de A. */
    private long checkInB;

    private List<Case> cases() {
        List<Case> list = new ArrayList<>();

        // --- Establecimientos (solo el Master; el Admin consulta el suyo según la
        // matriz) ---
        list.add(gated("POST /establishments", roles(Rol.MASTER), "/establishments", ESTABLISHMENT_BODY));
        list.add(gated("GET /establishments", roles(Rol.MASTER, Rol.ADMIN), "/establishments", null));
        list.add(gated("GET /establishments/{id}", roles(Rol.MASTER, Rol.ADMIN),
                c -> "/establishments/" + c.b().id(), null,
                byPath("/establishments/%d", c -> c.b().id(), null, FORBIDDEN_OR_NOT_FOUND)));
        list.add(gated("PUT /establishments/{id}", roles(Rol.MASTER),
                c -> "/establishments/" + c.b().id(), ESTABLISHMENT_BODY,
                byPath("/establishments/%d", c -> c.b().id(), ESTABLISHMENT_BODY, FORBIDDEN_OR_NOT_FOUND)));
        // Con {"active":true} sobre un establecimiento activo no cambia nada: el
        // registro no puede dejar B inactivo.
        list.add(gated("PATCH /establishments/{id}/active", roles(Rol.MASTER),
                c -> "/establishments/" + c.b().id() + "/active", "{\"active\":true}",
                byPath("/establishments/%d/active", c -> c.b().id(), "{\"active\":true}", FORBIDDEN_OR_NOT_FOUND)));
        list.add(gated("POST /establishments/{orgId}/admin", roles(Rol.MASTER),
                c -> "/establishments/" + c.b().id() + "/admin",
                "{\"documentType\":\"CC\",\"documentNumber\":\"TEST-MATRIZ-1\",\"firstName\":\"Nuevo\","
                        + "\"lastName\":\"Admin\",\"birthDate\":\"1990-01-01\",\"email\":\"matriz.admin@nexfit.test\"}",
                byPath("/establishments/%d/admin", c -> c.b().id(), "{}", FORBIDDEN_OR_NOT_FOUND)));
        list.add(gated("GET /establishments/{orgId}/admin/lookup", roles(Rol.MASTER),
                c -> "/establishments/" + c.b().id() + "/admin/lookup?documentType=CC&documentNumber=TEST-MATRIZ-2",
                null,
                byPath("/establishments/%d/admin/lookup?documentType=CC&documentNumber=TEST-MATRIZ-2",
                        c -> c.b().id(), null, FORBIDDEN_OR_NOT_FOUND)));

        // --- Autenticación y cuenta propia ---
        list.add(new Case("POST /auth/login", Kind.PUBLIC, Set.of(), c -> "/auth/login", c -> "{}", null, false));
        // Públicos de solo lectura (NEX-97): los abre SecurityConfig; el handler lleva
        // @AnyAuthenticated.
        list.add(new Case("GET /public/gyms", Kind.PUBLIC, Set.of(), c -> "/public/gyms", c -> null, null, false));
        list.add(new Case("GET /public/gyms/{id}", Kind.PUBLIC, Set.of(), c -> "/public/gyms/" + c.a().id(),
                c -> null, null, false));
        list.add(any("GET /auth/establishments", "/auth/establishments", c -> null, null, false));
        list.add(any("POST /auth/establishment", "/auth/establishment", c -> "{\"organizationId\":" + c.b().id() + "}",
                byBody("/auth/establishment", "{\"organizationId\":%d}", c -> c.b().id(), Set.of(400)), true));
        list.add(any("POST /auth/password", "/auth/password",
                c -> "{\"currentPassword\":\"incorrecta-123\",\"newPassword\":\"NuevaClave-Segura-456\"}", null,
                false));
        list.add(gated("POST /auth/users/{userId}/password-reset", roles(Rol.MASTER, Rol.ADMIN, Rol.STAFF),
                c -> "/auth/users/" + c.userB() + "/password-reset", null,
                byPath("/auth/users/%d/password-reset", c -> c.userB(), null, NOT_FOUND)));
        list.add(any("POST /auth/logout", "/auth/logout", c -> null, null, true));
        list.add(any("GET /me", "/me", c -> null, null, false));
        list.add(any("PATCH /me", "/me", c -> "{\"phone\":\"3001112233\"}", null, false));

        // --- Planes de membresía ---
        list.add(gated("POST /membership-plans", roles(Rol.ADMIN), "/membership-plans", PLAN_BODY));
        list.add(gated("GET /membership-plans", roles(Rol.ADMIN, Rol.STAFF, Rol.CLIENT), "/membership-plans", null));
        list.add(gated("GET /membership-plans/{id}", roles(Rol.ADMIN, Rol.STAFF, Rol.CLIENT),
                c -> "/membership-plans/" + c.planB(), null,
                byPath("/membership-plans/%d", c -> c.planB(), null, NOT_FOUND)));
        list.add(gated("PUT /membership-plans/{id}", roles(Rol.ADMIN),
                c -> "/membership-plans/" + c.planB(), PLAN_BODY,
                byPath("/membership-plans/%d", c -> c.planB(), PLAN_BODY, NOT_FOUND)));
        list.add(gated("PATCH /membership-plans/{id}/active", roles(Rol.ADMIN),
                c -> "/membership-plans/" + c.planB() + "/active", "{\"active\":false}",
                byPath("/membership-plans/%d/active", c -> c.planB(), "{\"active\":false}", NOT_FOUND)));

        // --- Sedes ---
        list.add(gated("POST /branches", roles(Rol.ADMIN), "/branches", BRANCH_BODY));
        list.add(gated("GET /branches", roles(Rol.ADMIN, Rol.STAFF, Rol.TRAINER, Rol.CLIENT), "/branches", null));
        list.add(gated("GET /branches/{id}", roles(Rol.ADMIN, Rol.STAFF, Rol.TRAINER, Rol.CLIENT),
                c -> "/branches/" + c.branchB(), null,
                byPath("/branches/%d", c -> c.branchB(), null, NOT_FOUND)));
        list.add(gated("PUT /branches/{id}", roles(Rol.ADMIN),
                c -> "/branches/" + c.branchB(), BRANCH_BODY,
                byPath("/branches/%d", c -> c.branchB(), BRANCH_BODY, NOT_FOUND)));
        list.add(gated("PATCH /branches/{id}/active", roles(Rol.ADMIN),
                c -> "/branches/" + c.branchB() + "/active", "{\"active\":false}",
                byPath("/branches/%d/active", c -> c.branchB(), "{\"active\":false}", NOT_FOUND)));

        // --- Auditoría ---
        list.add(gated("GET /audit/events", roles(Rol.MASTER, Rol.ADMIN), "/audit/events", null));

        // --- Personal y roles (NEX-36: solo ADMIN en los 4 verbos de PERSONAL_Y_ROLES)
        // ---
        // Sin Foreign: estos casos cubren 401 sin sesión, 403 para los demás roles y
        // que la
        // compuerta se abre para ADMIN. El aislamiento entre gimnasios ya lo cubre
        // StaffTests.
        list.add(gated("POST /staff", roles(Rol.ADMIN), "/staff", STAFF_BODY));
        list.add(gated("GET /staff", roles(Rol.ADMIN), "/staff", null));
        list.add(gated("GET /staff/{memberId}", roles(Rol.ADMIN),
                c -> "/staff/" + c.userB(), null, null));
        list.add(gated("PATCH /staff/{memberId}", roles(Rol.ADMIN),
                c -> "/staff/" + c.userB(), "{\"roles\":[\"trainer\"]}", null));
        list.add(gated("POST /staff/{memberId}/activate", roles(Rol.ADMIN),
                c -> "/staff/" + c.userB() + "/activate", null, null));
        list.add(gated("POST /staff/{memberId}/deactivate", roles(Rol.ADMIN),
                c -> "/staff/" + c.userB() + "/deactivate", null, null));

        // --- Clases (NEX-40) ---
        // La matriz CLASES da CREAR y MODIFICAR solo a ADMIN y CONSULTAR a los cuatro roles del gimnasio.
        // Los cuerpos usan la sede de A; PUT con una clase de B debe dar el mismo 404 que un id inexistente.
        list.add(new Case("POST /classes", Kind.GATED, roles(Rol.ADMIN), c -> "/classes",
                c -> CLASS_BODY_TEMPLATE.formatted(c.branchA()), null, false));
        list.add(gated("GET /classes", roles(Rol.ADMIN, Rol.STAFF, Rol.TRAINER, Rol.CLIENT), "/classes", null));
        list.add(gated("GET /classes/{id}", roles(Rol.ADMIN, Rol.STAFF, Rol.TRAINER, Rol.CLIENT),
                c -> "/classes/" + c.classB(), null,
                byPath("/classes/%d", c -> c.classB(), null, NOT_FOUND)));
        list.add(new Case("PUT /classes/{id}", Kind.GATED, roles(Rol.ADMIN), c -> "/classes/" + c.classB(),
                c -> CLASS_BODY_TEMPLATE.formatted(c.branchA()),
                new Foreign(c -> "/classes/" + c.classB(), c -> "/classes/" + (c.classB() + 1_000_000),
                        c -> CLASS_BODY_TEMPLATE.formatted(c.branchA()),
                        c -> CLASS_BODY_TEMPLATE.formatted(c.branchA()), NOT_FOUND),
                false));

        // --- Pagos (NEX-45, NEX-51, NEX-50, NEX-54) ---
        // PAGOS: CREAR solo STAFF; CONSULTAR ADMIN, STAFF y CLIENT; ANULAR y COMPLETAR ADMIN y STAFF.
        // El cuerpo de POST apunta a un cliente de B: para el Staff de A responde 400 (no existe en su gimnasio).
        // El pago de B es indistinguible de uno inexistente: 404.
        list.add(new Case("POST /payments", Kind.GATED, roles(Rol.STAFF), c -> "/payments",
                c -> "{\"memberId\":" + c.memberB() + ",\"concept\":\"Matriz\",\"amount\":1000,\"method\":\"cash\"}",
                null, false));
        list.add(gated("GET /payments", roles(Rol.ADMIN, Rol.STAFF, Rol.CLIENT), "/payments", null));
        list.add(gated("PATCH /payments/{id}/void", roles(Rol.ADMIN, Rol.STAFF),
                c -> "/payments/" + paymentB + "/void", "{\"reason\":\"matriz\"}",
                byPath("/payments/%d/void", c -> paymentB, "{\"reason\":\"matriz\"}", NOT_FOUND)));
        list.add(gated("PATCH /payments/{id}/complete", roles(Rol.ADMIN, Rol.STAFF),
                c -> "/payments/" + paymentB + "/complete", null,
                byPath("/payments/%d/complete", c -> paymentB, null, NOT_FOUND)));

        // --- Membresías (NEX-49, NEX-55, NEX-56, NEX-57, NEX-66, NEX-67) ---
        // MEMBRESIAS: CREAR y RENOVAR solo STAFF; CONSULTAR ADMIN, STAFF y CLIENT; SUSPENDER y CANCELAR ADMIN y STAFF.
        list.add(new Case("POST /memberships", Kind.GATED, roles(Rol.STAFF), c -> "/memberships",
                c -> "{\"memberId\":" + c.memberB() + ",\"planId\":" + c.planB() + "}", null, false));
        list.add(gated("POST /memberships/{id}/renew", roles(Rol.STAFF),
                c -> "/memberships/" + membershipB + "/renew", "{}",
                byPath("/memberships/%d/renew", c -> membershipB, "{}", NOT_FOUND)));
        list.add(gated("GET /memberships", roles(Rol.ADMIN, Rol.STAFF, Rol.CLIENT), "/memberships", null));
        list.add(gated("GET /memberships/{id}", roles(Rol.ADMIN, Rol.STAFF, Rol.CLIENT),
                c -> "/memberships/" + membershipB, null,
                byPath("/memberships/%d", c -> membershipB, null, NOT_FOUND)));
        list.add(gated("PATCH /memberships/{id}/suspend", roles(Rol.ADMIN, Rol.STAFF),
                c -> "/memberships/" + membershipB + "/suspend", "{\"reason\":\"matriz\"}",
                byPath("/memberships/%d/suspend", c -> membershipB, "{\"reason\":\"matriz\"}", NOT_FOUND)));
        list.add(gated("PATCH /memberships/{id}/reactivate", roles(Rol.ADMIN, Rol.STAFF),
                c -> "/memberships/" + membershipB + "/reactivate", "{\"reason\":\"matriz\"}",
                byPath("/memberships/%d/reactivate", c -> membershipB, "{\"reason\":\"matriz\"}", NOT_FOUND)));
        list.add(gated("PATCH /memberships/{id}/cancel", roles(Rol.ADMIN, Rol.STAFF),
                c -> "/memberships/" + membershipB + "/cancel", "{\"reason\":\"matriz\"}",
                byPath("/memberships/%d/cancel", c -> membershipB, "{\"reason\":\"matriz\"}", NOT_FOUND)));

        // --- Reservas y cancelación de clases (NEX-58, NEX-69, NEX-70, NEX-68) ---
        // RESERVAS: CREAR y CANCELAR STAFF y CLIENT; CONSULTAR ADMIN, STAFF, TRAINER y CLIENT. CLASES.CANCELAR: ADMIN.
        list.add(new Case("POST /bookings", Kind.GATED, roles(Rol.STAFF, Rol.CLIENT), c -> "/bookings",
                c -> "{\"classId\":" + c.classB() + "}", null, false));
        list.add(gated("GET /bookings", roles(Rol.ADMIN, Rol.STAFF, Rol.TRAINER, Rol.CLIENT), "/bookings", null));
        list.add(gated("PATCH /bookings/{id}/cancel", roles(Rol.STAFF, Rol.CLIENT),
                c -> "/bookings/" + bookingB + "/cancel", null,
                byPath("/bookings/%d/cancel", c -> bookingB, null, NOT_FOUND)));
        list.add(gated("PATCH /classes/{id}/cancel", roles(Rol.ADMIN),
                c -> "/classes/" + c.classB() + "/cancel", "{}",
                byPath("/classes/%d/cancel", c -> c.classB(), "{}", NOT_FOUND)));

        // --- Asistencia (NEX-59, NEX-72, NEX-73) ---
        // ASISTENCIA: CREAR y MODIFICAR STAFF y CLIENT; CONSULTAR ADMIN, STAFF, TRAINER y CLIENT.
        list.add(new Case("POST /check-ins", Kind.GATED, roles(Rol.STAFF, Rol.CLIENT), c -> "/check-ins",
                c -> "{\"branchId\":" + c.branchB() + "}", null, false));
        list.add(gated("GET /check-ins", roles(Rol.ADMIN, Rol.STAFF, Rol.TRAINER, Rol.CLIENT), "/check-ins", null));
        list.add(gated("PATCH /check-ins/{id}/check-out", roles(Rol.STAFF, Rol.CLIENT),
                c -> "/check-ins/" + checkInB + "/check-out", null,
                byPath("/check-ins/%d/check-out", c -> checkInB, null, NOT_FOUND)));

        // --- Indicadores (NEX-74, NEX-62) ---
        // INDICADORES: CONSULTAR ADMIN; CONSULTAR_AGREGADO MASTER.
        list.add(gated("GET /dashboard/gym", roles(Rol.ADMIN), "/dashboard/gym", null));
        list.add(gated("GET /dashboard/platform", roles(Rol.MASTER), "/dashboard/platform", null));

        // --- Clientes (NEX-39) ---
        // POST /clients: solo STAFF crea (matriz CLIENTES/CREAR).
        // GET /clients/search: la matriz da CONSULTAR a ADMIN, STAFF, TRAINER y CLIENT,
        // pero el servicio limita la búsqueda a Staff (RF-90); por eso el caso lleva
        // todos los roles de CONSULTAR y el test positivo solo exige que no sea 401 ni 5xx.
        list.add(gated("POST /clients", roles(Rol.STAFF), "/clients", CLIENT_BODY));
        list.add(gated("GET /clients/search", roles(Rol.ADMIN, Rol.STAFF, Rol.TRAINER, Rol.CLIENT),
                "/clients/search?documentType=CC&documentNumber=TEST-MATRIZ-CLIENTE-1&documentVerified=true&personPresent=true",
                null));

        // --- Catálogo de EPS, solo lectura (NEX-89): la matriz CATALOGO_EPS/CONSULTAR da MASTER, ADMIN, STAFF
        // y CLIENT; es un catálogo global, sin recursos de un gimnasio que aislar. ---
        list.add(gated("GET /health-insurance-providers", roles(Rol.MASTER, Rol.ADMIN, Rol.STAFF, Rol.CLIENT),
                "/health-insurance-providers", null));

        // --- Búsqueda y detalle de clientes (NEX-43) ---
        // La matriz CLIENTES/CONSULTAR da ADMIN, STAFF, TRAINER y CLIENT, pero el servicio limita la
        // búsqueda y el detalle a Staff y Administrador: Entrenador y Cliente reciben 403 del servicio, que
        // el positivo tolera. Con un cliente de B, Staff y Admin reciben el mismo 404 que un id inexistente y
        // Entrenador y Cliente el mismo 403.
        list.add(gated("GET /clients", roles(Rol.ADMIN, Rol.STAFF, Rol.TRAINER, Rol.CLIENT), "/clients", null));
        list.add(gated("GET /clients/{memberId}", roles(Rol.ADMIN, Rol.STAFF, Rol.TRAINER, Rol.CLIENT),
                c -> "/clients/" + c.memberB(), null,
                byPath("/clients/%d", c -> c.memberB(), null, FORBIDDEN_OR_NOT_FOUND)));

        // --- Contacto del cliente (NEX-42) ---
        // GET: la matriz EPS_Y_CONTACTOS/CONSULTAR da ADMIN, STAFF, TRAINER y CLIENT,
        // pero el servicio solo deja consultar a Staff, Admin y al propio Cliente;
        // el positivo solo exige que no sea 401 ni 5xx, así que el 403 del servicio pasa.
        // PATCH: la matriz EPS_Y_CONTACTOS/MODIFICAR da STAFF y CLIENT; Admin y
        // Entrenador quedan fuera por la compuerta (403 ACCESS_DENIED).
        list.add(gated("GET /clients/{memberId}/contact",
                roles(Rol.ADMIN, Rol.STAFF, Rol.TRAINER, Rol.CLIENT),
                c -> "/clients/" + c.userB() + "/contact", null, null));
        list.add(gated("PATCH /clients/{memberId}/contact", roles(Rol.STAFF, Rol.CLIENT),
                c -> "/clients/" + c.userB() + "/contact", CONTACT_BODY, null));

        return list;
    }

    // ---- preparación
    // -----------------------------------------------------------------------

    /**
     * Debe llamarse al inicio de cada prueba: crea los datos la primera vez, cuando
     * la base ya está arriba.
     */
    private synchronized void prepare() throws Exception {
        fx = new AccessFixtures(jdbc, encoder, mvc);
        if (ctx != null) {
            return;
        }
        Org a = fx.orgWithAdmin();
        Org b = fx.orgWithAdmin();

        Map<Rol, Account> accounts = new EnumMap<>(Rol.class);
        accounts.put(Rol.MASTER, fx.user(true, true));
        accounts.put(Rol.ADMIN, a.admin());
        accounts.put(Rol.STAFF, fx.actor(a.id(), "staff"));
        accounts.put(Rol.TRAINER, fx.actor(a.id(), "trainer"));
        accounts.put(Rol.CLIENT, fx.actor(a.id(), "client"));
        Map<Rol, Cookie> cookies = new EnumMap<>(Rol.class);
        for (Map.Entry<Rol, Account> e : accounts.entrySet()) {
            cookies.put(e.getKey(), fx.login(e.getValue()));
        }

        long planA = plan(a.id(), "Plan propio A");
        long planB = plan(b.id(), SECRET_PLAN);
        long branchA = branch(a.id(), "Sede propia A");
        long branchB = branch(b.id(), SECRET_BRANCH);
        long classB = jdbc.queryForObject("""
                INSERT INTO classes (organization_id, branch_id, name, starts_at, duration_minutes, capacity, status)
                VALUES (?, ?, 'Clase secreta B', now() + interval '30 days', 60, 10, 'scheduled') RETURNING id
                """, Long.class, b.id(), branchB);
        Account clientB = fx.actor(b.id(), "client");
        long userB = clientB.id();
        long memberB = jdbc.queryForObject(
                "SELECT id FROM organization_members WHERE organization_id = ? AND user_id = ?", Long.class, b.id(),
                userB);
        jdbc.update("INSERT INTO audit_events (organization_id, event_type, metadata) "
                + "VALUES (?, 'LOGIN', CAST(? AS jsonb))", b.id(), "{\"secret\":\"" + SECRET_EVENT + "\"}");

        paymentB = jdbc.queryForObject("""
                INSERT INTO payments (organization_id, member_id, registered_by_member_id, concept, amount, paid_at,
                                      method, status)
                VALUES (?, ?, ?, 'Pago secreto B', 1000, now(), 'cash', 'pending') RETURNING id
                """, Long.class, b.id(), memberB, b.adminMemberId());

        membershipB = jdbc.queryForObject("""
                INSERT INTO memberships (organization_id, member_id, plan_id, created_by_member_id, start_date,
                                         end_date, status)
                VALUES (?, ?, ?, ?, CURRENT_DATE, CURRENT_DATE + 60, 'active') RETURNING id
                """, Long.class, b.id(), memberB, planB, b.adminMemberId());

        bookingB = jdbc.queryForObject("""
                INSERT INTO class_bookings (organization_id, class_id, member_id, status)
                VALUES (?, ?, ?, 'confirmed') RETURNING id
                """, Long.class, b.id(), classB, memberB);

        // Se fecha el ingreso mañana al mediodía: la membresía de B empieza con CURRENT_DATE (zona del servidor de BD)
        // y a ciertas horas la fecha local de Bogotá aún es la de ayer, lo que haría fallar el trigger por el reloj.
        checkInB = jdbc.queryForObject("""
                INSERT INTO check_ins (organization_id, member_id, branch_id, check_in_at, auto_closed)
                VALUES (?, ?, ?, (CURRENT_DATE + 1)::timestamp + interval '12 hours', false) RETURNING id
                """, Long.class, b.id(), memberB, branchB);

        ctx = new Ctx(a, b, accounts, cookies, planA, planB, branchA, branchB, classB, userB, memberB);
        cases = cases();
    }

    private long plan(long orgId, String name) {
        return jdbc.queryForObject("""
                INSERT INTO membership_plans (organization_id, name, price, duration_days, is_active)
                VALUES (?, ?, 50000, 30, true) RETURNING id
                """, Long.class, orgId, name);
    }

    private long branch(long orgId, String name) {
        return jdbc.queryForObject("""
                INSERT INTO branches (organization_id, name, address_line1, city, department, country, is_active)
                VALUES (?, ?, 'Calle 1', 'Tunja', 'Boyacá', 'Colombia', true) RETURNING id
                """, Long.class, orgId, name);
    }

    // ---- utilidades
    // ------------------------------------------------------------------------

    private MvcResult call(Case c, Cookie cookie, String uri, String body) throws Exception {
        MockHttpServletRequestBuilder builder = request(c.method(), uri);
        builder.with(csrf());
        if (cookie != null) {
            builder.cookie(cookie);
        }
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(builder).andReturn();
    }

    /**
     * Sesión del rol; los endpoints que cierran o cambian la sesión usan una sesión
     * nueva en cada llamada.
     */
    private Cookie session(Case c, Rol role) throws Exception {
        return c.freshSession() ? fx.login(ctx.accounts().get(role)) : ctx.cookies().get(role);
    }

    private static String bodyOf(MvcResult r) {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * Huella de las tablas que los endpoints pueden modificar: si una llamada
     * rechazada la cambia, hubo un
     * acceso indebido aunque la respuesta diga que no.
     */
    private String state() {
        return jdbc.queryForObject("""
                SELECT concat_ws('|',
                  (SELECT coalesce(md5(string_agg(concat_ws(',', id, organization_id, name, description, price,
                          duration_days, benefits, is_active), ';' ORDER BY id)), '') FROM membership_plans),
                  (SELECT coalesce(md5(string_agg(concat_ws(',', id, organization_id, name, phone, address_line1, city,
                          department, is_active), ';' ORDER BY id)), '') FROM branches),
                  (SELECT coalesce(md5(string_agg(concat_ws(',', id, name, contact_email, timezone, is_active),
                          ';' ORDER BY id)), '') FROM organizations),
                  (SELECT coalesce(md5(string_agg(concat_ws(',', id, email, password_hash, must_change_password,
                          is_active), ';' ORDER BY id)), '') FROM users),
                  (SELECT coalesce(md5(string_agg(concat_ws(',', id, organization_id, branch_id, trainer_member_id, name,
                          starts_at, duration_minutes, capacity, status), ';' ORDER BY id)), '') FROM classes),
                  (SELECT count(*) FROM organization_members),
                  (SELECT count(*) FROM member_roles))
                """, String.class);
    }

    // ---- criterio 2: sin sesión 401; con sesión sin permiso 403
    // ----------------------------

    // Sin sesión, todo endpoint (salvo el login, público) responde 401, aun con
    // token CSRF válido.
    @TestFactory
    Stream<DynamicTest> sinSesionCadaEndpointResponde401() throws Exception {
        prepare();
        return cases.stream().filter(c -> c.kind() != Kind.PUBLIC)
                .map(c -> DynamicTest.dynamicTest(c.key() + " · sin sesión", () -> {
                    String before = state();

                    MvcResult r = call(c, null, c.uri().apply(ctx), c.body().apply(ctx));

                    assertThat(r.getResponse().getStatus()).as(c.key()).isEqualTo(401);
                    assertThat(state()).as("sin sesión no cambia nada").isEqualTo(before);
                }));
    }

    // Con sesión, cada rol que la matriz no autoriza recibe 403 ACCESS_DENIED y no
    // cambia ningún dato.
    @TestFactory
    @ReglaDeNegocio("RN-06")
    Stream<DynamicTest> conSesionSinPermisoCadaEndpointYRolResponde403() throws Exception {
        prepare();
        List<DynamicTest> tests = new ArrayList<>();
        for (Case c : cases) {
            if (c.kind() != Kind.GATED) {
                continue;
            }
            for (Rol role : ALL) {
                if (c.gate().contains(role)) {
                    continue;
                }
                tests.add(DynamicTest.dynamicTest(c.key() + " · " + role + " → 403", () -> {
                    String before = state();

                    MvcResult r = call(c, session(c, role), c.uri().apply(ctx), c.body().apply(ctx));

                    assertThat(r.getResponse().getStatus()).as(c.key() + " como " + role).isEqualTo(403);
                    assertThat(bodyOf(r)).contains("ACCESS_DENIED");
                    assertThat(state()).as("un 403 no cambia nada").isEqualTo(before);
                }));
            }
        }
        return tests.stream();
    }

    // Control positivo: los roles que la matriz autoriza sí atraviesan la compuerta
    // (ni 401 ni error del servidor).
    @TestFactory
    Stream<DynamicTest> conPermisoLaCompuertaSeAbre() throws Exception {
        prepare();
        List<DynamicTest> tests = new ArrayList<>();
        for (Case c : cases) {
            if (c.kind() == Kind.PUBLIC) {
                continue;
            }
            for (Rol role : c.gate()) {
                tests.add(DynamicTest.dynamicTest(c.key() + " · " + role + " pasa la compuerta", () -> {
                    MvcResult r = call(c, session(c, role), c.uri().apply(ctx), c.body().apply(ctx));

                    int status = r.getResponse().getStatus();
                    assertThat(status).as(c.key() + " como " + role).isNotEqualTo(401).isLessThan(500);
                }));
            }
        }
        return tests.stream();
    }

    // ---- criterio 1: recurso de otro establecimiento
    // ----------------------------------------

    // Cada rol de A que pasa la compuerta y apunta a un recurso de B es rechazado,
    // sin cambios, sin revelar
    // nada y de forma idéntica a un id inexistente (RN-03, RN-20).
    @TestFactory
    @ReglaDeNegocio("RN-03")
    Stream<DynamicTest> recursoDeOtroEstablecimientoEsRechazadoIgualQueUnInexistente() throws Exception {
        prepare();
        List<DynamicTest> tests = new ArrayList<>();
        for (Case c : cases) {
            if (c.foreign() == null) {
                continue;
            }
            for (Rol role : TENANT_ROLES) {
                if (!c.gate().contains(role)) {
                    continue;
                }
                tests.add(DynamicTest.dynamicTest(c.key() + " · " + role + " con recurso de B", () -> {
                    Foreign f = c.foreign();
                    String before = state();

                    MvcResult foreign = call(c, session(c, role), f.uri().apply(ctx), f.body().apply(ctx));
                    MvcResult missing = call(c, session(c, role), f.missingUri().apply(ctx),
                            f.missingBody().apply(ctx));

                    assertThat(foreign.getResponse().getStatus()).as(c.key() + " como " + role)
                            .isIn(f.statuses());
                    assertThat(foreign.getResponse().getStatus()).as("igual que un id inexistente")
                            .isEqualTo(missing.getResponse().getStatus());
                    assertThat(bodyOf(foreign)).as("mismo cuerpo que un id inexistente")
                            .isEqualTo(bodyOf(missing));
                    assertThat(bodyOf(foreign)).doesNotContain(SECRET_PLAN, SECRET_BRANCH, SECRET_EVENT, SECRET_CLASS);
                    assertThat(state()).as("el recurso de B no cambia").isEqualTo(before);
                }));
            }
        }
        return tests.stream();
    }

    // Control positivo: con ids propios los mismos endpoints sí responden, así que
    // los 404 de arriba son por
    // el aislamiento y no por una solicitud mal armada.
    @Test
    void conRecursosPropiosElAdministradorSiLosVe() throws Exception {
        prepare();
        Cookie admin = ctx.cookies().get(Rol.ADMIN);

        assertThat(get("/membership-plans/" + ctx.planA(), admin)).isEqualTo(200);
        assertThat(get("/branches/" + ctx.branchA(), admin)).isEqualTo(200);
        assertThat(get("/membership-plans/" + ctx.planB(), admin)).isEqualTo(404);
        assertThat(get("/branches/" + ctx.branchB(), admin)).isEqualTo(404);
    }

    private int get(String uri, Cookie cookie) throws Exception {
        return mvc.perform(request(HttpMethod.GET, uri).cookie(cookie)).andReturn().getResponse().getStatus();
    }

    // ---- las listas y las escrituras tampoco cruzan establecimientos
    // ------------------------

    // Las listas de cada rol con permiso solo traen datos del establecimiento A.
    @TestFactory
    Stream<DynamicTest> lasListasSoloTraenDatosDelPropioEstablecimiento() throws Exception {
        prepare();
        List<DynamicTest> tests = new ArrayList<>();
        for (String uri : List.of("/membership-plans", "/branches", "/audit/events")) {
            Case c = cases.stream().filter(k -> k.key().equals("GET " + uri)).findFirst().orElseThrow();
            for (Rol role : TENANT_ROLES) {
                if (!c.gate().contains(role)) {
                    continue;
                }
                tests.add(DynamicTest.dynamicTest("GET " + uri + " · " + role + " sin datos de B", () -> {
                    MvcResult r = call(c, session(c, role), uri, null);

                    assertThat(r.getResponse().getStatus()).isEqualTo(200);
                    assertThat(bodyOf(r)).doesNotContain(SECRET_PLAN, SECRET_BRANCH, SECRET_EVENT, SECRET_CLASS);
                }));
            }
        }
        return tests.stream();
    }

    // El establecimiento de una escritura sale de la sesión: un organizationId en
    // el cuerpo se ignora.
    @Test
    @ReglaDeNegocio("RN-03")
    void unOrganizationIdEnElCuerpoNoMueveLaEscrituraAOtroEstablecimiento() throws Exception {
        prepare();
        Cookie admin = ctx.cookies().get(Rol.ADMIN);
        long b = ctx.b().id();
        int plansB = count("SELECT count(*) FROM membership_plans WHERE organization_id = ?", b);
        int branchesB = count("SELECT count(*) FROM branches WHERE organization_id = ?", b);

        MvcResult plan = mvc.perform(request(HttpMethod.POST, "/membership-plans").with(csrf()).cookie(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Plan con orgId\",\"price\":1000,\"durationDays\":30,\"organizationId\":" + b
                        + "}"))
                .andReturn();
        MvcResult branch = mvc.perform(request(HttpMethod.POST, "/branches").with(csrf()).cookie(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Sede con orgId\",\"addressLine1\":\"Calle 1\",\"city\":\"Tunja\","
                        + "\"department\":\"Boyacá\",\"organizationId\":" + b + "}"))
                .andReturn();

        assertThat(plan.getResponse().getStatus()).isEqualTo(201);
        assertThat(branch.getResponse().getStatus()).isEqualTo(201);
        assertThat(count("SELECT count(*) FROM membership_plans WHERE organization_id = ?", b)).isEqualTo(plansB);
        assertThat(count("SELECT count(*) FROM branches WHERE organization_id = ?", b)).isEqualTo(branchesB);
        assertThat(count("SELECT count(*) FROM membership_plans WHERE organization_id = ? AND name = 'Plan con orgId'",
                ctx.a().id())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM branches WHERE organization_id = ? AND name = 'Sede con orgId'",
                ctx.a().id())).isEqualTo(1);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    // ---- criterio 3: un endpoint nuevo sin prueba se reporta como no cubierto
    // ----------------

    // Todo endpoint real de la aplicación tiene su caso en cases(), y los casos
    // coinciden con la matriz 3.7.
    @Test
    void todoEndpointTieneCasoEnLaSuite() throws Exception {
        prepare();
        Map<String, HandlerMethod> found = new TreeMap<>();
        mapping.getHandlerMethods().forEach((info, handler) -> {
            if (!AccessInterceptor.inApplication(handler)) {
                return;
            }
            String method = info.getMethodsCondition().getMethods().stream().findFirst()
                    .map(Enum::name).orElse("ANY");
            PathPatternsRequestCondition paths = info.getPathPatternsCondition();
            assertThat(paths).as("rutas de " + handler.getShortLogMessage()).isNotNull();
            for (String pattern : paths.getPatternValues()) {
                found.put(method + " " + pattern, handler);
            }
        });
        Set<String> registered = new TreeSet<>();
        cases.forEach(c -> registered.add(c.key()));

        Set<String> uncovered = new TreeSet<>(found.keySet());
        uncovered.removeAll(registered);
        Set<String> stale = new TreeSet<>(registered);
        stale.removeAll(found.keySet());
        assertThat(uncovered).as("Endpoints sin caso en AuthorizationMatrixTests.cases() (NEX-28): agrégalos")
                .isEmpty();
        assertThat(stale).as("Casos de la suite que ya no corresponden a ningún endpoint: quítalos").isEmpty();

        List<String> mismatches = new ArrayList<>();
        for (Case c : cases) {
            HandlerMethod handler = found.get(c.key());
            RequiresAccess rule = AccessInterceptor.find(handler, RequiresAccess.class);
            boolean anyAuthenticated = AccessInterceptor.find(handler, AnyAuthenticated.class) != null;
            switch (c.kind()) {
                case GATED -> {
                    if (rule == null) {
                        mismatches.add(c.key() + ": la suite lo espera con @RequiresAccess");
                    } else if (!Permisos.rolesPermitidos(rule.funcion(), rule.accion()).equals(c.gate())) {
                        mismatches.add(c.key() + ": la suite espera " + c.gate() + " pero la matriz da "
                                + Permisos.rolesPermitidos(rule.funcion(), rule.accion()));
                    }
                }
                case ANY_AUTHENTICATED, PUBLIC -> {
                    if (rule != null || !anyAuthenticated) {
                        mismatches.add(c.key() + ": la suite lo espera con @AnyAuthenticated");
                    }
                }
                default -> throw new IllegalStateException();
            }
        }
        assertThat(mismatches).as("Casos que no coinciden con la regla real del endpoint").isEmpty();
    }

    // La suite recorre cada endpoint con los 5 roles: el registro no deja ninguno
    // sin probar.
    @Test
    void elRegistroCubreLosCincoRolesYSusCasosDeBorde() throws Exception {
        prepare();
        Map<String, Long> byKind = new HashMap<>();
        cases.forEach(c -> byKind.merge(c.kind().name(), 1L, Long::sum));

        assertThat(ALL).hasSize(5);
        assertThat(ctx.accounts().keySet()).containsExactlyInAnyOrderElementsOf(ALL);
        assertThat(byKind.getOrDefault("PUBLIC", 0L)).as("solo el login y los 2 GET de /public/gyms son públicos")
                .isEqualTo(3L);
        assertThat(cases.stream().filter(c -> c.foreign() != null)).as("endpoints con recurso ajeno").isNotEmpty();
    }
}