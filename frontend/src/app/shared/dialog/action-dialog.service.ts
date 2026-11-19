import { Injectable, signal } from '@angular/core';

export interface QuantityLine {
  id: string;
  label: string;
  max: number;
  initial?: number;
}

export interface DialogRequest {
  title: string;
  description: string;
  actionLabel: string;
  destructive?: boolean;
  kind: 'reason' | 'quantities';
  lines?: QuantityLine[];
  resolve: (value: string | Record<string, number> | null) => void;
}

/** In-app action confirmation; no native browser prompts or stored input. */
@Injectable({ providedIn: 'root' })
export class ActionDialogService {
  readonly current = signal<DialogRequest | null>(null);

  askReason(title: string, description: string, actionLabel: string, destructive = false): Promise<string | null> {
    return this.open({ title, description, actionLabel, destructive, kind: 'reason' }) as Promise<string | null>;
  }

  askQuantities(title: string, description: string, lines: QuantityLine[]): Promise<Record<string, number> | null> {
    return this.open({ title, description, actionLabel: 'Registrar cantidades', kind: 'quantities', lines }) as Promise<Record<string, number> | null>;
  }

  private open(request: Omit<DialogRequest, 'resolve'>): Promise<string | Record<string, number> | null> {
    // Prevent overlapping actions: do not silently overwrite another dialog.
    if (this.current()) return Promise.resolve(null);
    return new Promise((resolve) => this.current.set({ ...request, resolve }));
  }

  finish(value: string | Record<string, number> | null): void {
    const request = this.current();
    if (!request) return;
    this.current.set(null);
    request.resolve(value);
  }
}
