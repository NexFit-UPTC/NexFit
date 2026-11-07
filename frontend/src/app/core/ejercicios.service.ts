import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface Ejercicio {
  id: number;
  scope: 'global' | 'own';
  name: string;
  muscleGroup: string | null;
  description: string | null;
  imageUrl: string | null;
  active: boolean;
}

export interface DatosEjercicio {
  name: string;
  muscleGroup: string | null;
  description: string | null;
  imageUrl: string | null;
}

export interface FiltroEjercicios {
  q?: string;
  muscleGroup?: string;
  scope?: 'all' | 'global' | 'own';
  active?: boolean;
}

@Injectable({ providedIn: 'root' })
export class EjerciciosService {
  private readonly http = inject(HttpClient);

  listar(filtro: FiltroEjercicios = {}): Observable<Ejercicio[]> {
    let params = new HttpParams();
    if (filtro.q?.trim()) params = params.set('q', filtro.q.trim());
    if (filtro.muscleGroup?.trim()) params = params.set('muscleGroup', filtro.muscleGroup.trim());
    if (filtro.scope && filtro.scope !== 'all') params = params.set('scope', filtro.scope);
    if (filtro.active !== undefined) params = params.set('active', filtro.active);
    return this.http.get<Ejercicio[]>('/api/exercises', { params });
  }

  crear(datos: DatosEjercicio): Observable<Ejercicio> {
    return this.http.post<Ejercicio>('/api/exercises', datos);
  }

  editar(id: number, datos: DatosEjercicio): Observable<Ejercicio> {
    return this.http.put<Ejercicio>(`/api/exercises/${id}`, datos);
  }

  activar(id: number, active: boolean): Observable<Ejercicio> {
    return this.http.patch<Ejercicio>(`/api/exercises/${id}/active`, { active });
  }
}
