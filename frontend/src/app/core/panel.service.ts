import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface PanelGimnasio {
  date: string;
  timezone: string;
  activeClients: number;
  currentMemberships: number;
  expiringMemberships: number;
  expiringInDays: number;
  monthRevenue: number;
  todayCheckIns: number;
  upcomingOccupancy: { classes: number; confirmedBookings: number; capacity: number; percent: number };
}

export interface CifrasPlataforma {
  gyms: number;
  branches: number;
  clients: number;
  currentMemberships: number;
}

@Injectable({ providedIn: 'root' })
export class PanelService {
  private readonly http = inject(HttpClient);

  gimnasio(): Observable<PanelGimnasio> {
    return this.http.get<PanelGimnasio>('/api/dashboard/gym');
  }

  plataforma(): Observable<CifrasPlataforma> {
    return this.http.get<CifrasPlataforma>('/api/dashboard/platform');
  }
}
