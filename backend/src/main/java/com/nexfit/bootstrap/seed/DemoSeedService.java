package com.nexfit.bootstrap.seed;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Conjunto {@code demo}: dos gimnasios ficticios completos para mostrar toda la interfaz (personal, clientes con
 * membresías en distintos estados, equipos con imagen, clases con reservas, asistencias, alumnos asignados, rutinas,
 * entrenamientos con racha, meta semanal y peso corporal). Todo es inventado: correos {@code @nexfit.test},
 * documentos {@code DEMO-…}. Es idempotente: si «Iron Peak Fitness» ya existe, no toca nada. Se carga en una sola
 * transacción.
 */
@Service
class DemoSeedService {

    static final String DEMO = "demo";
    private static final String TZ = SeedService.TIMEZONE;

    record Result(boolean skipped, int gyms, int clients, int routines, int workouts) { }

    private record Gym(String name, String slug, String city, String department, List<String> branches) { }

    private record Equip(String name, String category, String status, int ageDays) { }

    private static final List<Gym> GYMS = List.of(
        new Gym("Iron Peak Fitness", "ironpeak", "Tunja", "Boyacá", List.of("Sede Centro", "Sede Norte")),
        new Gym("Vital Studio", "vital", "Sogamoso", "Boyacá", List.of("Sede Principal")));

    private static final String[] TRAINER_NAMES = {"Mariana Ospina", "Esteban Quiroga", "Lorena Cepeda"};
    private static final String[] TRAINER_SPECIALTIES = {"Fuerza e hipertrofia", "Funcional y cardio",
        "Movilidad y rehabilitación"};
    private static final String[] STAFF_NAMES = {"Paola Mendoza", "Jairo Becerra"};
    private static final String[] CLIENT_FIRST = {"Camila", "Santiago", "Valentina", "Mateo", "Sofía", "Daniel",
        "Laura", "Andrés", "Mariana", "Julián", "Natalia", "Felipe"};
    private static final String[] CLIENT_LAST = {"Rojas", "Pinto", "Cárdenas", "Salamanca", "Quintero", "Báez",
        "Camargo", "Barrera", "Niño", "Pulido", "Becerra", "Cely"};

    private static final List<Equip> EQUIPMENT = List.of(
        new Equip("Caminadora Pro X1", "Cardio", "available", 900), new Equip("Caminadora Pro X2", "Cardio", "available", 900),
        new Equip("Bicicleta estática Spin", "Cardio", "available", 700), new Equip("Elíptica Glide", "Cardio", "maintenance", 1100),
        new Equip("Remo Concept", "Cardio", "available", 500), new Equip("Prensa de piernas 45°", "Fuerza", "available", 1500),
        new Equip("Polea alta y baja", "Fuerza", "available", 1500), new Equip("Press de pecho en máquina", "Fuerza", "out_of_service", 1800),
        new Equip("Mancuernas 2–30 kg", "Pesas libres", "available", 1200), new Equip("Barra olímpica y discos", "Pesas libres", "available", 1600),
        new Equip("Banco ajustable", "Pesas libres", "available", 1000), new Equip("Kettlebells 8–24 kg", "Funcional", "available", 400),
        new Equip("Suspensión TRX", "Funcional", "available", 300));

    private static final String[] CLASS_NAMES = {"Spinning", "Yoga", "Funcional", "Zumba", "Crossfit Intro", "Pilates",
        "Boxeo fitness", "Estiramiento"};

    private static final String[][] FALLBACK_EXERCISES = {
        {"Press de banca plano", "Pecho"}, {"Remo con barra", "Espalda"}, {"Sentadilla libre", "Piernas"},
        {"Prensa de piernas", "Piernas"}, {"Press militar con barra", "Hombros"}, {"Curl de bíceps con barra", "Bíceps"},
        {"Plancha abdominal", "Core"}, {"Crunch abdominal", "Core"}, {"Bicicleta estática", "Cardio"},
        {"Trote en cinta", "Cardio"}, {"Estiramiento de isquiotibiales", "Movilidad"},
        {"Movilidad de cadera 90/90", "Movilidad"}};

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;

    DemoSeedService(JdbcClient jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    @Transactional
    Result load(String password) {
        if (jdbc.sql("SELECT EXISTS (SELECT 1 FROM organizations WHERE name = :n)").param("n", GYMS.get(0).name())
            .query(Boolean.class).single()) {
            return new Result(true, 0, 0, 0, 0);
        }
        String hash = encoder.encode(password);
        long eps = ensureEps();
        Map<String, List<Long>> exercises = globalExercises();
        LocalDate today = today();
        int clients = 0;
        int routines = 0;
        int workouts = 0;
        for (Gym gym : GYMS) {
            long org = jdbc.sql("""
                    INSERT INTO organizations (name, contact_email, contact_phone, timezone, is_active)
                    VALUES (:name, :email, '3100000000', :tz, true) RETURNING id""")
                .param("name", gym.name()).param("email", "contacto." + gym.slug() + "@nexfit.test").param("tz", TZ)
                .query(Long.class).single();
            List<Long> branches = new ArrayList<>();
            for (int b = 0; b < gym.branches().size(); b++) {
                branches.add(jdbc.sql("""
                        INSERT INTO branches (organization_id, name, address_line1, city, department, country, is_active)
                        VALUES (:org, :name, :addr, :city, :dep, 'Colombia', true) RETURNING id""")
                    .param("org", org).param("name", gym.branches().get(b)).param("addr", "Carrera " + (5 + b) + " # 10-" + (20 + b))
                    .param("city", gym.city()).param("dep", gym.department()).query(Long.class).single());
            }
            staff(org, "admin." + gym.slug(), "DEMO-" + gym.slug() + "-ADMIN", "Andrea", "Castellanos", 1982,
                "admin", hash);
            long[] staff = new long[2];
            for (int s = 0; s < 2; s++) {
                String[] n = STAFF_NAMES[s].split(" ");
                staff[s] = staff(org, (s == 0 ? "staff." : "staff2.") + gym.slug(), "DEMO-" + gym.slug() + "-S" + s,
                    n[0], n[1], 1990 + s, "staff", hash);
            }
            long[] trainers = new long[3];
            for (int t = 0; t < 3; t++) {
                String[] n = TRAINER_NAMES[t].split(" ");
                trainers[t] = staff(org, (t == 0 ? "trainer." : "trainer" + (t + 1) + ".") + gym.slug(),
                    "DEMO-" + gym.slug() + "-T" + t, n[0], n[1], 1988 + t, "trainer", hash);
                jdbc.sql("INSERT INTO trainer_profiles (member_id, organization_id, specialty, hire_date) "
                        + "VALUES (:m, :o, :s, CAST(:d AS date))")
                    .param("m", trainers[t]).param("o", org).param("s", TRAINER_SPECIALTIES[t])
                    .param("d", today.minusDays(400L + t * 90)).update();
            }
            long monthly = plan(org, "Mensual", 90_000, 30);
            long quarterly = plan(org, "Trimestral", 240_000, 90);
            plan(org, "Anual", 800_000, 365);
            plan(org, "Pase diario", 8_000, 1);

            long[] client = new long[12];
            for (int k = 0; k < 12; k++) {
                client[k] = addClient(org, "cliente" + (k + 1) + "." + gym.slug(), "DEMO-" + gym.slug() + "-C" + (k + 1),
                    CLIENT_FIRST[k], CLIENT_LAST[k], 1985 + k * 2, hash, eps);
                clients++;
            }
            // Clientes 1–8 con membresía vigente; 9 por vencer; 10 vencida; 11 y 12 sin membresía.
            for (int k = 0; k < 8; k++) {
                contract(org, client[k], k % 2 == 0 ? monthly : quarterly, k % 2 == 0 ? 30 : 90, staff[k % 2],
                    today.minusDays(20));
            }
            contract(org, client[8], monthly, 30, staff[0], today.minusDays(25));
            contract(org, client[9], monthly, 30, staff[1], today.minusDays(70));

            for (int k = 0; k < 9; k++) {
                for (int d = 1; d <= 14; d++) {
                    if ((d + k) % 3 != 0) {
                        checkIn(org, client[k], branches.get(k % branches.size()), staff[k % 2], today.minusDays(d), k);
                    }
                }
            }

            for (int b = 0; b < branches.size(); b++) {
                for (Equip e : EQUIPMENT) {
                    jdbc.sql("""
                            INSERT INTO equipment (organization_id, branch_id, name, category, status, acquisition_date,
                                                   image_url)
                            VALUES (:org, :branch, :name, :cat, CAST(:status AS equipment_status), CAST(:d AS date), :img)""")
                        .param("org", org).param("branch", branches.get(b)).param("name", e.name())
                        .param("cat", e.category()).param("status", b == 0 || !"out_of_service".equals(e.status()) ? e.status() : "available")
                        .param("d", today.minusDays(e.ageDays())).param("img", equipmentImage(e.category())).update();
                }
                classes(org, branches.get(b), b, trainers, client);
            }

            for (int k = 0; k < 6; k++) {
                jdbc.sql("""
                        INSERT INTO trainer_clients (trainer_member_id, client_member_id, organization_id, is_active)
                        VALUES (:t, :c, :o, true)""").param("t", trainers[k % 3]).param("c", client[k]).param("o", org).update();
                jdbc.sql("UPDATE organization_members SET weekly_goal_sessions = :g WHERE id = :m")
                    .param("g", 3 + k % 3).param("m", client[k]).update();
            }

            long[][] firstRoutine = new long[6][];
            for (int k = 0; k < 6; k++) {
                long trainer = trainers[k % 3];
                long[] main = routine(org, trainer, client[k], k % 2 == 0 ? "Fuerza total A" : "Hipertrofia base",
                    "strength", k < 3 ? "beginner" : "intermediate", 55, 380, 12 - k, true,
                    choose(exercises, k, "Pecho", "Espalda", "Piernas", "Piernas", "Hombros", "Bíceps"));
                routine(org, trainer, client[k], "Cardio y movilidad", k % 2 == 0 ? "cardio" : "mobility", "beginner", 40,
                    260, 6, true, choose(exercises, k, "Cardio", "Cardio", "Core", "Core", "Movilidad"));
                routines += 2;
                if (k == 0) {
                    routine(org, trainer, client[k], "Rutina de adaptación (anterior)", "strength", "beginner", 45, 300,
                        60, false, choose(exercises, 1, "Pecho", "Espalda", "Piernas", "Core"));
                    routines++;
                }
                firstRoutine[k] = main;
            }

            // Entrenamientos: el cliente 1 entrena todos los días (racha larga); 2, 3 y 4 con patrones distintos.
            workouts += workouts(org, client[0], firstRoutine[0], today, new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11});
            workouts += workouts(org, client[1], firstRoutine[1], today, new int[] {1, 2, 3, 6, 7});
            workouts += workouts(org, client[2], firstRoutine[2], today, new int[] {0, 2, 4, 6, 8});
            workouts += workouts(org, client[3], firstRoutine[3], today, new int[] {1});
            workouts += workouts(org, client[4], firstRoutine[4], today, new int[] {5, 6});
            for (int k = 0; k < 4; k++) {
                for (int w = 7; w >= 0; w--) {
                    jdbc.sql("""
                            INSERT INTO body_weight_records (organization_id, member_id, measured_on, weight_kg)
                            VALUES (:o, :m, CAST(:d AS date), :kg)""")
                        .param("o", org).param("m", client[k]).param("d", today.minusDays(w * 7L))
                        .param("kg", (k % 2 == 0 ? 82.0 - (7 - w) * 0.6 : 58.0 + (7 - w) * 0.4) + k).update();
                }
            }
        }
        return new Result(false, GYMS.size(), clients, routines, workouts);
    }

    /** Fecha de hoy en la zona horaria de los gimnasios (la misma que usan las reglas de negocio). */
    private LocalDate today() {
        return jdbc.sql("SELECT (now() AT TIME ZONE 'America/Bogota')::date").query(LocalDate.class).single();
    }

    // =====================================================================================
    // Piezas del conjunto
    // =====================================================================================

    /** Clases de los próximos seis días con algunas reservas (solo clientes con membresía vigente). */
    private void classes(long org, long branch, int branchIndex, long[] trainers, long[] client) {
        String[] slots = branchIndex == 0 ? new String[] {"06:00", "18:00"} : new String[] {"08:00", "19:30"};
        LocalDate today = today();
        for (int i = 0; i < CLASS_NAMES.length; i++) {
            long cls = jdbc.sql("""
                    INSERT INTO classes (organization_id, branch_id, trainer_member_id, name, description, starts_at,
                                         duration_minutes, capacity, status)
                    VALUES (:org, :branch, :trainer, :name, :desc,
                            (CAST(:day AS date) + CAST(:time AS time)) AT TIME ZONE 'America/Bogota', 60, 15,
                            CAST('scheduled' AS class_status))
                    RETURNING id""")
                .param("org", org).param("branch", branch).param("trainer", trainers[i % 3])
                .param("name", CLASS_NAMES[i]).param("desc", "Clase grupal de " + CLASS_NAMES[i].toLowerCase() + " para todos los niveles.")
                .param("day", today.plusDays(1 + i % 6)).param("time", slots[i < 6 ? 0 : 1]).query(Long.class).single();
            for (int k = 0; k < 8; k++) {
                if ((k + i) % 3 == 0) {
                    jdbc.sql("""
                            INSERT INTO class_bookings (organization_id, class_id, member_id, status)
                            VALUES (:org, :cls, :member, CAST('confirmed' AS booking_status))""")
                        .param("org", org).param("cls", cls).param("member", client[k]).update();
                }
            }
        }
    }

    /** Crea una rutina con sus ejercicios y devuelve los ids de {@code routine_exercises} (el primero es el id de la rutina). */
    private long[] routine(long org, long trainer, long client, String name, String category, String level, int minutes,
                           int kcal, int daysAgo, boolean active, List<Long> exerciseIds) {
        long id = jdbc.sql("""
                INSERT INTO routines (organization_id, trainer_member_id, client_member_id, name, category, level,
                                      estimated_minutes, estimated_kcal, created_at, is_active)
                VALUES (:org, :trainer, :client, :name, CAST(:cat AS routine_category), CAST(:level AS routine_level),
                        :minutes, :kcal, now() - make_interval(days => :ago), :active)
                RETURNING id""")
            .param("org", org).param("trainer", trainer).param("client", client).param("name", name)
            .param("cat", category).param("level", level).param("minutes", minutes).param("kcal", kcal)
            .param("ago", daysAgo).param("active", active).query(Long.class).single();
        long[] out = new long[exerciseIds.size() + 1];
        out[0] = id;
        for (int i = 0; i < exerciseIds.size(); i++) {
            out[i + 1] = jdbc.sql("""
                    INSERT INTO routine_exercises (routine_id, exercise_id, exec_order, series, repetitions, rest_seconds)
                    VALUES (:r, :e, :o, :s, :reps, :rest) RETURNING id""")
                .param("r", id).param("e", exerciseIds.get(i)).param("o", i + 1).param("s", 3 + i % 2)
                .param("reps", 8 + (i * 2) % 8).param("rest", 45 + (i % 3) * 15).query(Long.class).single();
        }
        return out;
    }

    /** Entrenamientos de la rutina en los días indicados (días atrás). */
    private int workouts(long org, long client, long[] routine, LocalDate today, int[] daysAgo) {
        for (int d : daysAgo) {
            long log = jdbc.sql("""
                    INSERT INTO workout_logs (organization_id, member_id, routine_id, performed_on, created_at)
                    VALUES (:org, :m, :r, CAST(:d AS date), now()) RETURNING id""")
                .param("org", org).param("m", client).param("r", routine[0]).param("d", today.minusDays(d))
                .query(Long.class).single();
            int marked = Math.max(1, routine.length - 1 - d % 2);
            for (int i = 1; i <= marked; i++) {
                jdbc.sql("INSERT INTO workout_log_items (workout_log_id, routine_exercise_id) VALUES (:l, :i)")
                    .param("l", log).param("i", routine[i]).update();
            }
        }
        return daysAgo.length;
    }

    /** Ejercicios globales por grupo muscular; si el catálogo inicial no está cargado, se agregan unos básicos. */
    private Map<String, List<Long>> globalExercises() {
        long total = jdbc.sql("SELECT count(*) FROM exercises WHERE organization_id IS NULL AND is_active")
            .query(Long.class).single();
        if (total < 12) {
            for (String[] e : FALLBACK_EXERCISES) {
                boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM exercises WHERE organization_id IS NULL "
                        + "AND lower(name) = lower(:n))").param("n", e[0]).query(Boolean.class).single();
                if (!exists) {
                    jdbc.sql("INSERT INTO exercises (name, muscle_group, is_active) VALUES (:n, :g, true)")
                        .param("n", e[0]).param("g", e[1]).update();
                }
            }
        }
        Map<String, List<Long>> byGroup = new LinkedHashMap<>();
        jdbc.sql("SELECT id, muscle_group FROM exercises WHERE organization_id IS NULL AND is_active "
                + "AND muscle_group IS NOT NULL ORDER BY id")
            .query((rs, n) -> new Object[] {rs.getLong(1), rs.getString(2)}).list()
            .forEach(r -> byGroup.computeIfAbsent((String) r[1], g -> new ArrayList<>()).add((Long) r[0]));
        return byGroup;
    }

    /** Un ejercicio por grupo pedido (rotando con {@code shift}); sin repetir y con al menos uno. */
    private static List<Long> choose(Map<String, List<Long>> byGroup, int shift, String... groups) {
        List<Long> picked = new ArrayList<>();
        for (int i = 0; i < groups.length; i++) {
            List<Long> pool = byGroup.get(groups[i]);
            if (pool == null || pool.isEmpty()) {
                continue;
            }
            Long id = pool.get((shift + i) % pool.size());
            if (!picked.contains(id)) {
                picked.add(id);
            }
        }
        if (picked.isEmpty()) {
            byGroup.values().stream().flatMap(List::stream).limit(3).forEach(picked::add);
        }
        return picked;
    }

    private static String equipmentImage(String category) {
        return switch (category) {
            case "Cardio" -> "/img/equipos/cardio.svg";
            case "Fuerza" -> "/img/equipos/fuerza.svg";
            case "Pesas libres" -> "/img/equipos/libres.svg";
            case "Funcional" -> "/img/equipos/funcional.svg";
            default -> "/img/equipos/default.svg";
        };
    }

    // =====================================================================================
    // Auxiliares
    // =====================================================================================

    private long ensureEps() {
        return jdbc.sql("SELECT id FROM health_insurance_providers WHERE name = 'EPS Demo (ficticia)'")
            .query(Long.class).optional()
            .orElseGet(() -> jdbc.sql("""
                    INSERT INTO health_insurance_providers (name, is_active)
                    VALUES ('EPS Demo (ficticia)', true) RETURNING id""").query(Long.class).single());
    }

    private long insertUser(String email, String document, String first, String last, int birthYear, String hash) {
        return jdbc.sql("""
                INSERT INTO users (document_type, document_number, first_name, last_name, birth_date, email,
                                   password_hash, must_change_password, is_platform_admin, is_active)
                VALUES (CAST('CC' AS document_type), :document, :first, :last, CAST(:birth AS date), :email,
                        :hash, false, false, true)
                RETURNING id""")
            .param("document", document).param("first", first).param("last", last)
            .param("birth", LocalDate.of(birthYear, 3 + birthYear % 9, 1 + birthYear % 27))
            .param("email", email + "@nexfit.test").param("hash", hash).query(Long.class).single();
    }

    private long staff(long org, String email, String document, String first, String last, int birthYear, String role,
                       String hash) {
        long user = insertUser(email, document, first, last, birthYear, hash);
        long member = jdbc.sql("INSERT INTO organization_members (organization_id, user_id, is_active) "
                + "VALUES (:org, :user, true) RETURNING id").param("org", org).param("user", user)
            .query(Long.class).single();
        jdbc.sql("INSERT INTO member_roles (member_id, role, organization_id) VALUES (:m, CAST(:role AS member_role), :o)")
            .param("m", member).param("role", role).param("o", org).update();
        return member;
    }

    private long addClient(long org, String email, String document, String first, String last, int birthYear,
                           String hash, long eps) {
        long user = insertUser(email, document, first, last, birthYear, hash);
        long member = jdbc.sql("""
                INSERT INTO organization_members (organization_id, user_id, eps_id, privacy_accepted_at,
                                                  health_consent_at, is_active)
                VALUES (:org, :user, :eps, now(), now(), true) RETURNING id""")
            .param("org", org).param("user", user).param("eps", eps).query(Long.class).single();
        jdbc.sql("INSERT INTO member_roles (member_id, role, organization_id) VALUES (:m, CAST('client' AS member_role), :o)")
            .param("m", member).param("o", org).update();
        jdbc.sql("""
                INSERT INTO emergency_contacts (organization_id, member_id, full_name, relationship, phone, is_primary)
                VALUES (:o, :m, :name, :rel, '3000000000', true)""")
            .param("o", org).param("m", member).param("name", "Contacto de " + first)
            .param("rel", birthYear % 2 == 0 ? "Madre" : "Hermano(a)").update();
        return member;
    }

    private long plan(long org, String name, int price, int days) {
        return jdbc.sql("""
                INSERT INTO membership_plans (organization_id, name, price, duration_days, benefits, is_active)
                VALUES (:org, :name, :price, :days, :benefits, true) RETURNING id""")
            .param("org", org).param("name", name).param("price", price).param("days", days)
            .param("benefits", "Acceso a todas las sedes y clases grupales").query(Long.class).single();
    }

    private void contract(long org, long member, long plan, int days, long registeredBy, LocalDate start) {
        long membership = jdbc.sql("""
                INSERT INTO memberships (organization_id, member_id, plan_id, created_by_member_id, start_date, end_date,
                                         status)
                VALUES (:org, :member, :plan, :by, CAST(:start AS date), CAST(:end AS date),
                        CAST('active' AS membership_status))
                RETURNING id""")
            .param("org", org).param("member", member).param("plan", plan).param("by", registeredBy)
            .param("start", start).param("end", start.plusDays(days)).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO payments (organization_id, member_id, membership_id, registered_by_member_id, concept, amount,
                                      paid_at, method, status, completed_at, completed_by_member_id)
                SELECT CAST(:org AS bigint), CAST(:member AS bigint), CAST(:membership AS bigint), CAST(:by AS bigint),
                       'Membresía (dato ficticio)', mp.price, CAST(:start AS timestamptz),
                       CAST('transfer' AS payment_method), CAST('completed' AS payment_status),
                       CAST(:start AS timestamptz), CAST(:by AS bigint)
                  FROM membership_plans mp WHERE mp.id = :plan""")
            .param("org", org).param("member", member).param("membership", membership).param("by", registeredBy)
            .param("start", start).param("plan", plan).update();
    }

    private void checkIn(long org, long member, long branch, long registeredBy, LocalDate day, int k) {
        int hour = 6 + k % 12;
        jdbc.sql("""
                INSERT INTO check_ins (organization_id, member_id, branch_id, registered_by_member_id, check_in_at,
                                       check_out_at, auto_closed)
                VALUES (:org, :member, :branch, :by,
                        (CAST(:day AS date) + make_time(:hour, 0, 0)) AT TIME ZONE 'America/Bogota',
                        (CAST(:day AS date) + make_time(:hour + 1, 0, 0)) AT TIME ZONE 'America/Bogota', false)""")
            .param("org", org).param("member", member).param("branch", branch).param("by", registeredBy)
            .param("day", day).param("hour", hour).update();
    }
}
