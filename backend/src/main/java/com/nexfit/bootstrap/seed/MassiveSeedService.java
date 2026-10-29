package com.nexfit.bootstrap.seed;

import com.nexfit.bootstrap.seed.MassiveCatalog.Place;
import com.nexfit.bootstrap.seed.MassiveCatalog.PlanSpec;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Conjunto {@code massive}: datos de demostración a gran escala y reproducibles (semilla fija). Todas las
 * personas, documentos, correos y teléfonos son sintéticos; el catálogo de lugares y de EPS sí corresponde a
 * Colombia. Carga con {@code COPY} (un gimnasio por transacción) y es idempotente: un gimnasio ya cargado
 * (identificado por su correo de contacto {@code …@massive.nexfit.test}) no se toca.
 *
 * <p>Los ingresos se insertan con el trigger {@code check_ins_validate} desactivado <b>solo dentro de la
 * transacción de carga</b>; antes de confirmar se verifica que ninguno quede fuera de la vigencia de una
 * membresía activa y, si hay alguno, se deshace todo el gimnasio.
 */
@Service
class MassiveSeedService {

    static final String EMAIL_DOMAIN = "@massive.nexfit.test";
    static final String DEMO_DOMAIN = "@nexfit.test";
    static final String MASTER_EMAIL = "massive.master" + DEMO_DOMAIN;

    private static final long SEED = 20_261_029L;
    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss'-05'");
    private static final long DOC_BASE = 1_000_000_000L;
    private static final long DOC_MODULUS = 90_000_000L;

    /** Tablas con {@code organization_id} que se cuentan al terminar. */
    private static final List<String> COUNTED_TABLES = List.of("branches", "membership_plans", "organization_members",
        "member_roles", "emergency_contacts", "trainer_profiles", "trainer_clients", "memberships", "payments",
        "classes", "class_bookings", "check_ins", "equipment");

    record Result(int gymsLoaded, int gymsSkipped, Map<String, Long> counts) { }

    /** Una membresía por generar (antes de conocer su id). */
    private record Ms(int client, int plan, LocalDate start, LocalDate end, String status) { }

    private final JdbcClient jdbc;
    private final DataSource dataSource;
    private final PasswordEncoder encoder;
    private final TransactionTemplate tx;

    MassiveSeedService(JdbcClient jdbc, DataSource dataSource, PasswordEncoder encoder,
                       PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.encoder = encoder;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // =====================================================================================
    // Punto de entrada
    // =====================================================================================

    Result load(String password, MassiveScale scale, Path teamFile) {
        String hash = encoder.encode(password);
        LocalDate today = LocalDate.now(BOGOTA);
        long[] epsPool = tx.execute(status -> ensureEpsPool());
        tx.executeWithoutResult(status -> ensureMaster(hash));

        double totalWeight = 0;
        for (int g = 0; g < scale.gyms; g++) {
            totalWeight += MassiveCatalog.GYM_WEIGHTS[g];
        }
        int loaded = 0;
        int skipped = 0;
        for (int g = 0; g < scale.gyms; g++) {
            if (gymExists(g)) {
                skipped++;
                continue;
            }
            double share = MassiveCatalog.GYM_WEIGHTS[g] / totalWeight;
            int clients = (int) Math.round(scale.clients * share);
            long checkIns = Math.round(scale.checkIns * share);
            int gym = g;
            tx.executeWithoutResult(status -> loadGym(gym, scale, clients, checkIns, hash, epsPool, today));
            loaded++;
        }
        if (teamFile != null) {
            tx.executeWithoutResult(status -> loadTeam(teamFile, hash, epsPool));
        }
        return new Result(loaded, skipped, counts());
    }

    // =====================================================================================
    // Un gimnasio
    // =====================================================================================

    private void loadGym(int g, MassiveScale scale, int clientCount, long checkInTarget, String hash,
                         long[] epsPool, LocalDate today) {
        Random rnd = new Random(SEED + 7919L * g);
        Place home = MassiveCatalog.PLACES.get(g % MassiveCatalog.PLACES.size());
        String gymName = MassiveCatalog.GYM_NAMES.get(g);

        long org = jdbc.sql("""
                INSERT INTO organizations (name, contact_email, contact_phone, timezone, is_active)
                VALUES (:name, :email, :phone, 'America/Bogota', true) RETURNING id
                """)
            .param("name", gymName).param("email", gymEmail(g)).param("phone", landline(rnd))
            .query(Long.class).single();

        // ---- sedes ---------------------------------------------------------------------
        int branchCount = scale.branchesPerGym;
        long[] branches = new long[branchCount];
        for (int j = 0; j < branchCount; j++) {
            Place place = MassiveCatalog.PLACES.get((g + j) % MassiveCatalog.PLACES.size());
            String barrio = place.barrios().get((g + j) % place.barrios().size());
            branches[j] = jdbc.sql("""
                    INSERT INTO branches (organization_id, name, phone, schedule, address_line1, address_line2,
                                          city, department, country, is_active)
                    VALUES (:org, :name, :phone, :schedule, :line1, :line2, :city, :dept, 'Colombia', true)
                    RETURNING id
                    """)
                .param("org", org).param("name", gymName + " " + barrio + " (" + place.city() + ")")
                .param("phone", landline(rnd))
                .param("schedule", "Lun-Vie 5:00 a. m. - 9:00 p. m.; Sáb 7:00 a. m. - 4:00 p. m.; "
                    + "Dom y festivos 8:00 a. m. - 1:00 p. m.")
                .param("line1", MassiveCatalog.address(rnd)).param("line2", "Barrio " + barrio)
                .param("city", place.city()).param("dept", place.department())
                .query(Long.class).single();
        }

        // ---- planes (precios en COP) -----------------------------------------------------
        double factor = MassiveCatalog.PRICE_FACTORS[g % MassiveCatalog.PRICE_FACTORS.length];
        List<PlanSpec> specs = MassiveCatalog.PLANS;
        long[] planIds = new long[specs.size()];
        int[] planPrice = new int[specs.size()];
        for (int p = 0; p < specs.size(); p++) {
            PlanSpec spec = specs.get(p);
            planPrice[p] = (int) (Math.round(spec.basePrice() * factor / 1000.0) * 1000);
            planIds[p] = jdbc.sql("""
                    INSERT INTO membership_plans (organization_id, name, description, price, duration_days,
                                                  benefits, is_active)
                    VALUES (:org, :name, :description, :price, :days, :benefits, true) RETURNING id
                    """)
                .param("org", org).param("name", spec.name())
                .param("description", "Plan " + spec.name().toLowerCase(Locale.ROOT) + " con acceso a todas las sedes")
                .param("price", planPrice[p]).param("days", spec.days())
                .param("benefits", "Acceso a sala de máquinas y clases grupales")
                .query(Long.class).single();
        }

        // ---- personas ---------------------------------------------------------------------
        int staffCount = 2 + clientCount / 1500;
        int trainerCount = Math.max(2, clientCount / 300);
        int staffFrom = 1;
        int trainerFrom = staffFrom + staffCount;
        int clientFrom = trainerFrom + trainerCount;
        int people = clientFrom + clientCount;

        long userBase = reserve("users", people);
        long memberBase = reserve("organization_members", people);

        String[] first = new String[people];
        String[] last = new String[people];
        String[] email = new String[people];
        long[] document = new long[people];
        LocalDate[] birth = new LocalDate[people];
        LocalDate[] joined = new LocalDate[people];
        boolean[] memberActive = new boolean[people];
        double[] weight = new double[people];
        for (int k = 0; k < people; k++) {
            boolean female = rnd.nextBoolean();
            first[k] = MassiveCatalog.pick(female ? MassiveCatalog.FEMALE : MassiveCatalog.MALE, rnd);
            last[k] = MassiveCatalog.lastName(rnd);
            long gi = g * 100_000L + k;
            document[k] = DOC_BASE + (gi * 48_271L + 7_654_321L) % DOC_MODULUS;
            String domain = MassiveCatalog.DOMAINS.get(rnd.nextInt(MassiveCatalog.DOMAINS.size()));
            email[k] = MassiveCatalog.fold(first[k]) + "." + MassiveCatalog.fold(last[k].split(" ")[0]) + "." + gi
                + "@" + domain;
            int age = k < clientFrom ? 24 + rnd.nextInt(30) : 18 + rnd.nextInt(48);
            birth[k] = today.minusYears(age).minusDays(rnd.nextInt(365));
            joined[k] = today.minusDays(1 + rnd.nextInt(540));
            memberActive[k] = k < clientFrom || rnd.nextInt(100) >= 3;
            weight[k] = 0.3 + rnd.nextDouble() * 1.4;
        }
        if (g == 0) {   // un usuario fijo por rol (documentado en docs/seeds/usuarios-demo.md)
            applyDemoUser(first, last, email, document, 0, "admin", "Administrador", "Demo", 2);
            applyDemoUser(first, last, email, document, staffFrom, "staff", "Recepción", "Demo", 3);
            applyDemoUser(first, last, email, document, trainerFrom, "trainer", "Entrenador", "Demo", 4);
            applyDemoUser(first, last, email, document, clientFrom, "cliente", "Cliente", "Demo", 5);
            memberActive[clientFrom] = true;
        }

        try (CopyWriter users = copy("COPY users (id, document_type, document_number, first_name, last_name, "
            + "birth_date, email, password_hash, must_change_password, is_platform_admin, is_active, created_at) "
            + "FROM STDIN")) {
            for (int k = 0; k < people; k++) {
                users.row(userBase + k, "CC", document[k], first[k], last[k], birth[k], email[k], hash, false,
                    false, true, ts(joined[k].atTime(9, 0)));
            }
        }

        String[] phone = new String[people];
        long[] eps = new long[people];
        try (CopyWriter members = copy("COPY organization_members (id, organization_id, user_id, phone, eps_id, "
            + "privacy_accepted_at, health_consent_at, weekly_goal_sessions, is_active, joined_at) FROM STDIN")) {
            for (int k = 0; k < people; k++) {
                phone[k] = MassiveCatalog.mobile(rnd);
                boolean client = k >= clientFrom;
                eps[k] = client ? epsPool[rnd.nextInt(epsPool.length)] : 0;
                String consent = ts(joined[k].atTime(9, 5));
                members.row(memberBase + k, org, userBase + k, phone[k], client ? eps[k] : null,
                    client ? consent : null, client ? consent : null, client ? 2 + rnd.nextInt(4) : null,
                    memberActive[k], consent);
            }
        }

        try (CopyWriter roles = copy("COPY member_roles (member_id, role, organization_id, assigned_at) "
            + "FROM STDIN")) {
            for (int k = 0; k < people; k++) {
                String role = k == 0 ? "admin" : k < trainerFrom ? "staff" : k < clientFrom ? "trainer" : "client";
                roles.row(memberBase + k, role, org, ts(joined[k].atTime(9, 10)));
            }
        }

        try (CopyWriter contacts = copy("COPY emergency_contacts (organization_id, member_id, full_name, "
            + "relationship, phone, is_primary) FROM STDIN")) {
            for (int k = clientFrom; k < people; k++) {
                contacts.row(org, memberBase + k, MassiveCatalog.pick(rnd.nextBoolean()
                        ? MassiveCatalog.FEMALE : MassiveCatalog.MALE, rnd) + " " + MassiveCatalog.lastName(rnd),
                    MassiveCatalog.pick(MassiveCatalog.RELATIONSHIPS, rnd), MassiveCatalog.mobile(rnd), true);
                if (rnd.nextInt(5) == 0) {
                    contacts.row(org, memberBase + k, MassiveCatalog.pick(MassiveCatalog.FEMALE, rnd) + " "
                        + MassiveCatalog.lastName(rnd), MassiveCatalog.pick(MassiveCatalog.RELATIONSHIPS, rnd),
                        MassiveCatalog.mobile(rnd), false);
                }
            }
        }

        // ---- entrenadores y asignaciones ---------------------------------------------------
        try (CopyWriter trainers = copy("COPY trainer_profiles (member_id, organization_id, specialty, "
            + "certification, hire_date) FROM STDIN")) {
            for (int k = trainerFrom; k < clientFrom; k++) {
                trainers.row(memberBase + k, org, MassiveCatalog.pick(MassiveCatalog.SPECIALTIES, rnd),
                    MassiveCatalog.pick(MassiveCatalog.CERTIFICATIONS, rnd), today.minusDays(120 + rnd.nextInt(1800)));
            }
        }
        try (CopyWriter assigned = copy("COPY trainer_clients (trainer_member_id, client_member_id, "
            + "organization_id, assigned_at, is_active) FROM STDIN")) {
            for (int k = clientFrom; k < people; k++) {
                if (memberActive[k] && rnd.nextInt(100) < 35) {
                    long trainer = memberBase + trainerFrom + rnd.nextInt(trainerCount);
                    assigned.row(trainer, memberBase + k, org, ts(joined[k].atTime(10, 0)), true);
                }
            }
        }

        // ---- membresías y pagos ------------------------------------------------------------
        List<Ms> memberships = new ArrayList<>();
        for (int c = 0; c < clientCount; c++) {
            membershipChain(c, memberActive[clientFrom + c], g == 0 && c == 0, joined[clientFrom + c], today, rnd,
                memberships);
        }
        long membershipBase = memberships.isEmpty() ? 0 : reserve("memberships", memberships.size());
        long[] staffMembers = new long[trainerFrom];
        for (int k = 0; k < trainerFrom; k++) {
            staffMembers[k] = memberBase + k;   // administrador y personal que registra
        }
        long admin = memberBase;
        String[] methods = {"cash", "cash", "cash", "cash", "cash", "transfer", "transfer", "transfer", "card",
            "card", "card", "other"};
        List<Object[]> payments = new ArrayList<>();
        try (CopyWriter ms = copy("COPY memberships (id, organization_id, member_id, plan_id, created_by_member_id, "
            + "start_date, end_date, status, status_reason, status_changed_at, status_changed_by_member_id, "
            + "created_at) FROM STDIN")) {
            for (int i = 0; i < memberships.size(); i++) {
                Ms m = memberships.get(i);
                long id = membershipBase + i;
                long member = memberBase + clientFrom + m.client();
                long by = staffMembers[1 + rnd.nextInt(staffCount)];
                String createdAt = ts(m.start().atTime(8 + rnd.nextInt(10), rnd.nextInt(60)));
                boolean changed = !"active".equals(m.status());
                ms.row(id, org, member, planIds[m.plan()], by, m.start(), m.end(), m.status(),
                    changed ? reasonFor(m.status()) : null, changed ? createdAt : null, changed ? admin : null,
                    createdAt);

                String concept = "Membresía " + MassiveCatalog.PLANS.get(m.plan()).name().toLowerCase(Locale.ROOT);
                String method = methods[rnd.nextInt(methods.length)];
                String reference = "cash".equals(method) ? null : "REF" + (100_000 + rnd.nextInt(900_000));
                boolean pending = "active".equals(m.status()) && !m.end().isBefore(today) && rnd.nextInt(100) < 2;
                if (pending) {
                    payments.add(new Object[] {org, member, id, by, concept, planPrice[m.plan()], createdAt, method,
                        reference, "pending", null, null, null});
                } else {
                    payments.add(new Object[] {org, member, id, by, concept, planPrice[m.plan()], createdAt, method,
                        reference, "completed", null, createdAt, by});
                }
                if (rnd.nextInt(1000) < 15) {   // cobro duplicado que se anuló
                    payments.add(new Object[] {org, member, id, by, concept, planPrice[m.plan()], createdAt, method,
                        reference, "voided", "Pago duplicado", null, null});
                }
            }
        }
        try (CopyWriter pay = copy("COPY payments (organization_id, member_id, membership_id, "
            + "registered_by_member_id, concept, amount, paid_at, method, reference, status, void_reason, "
            + "completed_at, completed_by_member_id) FROM STDIN")) {
            payments.forEach(pay::row);
        }

        // ---- clases y reservas ---------------------------------------------------------------
        classesAndBookings(org, branches, memberBase, trainerFrom, trainerCount, clientFrom, clientCount,
            memberships, memberActive, today, rnd);

        // ---- equipos ---------------------------------------------------------------------------
        try (CopyWriter equipment = copy("COPY equipment (organization_id, branch_id, name, category, status, "
            + "acquisition_date) FROM STDIN")) {
            for (long branch : branches) {
                int count = 25 + rnd.nextInt(21);
                for (int e = 0; e < count; e++) {
                    String[] item = MassiveCatalog.EQUIPMENT.get(rnd.nextInt(MassiveCatalog.EQUIPMENT.size()));
                    int roll = rnd.nextInt(100);
                    String status = roll < 88 ? "available" : roll < 95 ? "maintenance" : roll < 98
                        ? "out_of_service" : "retired";
                    equipment.row(org, branch, item[0] + " #" + (e + 1), item[1], status,
                        today.minusDays(60 + rnd.nextInt(2100)));
                }
            }
        }

        checkIns(org, branches, memberBase, staffCount, clientFrom, clientCount, memberships, memberActive, weight,
            checkInTarget, today, rnd);
    }

    // =====================================================================================
    // Membresías
    // =====================================================================================

    /** Historial de un cliente: renovaciones sin solaparse, con vencidas, suspendidas y canceladas. */
    private void membershipChain(int client, boolean active, boolean forceCurrent, LocalDate joined, LocalDate today,
                                 Random rnd, List<Ms> out) {
        if (!active) {   // cliente dado de baja: una única membresía cancelada y ya terminada
            PlanSpec plan = MassiveCatalog.PLANS.get(1);
            LocalDate end = today.minusDays(5 + rnd.nextInt(200));
            out.add(new Ms(client, 1, end.minusDays(plan.days() - 1), end, "cancelled"));
            return;
        }
        if (!forceCurrent && rnd.nextInt(100) < 4) {
            return;   // registrado, aún sin contratar
        }
        LocalDate cursor = forceCurrent ? today.minusDays(10) : joined;
        int before = out.size();
        for (int n = 0; n < 8 && !cursor.isAfter(today); n++) {
            int plan = pickPlan(rnd);
            LocalDate end = cursor.plusDays(MassiveCatalog.PLANS.get(plan).days() - 1);
            out.add(new Ms(client, plan, cursor, end, "active"));
            if (!end.isBefore(today)) {
                break;   // la vigente
            }
            if (!forceCurrent && rnd.nextInt(100) < 12) {
                break;   // dejó de renovar: queda vencida
            }
            cursor = end.plusDays(1 + (rnd.nextInt(10) < 7 ? rnd.nextInt(3) : 4 + rnd.nextInt(55)));
        }
        if (!forceCurrent && out.size() > before) {
            Ms last = out.get(out.size() - 1);
            boolean current = !last.start().isAfter(today) && !last.end().isBefore(today);
            int roll = rnd.nextInt(100);
            if (current && roll < 7) {
                out.set(out.size() - 1, new Ms(last.client(), last.plan(), last.start(), last.end(),
                    roll < 4 ? "suspended" : "cancelled"));
            }
        }
    }

    private static int pickPlan(Random rnd) {
        int roll = rnd.nextInt(100);
        int acc = 0;
        for (int p = 0; p < MassiveCatalog.PLANS.size(); p++) {
            acc += MassiveCatalog.PLANS.get(p).weight();
            if (roll < acc) {
                return p;
            }
        }
        return 1;
    }

    private static String reasonFor(String status) {
        return "suspended".equals(status) ? "Suspensión solicitada por el cliente (viaje)"
            : "Cancelación solicitada por el cliente";
    }

    // =====================================================================================
    // Clases y reservas
    // =====================================================================================

    private void classesAndBookings(long org, long[] branches, long memberBase, int trainerFrom, int trainerCount,
                                    int clientFrom, int clientCount, List<Ms> memberships, boolean[] memberActive,
                                    LocalDate today, Random rnd) {
        // Clientes con membresía activa vigente hoy: pueden reservar (el trigger lo exige).
        List<int[]> bookable = new ArrayList<>();   // {cliente, días que le quedan}
        for (Ms m : memberships) {
            if ("active".equals(m.status()) && !m.start().isAfter(today) && !m.end().isBefore(today)
                && memberActive[clientFrom + m.client()]) {
                bookable.add(new int[] {m.client(), (int) (m.end().toEpochDay() - today.toEpochDay())});
            }
        }
        int[][] weekdaySlots = {{6, 0}, {7, 0}, {8, 30}, {12, 15}, {17, 30}, {18, 30}, {19, 30}};
        int[][] weekendSlots = {{8, 0}, {9, 30}, {10, 30}};
        List<Object[]> classes = new ArrayList<>();
        for (long branch : branches) {
            for (int d = 1; d <= 21; d++) {
                LocalDate day = today.plusDays(d);
                boolean weekend = day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY;
                int[][] slots = weekend ? weekendSlots : weekdaySlots;
                int count = weekend ? 1 + rnd.nextInt(2) : 3 + rnd.nextInt(2);
                Set<Integer> used = new HashSet<>();
                while (used.size() < count) {
                    used.add(rnd.nextInt(slots.length));
                }
                for (int slot : used) {
                    classes.add(new Object[] {branch, day, slots[slot][0], slots[slot][1]});
                }
            }
        }
        long classBase = classes.isEmpty() ? 0 : reserve("classes", classes.size());
        List<Object[]> bookings = new ArrayList<>();
        try (CopyWriter cls = copy("COPY classes (id, organization_id, branch_id, trainer_member_id, name, "
            + "description, starts_at, duration_minutes, capacity, status, created_at) FROM STDIN")) {
            LocalDateTime now = LocalDateTime.now(BOGOTA);
            for (int i = 0; i < classes.size(); i++) {
                Object[] c = classes.get(i);
                LocalDate day = (LocalDate) c[1];
                long id = classBase + i;
                int capacity = 12 + rnd.nextInt(19);
                cls.row(id, org, c[0], memberBase + trainerFrom + rnd.nextInt(trainerCount),
                    MassiveCatalog.pick(MassiveCatalog.CLASS_NAMES, rnd), null,
                    ts(day.atTime((int) c[2], (int) c[3])), rnd.nextBoolean() ? 45 : 60, capacity, "scheduled",
                    ts(now.minusDays(30)));
                if (i % 33 == 0 || bookable.isEmpty()) {
                    continue;   // esta clase se cancelará más abajo
                }
                int target = (int) (capacity * (0.2 + rnd.nextDouble() * 0.75));
                Set<Integer> chosen = new HashSet<>();
                int tries = 0;
                int dayOffset = (int) (day.toEpochDay() - today.toEpochDay());
                while (chosen.size() < target && tries++ < target * 6) {
                    int[] candidate = bookable.get(rnd.nextInt(bookable.size()));
                    if (candidate[1] >= dayOffset && chosen.add(candidate[0])) {
                        LocalDateTime bookedAt = now.minusHours(1 + rnd.nextInt(96));
                        boolean cancelled = rnd.nextInt(100) < 8;
                        bookings.add(new Object[] {org, id, memberBase + clientFrom + candidate[0], ts(bookedAt),
                            cancelled ? "cancelled" : "confirmed", cancelled ? ts(bookedAt.plusHours(2)) : null});
                    }
                }
            }
        }
        try (CopyWriter booking = copy("COPY class_bookings (organization_id, class_id, member_id, booked_at, "
            + "status, cancelled_at) FROM STDIN")) {
            bookings.forEach(booking::row);
        }
        jdbc.sql("""
                UPDATE classes SET status = 'cancelled', cancelled_at = now(), cancel_reason = 'Instructor no disponible'
                 WHERE organization_id = :org AND (id - :base) % 33 = 0
                """)
            .param("org", org).param("base", classBase).update();
    }

    // =====================================================================================
    // Ingresos
    // =====================================================================================

    private void checkIns(long org, long[] branches, long memberBase, int staffCount, int clientFrom, int clientCount,
                          List<Ms> memberships, boolean[] memberActive, double[] weight, long target, LocalDate today,
                          Random rnd) {
        double totalWeight = 0;
        for (Ms m : memberships) {
            if (isAttendable(m, memberActive, clientFrom)) {
                for (LocalDate d = m.start(); !d.isAfter(lastDay(m, today)); d = d.plusDays(1)) {
                    totalWeight += weight[clientFrom + m.client()] * dayFactor(d);
                }
            }
        }
        double scale = totalWeight == 0 ? 0 : target / totalWeight;
        int[] home = new int[clientCount];
        for (int c = 0; c < clientCount; c++) {
            home[c] = rnd.nextInt(branches.length);
        }

        jdbc.sql("ALTER TABLE check_ins DISABLE TRIGGER check_ins_validate").update();
        try (CopyWriter out = copy("COPY check_ins (organization_id, member_id, branch_id, "
            + "registered_by_member_id, check_in_at, check_out_at, auto_closed) FROM STDIN")) {
            for (Ms m : memberships) {
                if (!isAttendable(m, memberActive, clientFrom)) {
                    continue;
                }
                double w = weight[clientFrom + m.client()];
                long member = memberBase + clientFrom + m.client();
                for (LocalDate d = m.start(); !d.isAfter(lastDay(m, today)); d = d.plusDays(1)) {
                    if (rnd.nextDouble() >= Math.min(0.95, scale * w * dayFactor(d))) {
                        continue;
                    }
                    int branch = rnd.nextInt(100) < 85 ? home[m.client()] : rnd.nextInt(branches.length);
                    LocalDateTime in = d.atTime(hour(rnd), rnd.nextInt(60), rnd.nextInt(60));
                    boolean auto = rnd.nextInt(100) < 2;
                    LocalDateTime outAt = in.plusMinutes(auto ? 180 : 40 + rnd.nextInt(91));
                    out.row(org, member, branches[branch], memberBase + 1 + rnd.nextInt(staffCount), ts(in),
                        ts(outAt), auto);
                }
            }
        }
        long outside = jdbc.sql("""
                SELECT count(*) FROM check_ins c
                  JOIN organizations o ON o.id = c.organization_id
                 WHERE c.organization_id = :org
                   AND NOT EXISTS (
                       SELECT 1 FROM memberships m
                         JOIN organization_members om ON om.id = m.member_id
                        WHERE m.member_id = c.member_id AND m.status = 'active' AND om.is_active
                          AND (c.check_in_at AT TIME ZONE o.timezone)::date BETWEEN m.start_date AND m.end_date)
                """)
            .param("org", org).query(Long.class).single();
        if (outside > 0) {
            throw new IllegalStateException(("Verificación fallida: %d ingresos quedaron fuera de la vigencia de una "
                + "membresía activa; no se carga el gimnasio.").formatted(outside));
        }
        jdbc.sql("ALTER TABLE check_ins ENABLE TRIGGER check_ins_validate").update();
    }

    private static boolean isAttendable(Ms m, boolean[] memberActive, int clientFrom) {
        return "active".equals(m.status()) && memberActive[clientFrom + m.client()];
    }

    private static LocalDate lastDay(Ms m, LocalDate today) {
        LocalDate yesterday = today.minusDays(1);
        return m.end().isBefore(yesterday) ? m.end() : yesterday;
    }

    private static double dayFactor(LocalDate d) {
        return switch (d.getDayOfWeek()) {
            case MONDAY -> 1.15;
            case TUESDAY -> 1.1;
            case WEDNESDAY -> 1.05;
            case THURSDAY -> 1.0;
            case FRIDAY -> 0.85;
            case SATURDAY -> 0.55;
            case SUNDAY -> 0.3;
        };
    }

    /** Picos de 6 a 8 h y de 17 a 20 h, con un valle al mediodía. */
    private static int hour(Random rnd) {
        int roll = rnd.nextInt(100);
        if (roll < 30) {
            return 6 + rnd.nextInt(3);
        }
        if (roll < 40) {
            return 12 + rnd.nextInt(2);
        }
        if (roll < 85) {
            return 17 + rnd.nextInt(4);
        }
        return roll < 93 ? 9 + rnd.nextInt(3) : 14 + rnd.nextInt(3);
    }

    // =====================================================================================
    // Equipo del grupo (archivo local opcional)
    // =====================================================================================

    /**
     * Cuentas reales del grupo, desde un archivo local fuera del repositorio. Una línea por persona:
     * {@code rol,correo,tipoDocumento,numeroDocumento,nombres,apellidos,fechaNacimiento,telefono}
     * (rol: admin, staff, trainer o client). Se afilian al primer gimnasio {@code massive}; lo que ya exista se
     * omite. Cada persona aporta sus datos y su consentimiento; este archivo no se versiona.
     */
    private void loadTeam(Path file, String hash, long[] epsPool) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer NEXFIT_SEED_TEAM_FILE", e);
        }
        Long org = jdbc.sql("SELECT id FROM organizations WHERE contact_email = :e").param("e", gymEmail(0))
            .query(Long.class).optional().orElse(null);
        if (org == null) {
            return;
        }
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split(",", -1);
            if (f.length < 8) {
                throw new IllegalArgumentException("Línea inválida en el archivo del equipo (se esperan 8 campos).");
            }
            String role = f[0].trim();
            if (!Set.of("admin", "staff", "trainer", "client").contains(role)) {
                throw new IllegalArgumentException("Rol inválido en el archivo del equipo: " + role);
            }
            boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE lower(email) = lower(:e))")
                .param("e", f[1].trim()).query(Boolean.class).single();
            if (exists) {
                continue;
            }
            long user = jdbc.sql("""
                    INSERT INTO users (document_type, document_number, first_name, last_name, birth_date, email,
                                       password_hash, must_change_password, is_platform_admin, is_active)
                    VALUES (CAST(:type AS document_type), :doc, :first, :last, CAST(:birth AS date), :email, :hash,
                            true, false, true) RETURNING id
                    """)
                .param("type", f[2].trim()).param("doc", f[3].trim()).param("first", f[4].trim())
                .param("last", f[5].trim()).param("birth", f[6].trim()).param("email", f[1].trim())
                .param("hash", hash).query(Long.class).single();
            boolean client = "client".equals(role);
            long member = jdbc.sql("""
                    INSERT INTO organization_members (organization_id, user_id, phone, eps_id, privacy_accepted_at,
                                                      health_consent_at, is_active)
                    VALUES (:org, :user, :phone, :eps, :consent, :consent, true) RETURNING id
                    """)
                .param("org", org).param("user", user).param("phone", f[7].trim())
                .param("eps", client ? epsPool[0] : null).param("consent", client ? java.time.OffsetDateTime.now() : null)
                .query(Long.class).single();
            jdbc.sql("INSERT INTO member_roles (member_id, role, organization_id) "
                    + "VALUES (:m, CAST(:role AS member_role), :o)")
                .param("m", member).param("role", role).param("o", org).update();
            if (client) {
                jdbc.sql("""
                        INSERT INTO emergency_contacts (organization_id, member_id, full_name, relationship, phone,
                                                        is_primary)
                        VALUES (:o, :m, 'Contacto por definir', 'Familiar', :phone, true)
                        """).param("o", org).param("m", member).param("phone", f[7].trim()).update();
            } else if ("trainer".equals(role)) {
                jdbc.sql("INSERT INTO trainer_profiles (member_id, organization_id, specialty) "
                        + "VALUES (:m, :o, 'General')").param("m", member).param("o", org).update();
            }
        }
    }

    // =====================================================================================
    // Auxiliares
    // =====================================================================================

    private static void applyDemoUser(String[] first, String[] last, String[] email, long[] document, int k,
                                      String handle, String firstName, String lastName, int docNumber) {
        first[k] = firstName;
        last[k] = lastName;
        email[k] = "massive." + handle + DEMO_DOMAIN;
        document[k] = 9_000_000_000L + docNumber;
    }

    private void ensureMaster(String hash) {
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE lower(email) = lower(:e))")
            .param("e", MASTER_EMAIL).query(Boolean.class).single();
        if (!exists) {
            jdbc.sql("""
                    INSERT INTO users (document_type, document_number, first_name, last_name, birth_date, email,
                                       password_hash, must_change_password, is_platform_admin, is_active)
                    VALUES ('CC', '9000000001', 'Master', 'NexFit Demo', DATE '1985-05-05', :email, :hash, false,
                            true, true)
                    """).param("email", MASTER_EMAIL).param("hash", hash).update();
        }
    }

    private boolean gymExists(int g) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM organizations WHERE contact_email = :e)")
            .param("e", gymEmail(g)).query(Boolean.class).single();
    }

    private static String gymEmail(int g) {
        return "info%02d%s".formatted(g + 1, EMAIL_DOMAIN);
    }

    /** Teléfono fijo: indicativo 60 + ciudad (8 = Boyacá…) + 7 dígitos. */
    private static String landline(Random rnd) {
        return "608" + String.format("%07d", rnd.nextInt(10_000_000));
    }

    /** Reserva {@code n} ids consecutivos de la secuencia de la tabla y devuelve el primero. */
    private long reserve(String table, int n) {
        String sequence = jdbc.sql("SELECT pg_get_serial_sequence(:t, 'id')").param("t", table)
            .query(String.class).single();
        long first = jdbc.sql("SELECT nextval(CAST(:s AS regclass))").param("s", sequence)
            .query(Long.class).single();
        if (n > 1) {
            jdbc.sql("SELECT setval(CAST(:s AS regclass), :v)").param("s", sequence).param("v", first + n - 1)
                .query(Long.class).single();
        }
        return first;
    }

    private CopyWriter copy(String sql) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            return CopyWriter.open(connection.unwrap(PGConnection.class), sql);
        } catch (SQLException e) {
            throw new IllegalStateException("La conexión no es de PostgreSQL", e);
        }
    }

    private static String ts(LocalDateTime t) {
        return TS.format(t);
    }

    /** Ids de las EPS confirmadas (catálogo {@code bootstrap/eps.txt}), repetidos según su peso. */
    private long[] ensureEpsPool() {
        List<String> names = new ArrayList<>();
        try (InputStream in = new ClassPathResource("bootstrap/eps.txt").getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            reader.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).forEach(names::add);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer bootstrap/eps.txt", e);
        }
        List<Long> pool = new ArrayList<>();
        for (String name : names) {
            long id = jdbc.sql("SELECT id FROM health_insurance_providers WHERE lower(name) = lower(:n)")
                .param("n", name).query(Long.class).optional()
                .orElseGet(() -> jdbc.sql("INSERT INTO health_insurance_providers (name, is_active) "
                        + "VALUES (:n, true) RETURNING id").param("n", name).query(Long.class).single());
            int weight = MassiveCatalog.EPS_WEIGHTS.getOrDefault(name, 1);
            for (int i = 0; i < weight; i++) {
                pool.add(id);
            }
        }
        return pool.stream().mapToLong(Long::longValue).toArray();
    }

    /** Conteos por tabla de lo que pertenece a los gimnasios {@code massive}. */
    private Map<String, Long> counts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        String orgs = "(SELECT id FROM organizations WHERE contact_email LIKE '%" + EMAIL_DOMAIN + "')";
        counts.put("organizations", jdbc.sql("SELECT count(*) FROM organizations WHERE contact_email LIKE '%"
            + EMAIL_DOMAIN + "'").query(Long.class).single());
        counts.put("users", jdbc.sql("SELECT count(DISTINCT user_id) FROM organization_members WHERE "
            + "organization_id IN " + orgs).query(Long.class).single());
        for (String table : COUNTED_TABLES) {
            counts.put(table, jdbc.sql("SELECT count(*) FROM " + table + " WHERE organization_id IN " + orgs)
                .query(Long.class).single());
        }
        return counts;
    }
}
