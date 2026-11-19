import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { NotificationOutletComponent } from './core/notifications/notification-outlet.component';
import { ActionDialogComponent } from './shared/dialog/action-dialog.component';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, NotificationOutletComponent, ActionDialogComponent],
  template: '<router-outlet /><app-notification-outlet /><app-action-dialog />',
  styleUrl: './app.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {}
