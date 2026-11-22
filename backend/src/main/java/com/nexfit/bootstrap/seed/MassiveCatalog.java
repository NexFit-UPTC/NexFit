package com.nexfit.bootstrap.seed;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Catálogos del conjunto {@code massive}: nombres y apellidos comunes en Colombia, municipios con barrios y
 * precios en COP. Las personas, documentos, teléfonos y números de dirección que se combinan con ellos son
 * sintéticos; los nombres de los gimnasios son inventados.
 */
final class MassiveCatalog {

    record Place(String city, String department, List<String> barrios) { }

    record PlanSpec(String name, int days, int basePrice, int weight) { }

    private MassiveCatalog() { }

    static final List<String> MALE = List.of("Juan", "Carlos", "Andrés", "Luis", "Jorge", "Camilo", "Santiago",
        "Daniel", "David", "Felipe", "Sebastián", "Alejandro", "Nicolás", "Mateo", "Julián", "Diego", "Miguel",
        "Fernando", "Ricardo", "Héctor", "Óscar", "Hernán", "Edwin", "Wilson", "Jhon", "Yeison", "Brayan",
        "Cristian", "Fabián", "Germán", "Iván", "Javier", "Mauricio", "Néstor", "Pablo", "Rafael", "Sergio",
        "Tomás", "Víctor", "William", "Alexander", "Edgar", "Gustavo", "Henry", "Jairo", "Leonardo", "Manuel",
        "Orlando", "Rodrigo", "Esteban", "Samuel", "Gabriel", "Johan", "Kevin", "Leandro", "Marlon", "Nelson",
        "Raúl", "Álvaro", "César");

    static final List<String> FEMALE = List.of("María", "Laura", "Valentina", "Sofía", "Daniela", "Camila", "Paula",
        "Andrea", "Natalia", "Carolina", "Juliana", "Diana", "Katherine", "Luisa", "Mariana", "Isabella", "Salomé",
        "Sara", "Gabriela", "Alejandra", "Angie", "Jenny", "Liliana", "Marcela", "Martha", "Patricia", "Sandra",
        "Claudia", "Luz", "Yolanda", "Adriana", "Beatriz", "Carmen", "Dayana", "Estefanía", "Fernanda", "Gloria",
        "Johana", "Karen", "Lorena", "Mónica", "Nubia", "Ximena", "Paola", "Rosa", "Tatiana", "Viviana", "Yessica",
        "Zulma", "Jessica", "Lina", "Manuela", "Michelle", "Nathalia", "Stephanie", "Yurany");

    static final List<String> SURNAMES = List.of("García", "Rodríguez", "Martínez", "López", "González",
        "Hernández", "Gómez", "Pérez", "Sánchez", "Ramírez", "Torres", "Díaz", "Vargas", "Castro", "Rojas",
        "Moreno", "Jiménez", "Ruiz", "Ortiz", "Gutiérrez", "Muñoz", "Álvarez", "Romero", "Suárez", "Cárdenas",
        "Peña", "Reyes", "Mendoza", "Cruz", "Herrera", "Acosta", "Rincón", "Pinto", "Salamanca", "Quintero",
        "Báez", "Camargo", "Barrera", "Niño", "Pulido", "Becerra", "Cely", "Fonseca", "Parra", "Sandoval",
        "Bautista", "Triana", "Avella", "Corredor", "Chaparro", "Montañez", "Molina", "Guerrero", "Medina",
        "Silva", "Cortés", "Soto", "Cepeda", "Forero", "Garzón", "Ladino", "Mesa", "Ospina", "Duarte", "Arias",
        "Bernal", "Cadena", "Delgado", "Escobar", "Franco", "Galindo", "Huertas", "Linares", "Mojica", "Nova",
        "Ochoa", "Patiño", "Quiroga", "Ramos", "Serrano", "Tibaduiza", "Useche", "Valderrama", "Zambrano");

    static final List<String> DOMAINS = List.of("gmail.com", "hotmail.com", "outlook.com");

    static final List<String> RELATIONSHIPS = List.of("Madre", "Padre", "Esposo(a)", "Hermano(a)", "Amigo(a)",
        "Pareja", "Hijo(a)", "Tío(a)");

    /** Municipios de Boyacá primero, luego ciudades principales. Los barrios son reales; los números, no. */
    static final List<Place> PLACES = List.of(
        new Place("Tunja", "Boyacá", List.of("Centro", "Maldonado", "Las Quintas", "Los Muiscas", "La Colina",
            "El Dorado", "Mesopotamia", "Santa Inés")),
        new Place("Duitama", "Boyacá", List.of("Centro", "Las Américas", "El Carmen", "La Esperanza")),
        new Place("Sogamoso", "Boyacá", List.of("Centro", "Santa Bárbara", "Las Nieves", "El Recreo")),
        new Place("Bogotá D.C.", "Bogotá D.C.", List.of("Chapinero", "Usaquén", "Suba", "Kennedy", "Teusaquillo",
            "Cedritos", "Chicó", "Engativá", "Fontibón", "Modelia")),
        new Place("Medellín", "Antioquia", List.of("El Poblado", "Laureles", "Belén", "Robledo", "Castilla",
            "Buenos Aires")),
        new Place("Cali", "Valle del Cauca", List.of("San Fernando", "Granada", "Ciudad Jardín", "El Peñón",
            "Versalles", "Tequendama")),
        new Place("Barranquilla", "Atlántico", List.of("El Prado", "Alto Prado", "Riomar", "Villa Country",
            "Boston")),
        new Place("Bucaramanga", "Santander", List.of("Cabecera", "Cañaveral", "García Rovira", "San Francisco",
            "Provenza")),
        new Place("Chiquinquirá", "Boyacá", List.of("Centro", "El Libertador", "La Esperanza")),
        new Place("Cartagena", "Bolívar", List.of("Bocagrande", "Manga", "Castillogrande", "El Laguito",
            "Getsemaní")),
        new Place("Paipa", "Boyacá", List.of("Centro", "El Jordán")),
        new Place("Cúcuta", "Norte de Santander", List.of("Caobos", "La Riviera", "Quinta Oriental", "Centro")),
        new Place("Pereira", "Risaralda", List.of("Centro", "Cuba", "Circunvalar", "Álamos")),
        new Place("Villavicencio", "Meta", List.of("Centro", "Barzal", "La Grama", "Kirpas")),
        new Place("Ibagué", "Tolima", List.of("Centro", "La Pola", "El Vergel", "Cádiz")));

    static final List<String> GYM_NAMES = List.of("Titanes Fitness Boyacá", "Centro Atlético Muisca", "Bacatá Gym",
        "Altiplano Fitness Club", "Sierra Nevada Training", "Iron House Colombia", "Fuerza Andina Gym",
        "Pulso Vital Fitness", "Zenit Sport Club", "Cóndor Gym", "Páramo Fitness", "Kinesis Training Center",
        "Latitud 5 Gym", "Ritmo y Músculo", "Atlas Wellness Club");

    /** Pesos relativos del tamaño de cada gimnasio. */
    static final double[] GYM_WEIGHTS = {1.7, 1.5, 1.3, 1.2, 1.1, 1.0, 1.0, 0.9, 0.9, 0.8, 0.8, 0.7, 0.7, 0.6, 0.5};

    /** Precios de lista en COP (se ajustan por gimnasio). */
    static final List<PlanSpec> PLANS = List.of(
        new PlanSpec("Semanal", 7, 40_000, 4),
        new PlanSpec("Mensual", 30, 85_000, 58),
        new PlanSpec("Trimestral", 90, 230_000, 20),
        new PlanSpec("Semestral", 180, 420_000, 9),
        new PlanSpec("Anual", 365, 750_000, 9));

    static final double[] PRICE_FACTORS = {0.85, 0.95, 1.0, 1.1, 1.25};

    /** Peso de cada EPS al asignarla a un cliente; las que no aparecen pesan 1. */
    static final Map<String, Integer> EPS_WEIGHTS = Map.ofEntries(
        Map.entry("Nueva EPS", 22), Map.entry("Sanitas", 14), Map.entry("Sura", 14), Map.entry("Salud Total", 10),
        Map.entry("Famisanar", 8), Map.entry("Coosalud", 5), Map.entry("SOS", 3), Map.entry("Mutual SER", 3),
        Map.entry("Aliansalud", 2), Map.entry("Capital Salud", 2), Map.entry("Savia Salud", 2),
        Map.entry("Salud Mía", 2), Map.entry("Asmet Salud", 2));

    static final List<String> CLASS_NAMES = List.of("Spinning", "Zumba", "Yoga", "Funcional", "Pilates",
        "CrossFit", "Rumba aeróbica", "Stretching", "Boxeo", "GAP", "Body Pump", "HIIT");

    static final List<String> SPECIALTIES = List.of("Fuerza e hipertrofia", "Acondicionamiento funcional",
        "Pérdida de peso", "Rehabilitación deportiva", "Ciclismo indoor", "Yoga y movilidad", "Entrenamiento de adultos mayores");

    static final List<String> CERTIFICATIONS = List.of("Entrenador personal certificado", "Técnico en entrenamiento deportivo",
        "Licenciado en Educación Física", "Profesional en Ciencias del Deporte");

    /** Equipos por sede: nombre, categoría. */
    static final List<String[]> EQUIPMENT = List.of(
        new String[] {"Caminadora", "Cardio"}, new String[] {"Elíptica", "Cardio"},
        new String[] {"Bicicleta estática", "Cardio"}, new String[] {"Bicicleta de spinning", "Cardio"},
        new String[] {"Remo", "Cardio"}, new String[] {"Escaladora", "Cardio"},
        new String[] {"Prensa de piernas", "Fuerza"}, new String[] {"Máquina de pecho", "Fuerza"},
        new String[] {"Jaula de sentadillas", "Fuerza"}, new String[] {"Polea alta", "Fuerza"},
        new String[] {"Banco plano", "Fuerza"}, new String[] {"Banco inclinado", "Fuerza"},
        new String[] {"Mancuernas (juego)", "Peso libre"}, new String[] {"Barra olímpica", "Peso libre"},
        new String[] {"Kettlebells (juego)", "Peso libre"}, new String[] {"Máquina de abductores", "Fuerza"},
        new String[] {"Colchonetas de yoga", "Funcional"}, new String[] {"TRX", "Funcional"});

    static String pick(List<String> list, Random rnd) {
        return list.get(rnd.nextInt(list.size()));
    }

    /** «García Pérez». */
    static String lastName(Random rnd) {
        return pick(SURNAMES, rnd) + " " + pick(SURNAMES, rnd);
    }

    /** Minúsculas, sin tildes ni caracteres raros: apto para el usuario de un correo. */
    static String fold(String text) {
        String plain = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return plain.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Celular colombiano de 10 dígitos que empieza en 3. */
    static String mobile(Random rnd) {
        int[] prefixes = {300, 301, 302, 304, 305, 310, 311, 312, 313, 314, 315, 316, 317, 318, 320, 321, 322,
            323, 324, 350, 351};
        return prefixes[rnd.nextInt(prefixes.length)] + String.format("%07d", rnd.nextInt(10_000_000));
    }

    /** Dirección con formato colombiano: «Carrera 9 # 18-41». */
    static String address(Random rnd) {
        String[] roads = {"Calle", "Carrera", "Avenida", "Diagonal", "Transversal", "Avenida Carrera"};
        return roads[rnd.nextInt(roads.length)] + " " + (1 + rnd.nextInt(80)) + " # " + (1 + rnd.nextInt(60))
            + "-" + (1 + rnd.nextInt(99));
    }
}
