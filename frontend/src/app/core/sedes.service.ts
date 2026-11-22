import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface Sede {
  id: number;
  name: string;
  phone: string | null;
  schedule: string | null;
  addressLine1: string;
  addressLine2: string | null;
  city: string;
  department: string;
  postalCode: string | null;
  country: string;
  active: boolean;
}

export type DatosSede = Omit<Sede, 'id' | 'active'>;

@Injectable({ providedIn: 'root' })
export class SedesService {
  private readonly http = inject(HttpClient);

  /** El Administrador recibe también las inactivas. */
  listar(): Observable<Sede[]> {
    return this.http.get<Sede[]>('/api/branches');
  }

  crear(datos: DatosSede): Observable<Sede> {
    return this.http.post<Sede>('/api/branches', datos);
  }

  editar(id: number, datos: DatosSede): Observable<Sede> {
    return this.http.put<Sede>(`/api/branches/${id}`, datos);
  }

  activar(id: number, active: boolean): Observable<Sede> {
    return this.http.patch<Sede>(`/api/branches/${id}/active`, { active });
  }
}
