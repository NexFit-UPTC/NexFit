import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface Plan {
  id: number;
  name: string;
  description: string | null;
  price: number;
  durationDays: number;
  benefits: string | null;
  active: boolean;
}

export type DatosPlan = Omit<Plan, 'id' | 'active'>;

@Injectable({ providedIn: 'root' })
export class PlanesService {
  private readonly http = inject(HttpClient);

  /** El Administrador recibe también los inactivos. */
  listar(): Observable<Plan[]> {
    return this.http.get<Plan[]>('/api/membership-plans');
  }

  crear(datos: DatosPlan): Observable<Plan> {
    return this.http.post<Plan>('/api/membership-plans', datos);
  }

  editar(id: number, datos: DatosPlan): Observable<Plan> {
    return this.http.put<Plan>(`/api/membership-plans/${id}`, datos);
  }

  activar(id: number, active: boolean): Observable<Plan> {
    return this.http.patch<Plan>(`/api/membership-plans/${id}/active`, { active });
  }
}
