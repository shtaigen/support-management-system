import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatToolbar } from '@angular/material/toolbar';
import { RouterOutlet } from '@angular/router';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, MatToolbar],
  selector: 'app-root',
  templateUrl: './app.html',
})
export class App {}
