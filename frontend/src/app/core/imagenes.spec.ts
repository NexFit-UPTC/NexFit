import { imagenEjercicio, imagenEquipo } from './imagenes';

describe('imagenes por defecto', () => {
  it('usa la imagen propia cuando existe', () => {
    expect(imagenEquipo({ imageUrl: 'https://cdn/x.png', category: 'Cardio' })).toBe('https://cdn/x.png');
    expect(imagenEjercicio({ imageUrl: '/img/a.svg', muscleGroup: 'Pecho' })).toBe('/img/a.svg');
  });
  it('elige la ilustración del equipo por categoría, sin distinguir acentos ni mayúsculas', () => {
    const e = (category: string | null) => imagenEquipo({ imageUrl: null, category });
    expect(e('CARDIO')).toBe('/img/equipos/cardio.svg');
    expect(e('Máquina de fuerza')).toBe('/img/equipos/fuerza.svg');
    expect(e('Pesas libres')).toBe('/img/equipos/libres.svg');
    expect(e('Funcional')).toBe('/img/equipos/funcional.svg');
    expect(e(null)).toBe('/img/equipos/default.svg');
    expect(e('Otra cosa')).toBe('/img/equipos/default.svg');
  });
  it('elige la ilustración del ejercicio por grupo muscular', () => {
    const g = (muscleGroup: string | null) => imagenEjercicio({ imageUrl: null, muscleGroup });
    expect(g('Pecho')).toBe('/img/ejercicios/pecho.svg');
    expect(g('Espalda')).toBe('/img/ejercicios/espalda.svg');
    expect(g('Hombros')).toBe('/img/ejercicios/hombros.svg');
    expect(g('Bíceps')).toBe('/img/ejercicios/brazos.svg');
    expect(g('Glúteos')).toBe('/img/ejercicios/piernas.svg');
    expect(g('Abdomen')).toBe('/img/ejercicios/core.svg');
    expect(g('Cardio')).toBe('/img/ejercicios/cardio.svg');
    expect(g(null)).toBe('/img/ejercicios/default.svg');
  });
});
