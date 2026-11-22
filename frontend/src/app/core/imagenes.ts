/** Imagen por defecto cuando un equipo o ejercicio no trae `imageUrl`: una ilustración por categoría o grupo muscular. */

function sinAcentos(texto: string | null | undefined): string {
  return (texto ?? '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
}

export function imagenEquipo(e: { imageUrl: string | null; category: string | null }): string {
  if (e.imageUrl) return e.imageUrl;
  const c = sinAcentos(e.category);
  if (/cardio|caminadora|cinta|bicicleta|eliptic|remo/.test(c)) return '/img/equipos/cardio.svg';
  if (/fuerza|maquina|poleas?|prensa/.test(c)) return '/img/equipos/fuerza.svg';
  if (/libre|mancuerna|barra|disco|pesa/.test(c)) return '/img/equipos/libres.svg';
  if (/funcional|kettle|pesa rusa|cuerda|trx/.test(c)) return '/img/equipos/funcional.svg';
  return '/img/equipos/default.svg';
}

export function imagenEjercicio(e: { imageUrl: string | null; muscleGroup: string | null }): string {
  if (e.imageUrl) return e.imageUrl;
  const g = sinAcentos(e.muscleGroup);
  if (/pecho|pectoral/.test(g)) return '/img/ejercicios/pecho.svg';
  if (/espalda|dorsal|lumbar/.test(g)) return '/img/ejercicios/espalda.svg';
  if (/hombro|deltoid/.test(g)) return '/img/ejercicios/hombros.svg';
  if (/brazo|bicep|tricep|antebrazo/.test(g)) return '/img/ejercicios/brazos.svg';
  if (/pierna|gluteo|cuadricep|femoral|pantorrilla|cadera/.test(g)) return '/img/ejercicios/piernas.svg';
  if (/core|abdom|oblicuo/.test(g)) return '/img/ejercicios/core.svg';
  if (/cardio|resistencia/.test(g)) return '/img/ejercicios/cardio.svg';
  return '/img/ejercicios/default.svg';
}
