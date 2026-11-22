import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export type CategoriaRutina = 'strength' | 'cardio' | 'mobility';
export type NivelRutina = 'beginner' | 'intermediate' | 'advanced';

export const ETIQUETA_CATEGORIA: Record<CategoriaRutina, string> = {
  strength: 'Fuerza',
  cardio: 'Cardio',
  mobility: 'Movilidad'
};

export const ETIQUETA_NIVEL: Record<NivelRutina, string> = {
  beginner: 'Principiante',
  intermediate: 'Intermedio',
  advanced: 'Avanzado'
};

export interface ResumenRutina {
  id: number;
  trainerMemberId: number;
  trainerName: string;
  clientMemberId: number;
  clientName: string;
  name: string;
  category: CategoriaRutina | null;
  level: NivelRutina | null;
  estimatedMinutes: number | null;
  estimatedKcal: number | null;
  createdAt: string;
  active: boolean;
  exerciseCount: number;
}

export interface EjercicioDeRutina {
  id: number;
  exerciseId: number;
  exerciseName: string;
  muscleGroup: string | null;
  imageUrl: string | null;
  order: number;
  series: number;
  repetitions: number;
  restSeconds: number;
}

export interface Rutina extends Omit<ResumenRutina, 'exerciseCount'> {
  exercises: EjercicioDeRutina[];
}

export interface ItemRutina {
  exerciseId: number;
  order: number;
  series: number;
  repetitions: number;
  restSeconds: number;
}

export interface DatosRutina {
  name: string;
  category: CategoriaRutina | null;
  level: NivelRutina | null;
  estimatedMinutes: number | null;
  estimatedKcal: number | null;
  exercises: ItemRutina[];
}

export interface FiltroRutinas {
  clientMemberId?: number;
  category?: CategoriaRutina;
  active?: boolean;
}

@Injectable({ providedIn: 'root' })
export class RutinasService {
  private readonly http = inject(HttpClient);

  listar(filtro: FiltroRutinas = {}): Observable<ResumenRutina[]> {
    let params = new HttpParams();
    if (filtro.clientMemberId !== undefined) params = params.set('clientMemberId', filtro.clientMemberId);
    if (filtro.category) params = params.set('category', filtro.category);
    if (filtro.active !== undefined) params = params.set('active', filtro.active);
    return this.http.get<ResumenRutina[]>('/api/routines', { params });
  }

  detalle(id: number): Observable<Rutina> {
    return this.http.get<Rutina>(`/api/routines/${id}`);
  }

  crear(clientMemberId: number, datos: DatosRutina): Observable<Rutina> {
    return this.http.post<Rutina>('/api/routines', { clientMemberId, ...datos });
  }

  editar(id: number, datos: DatosRutina): Observable<Rutina> {
    return this.http.put<Rutina>(`/api/routines/${id}`, datos);
  }

  archivar(id: number): Observable<Rutina> {
    return this.http.patch<Rutina>(`/api/routines/${id}/archive`, {});
  }
}
