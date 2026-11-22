import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface SedePublica {
  name: string;
  addressLine1: string;
  addressLine2: string | null;
  city: string;
  department: string;
}

export interface PlanPublico {
  name: string;
  price: number;
  durationDays: number;
}

export interface ResumenGimnasio {
  id: number;
  name: string;
  branches: SedePublica[];
}

export interface DetalleGimnasio extends ResumenGimnasio {
  plans: PlanPublico[];
}

export interface PaginaGimnasios {
  content: ResumenGimnasio[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** Gimnasios visibles sin sesión (NEX-97). Solo lectura. */
@Injectable({ providedIn: 'root' })
export class GimnasiosService {
  private readonly http = inject(HttpClient);

  listar(page: number, size: number): Observable<PaginaGimnasios> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<PaginaGimnasios>('/api/public/gyms', { params });
  }

  detalle(id: number): Observable<DetalleGimnasio> {
    return this.http.get<DetalleGimnasio>(`/api/public/gyms/${id}`);
  }
}
