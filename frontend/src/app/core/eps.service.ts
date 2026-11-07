import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface Eps {
  id: number;
  name: string;
}

@Injectable({ providedIn: 'root' })
export class EpsService {
  private readonly http = inject(HttpClient);

  /** EPS activas por nombre (catálogo global). */
  listar(): Observable<Eps[]> {
    return this.http.get<Eps[]>('/api/health-insurance-providers');
  }
}
