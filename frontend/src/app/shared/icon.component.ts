import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * Every icon the console uses, as path data on a 24×24 grid.
 *
 * Drawn here rather than pulled from an icon package for two reasons. The content security
 * policy this application sets is `default-src 'self'`, which blocks the font files and remote
 * stylesheets an icon font would need — an icon set from a CDN would simply not render, and it
 * would fail silently, which is the worst way for a thing to fail. And a dependency that ships
 * a thousand glyphs to draw fourteen is weight the browser downloads on every visit.
 *
 * Each icon is a list of `d` attributes, stroked rather than filled, so a single stroke width
 * and `currentColor` keep them visually consistent with the text they sit beside. Adding one
 * means adding a line here; nothing else in the application needs to change.
 */
const ICON_PATHS = {
  // Verification and verdicts
  shield: ['M12 3 4 6v6c0 4.5 3.2 7.9 8 9 4.8-1.1 8-4.5 8-9V6l-8-3Z'],
  shieldCheck: ['M12 3 4 6v6c0 4.5 3.2 7.9 8 9 4.8-1.1 8-4.5 8-9V6l-8-3Z', 'm8.6 12 2.5 2.5 4.4-5'],
  checkCircle: ['M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18Z', 'm8 12.2 2.8 2.8L16 9.6'],
  xCircle: ['M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18Z', 'm9.2 9.2 5.6 5.6', 'm14.8 9.2-5.6 5.6'],
  helpCircle: [
    'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18Z',
    'M9.7 9.6A2.4 2.4 0 0 1 14.4 10c0 1.7-2.4 2-2.4 3.6',
    'M12 16.9h.01'
  ],
  alert: [
    'M10.7 4.6 2.9 17.7A1.5 1.5 0 0 0 4.2 20h15.6a1.5 1.5 0 0 0 1.3-2.3L13.3 4.6a1.5 1.5 0 0 0-2.6 0Z',
    'M12 9.6v4',
    'M12 16.8h.01'
  ],

  // Navigation destinations
  search: ['M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14Z', 'm16.1 16.1 4.4 4.4'],
  award: [
    'M12 3a5.5 5.5 0 1 0 0 11 5.5 5.5 0 0 0 0-11Z',
    'm8.7 13.4-1.5 7.1L12 18l4.8 2.5-1.5-7.1'
  ],
  upload: [
    'M4 15v3.5A1.5 1.5 0 0 0 5.5 20h13a1.5 1.5 0 0 0 1.5-1.5V15',
    'M12 15.5V4',
    'm7.6 8.4 4.4-4.4 4.4 4.4'
  ],
  building: [
    'M4 20V6.5A1.5 1.5 0 0 1 5.5 5h7A1.5 1.5 0 0 1 14 6.5V20',
    'M14 10h4.5A1.5 1.5 0 0 1 20 11.5V20',
    'M3 20h18',
    'M7.2 9h3.4',
    'M7.2 13h3.4',
    'M7.2 17h3.4',
    'M16.8 14h.8'
  ],
  ledger: [
    'M5 5.5A1.5 1.5 0 0 1 6.5 4H15l4 4v11.5a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 19.5Z',
    'M14.5 4v4.5H19',
    'M8.5 13.5h7',
    'M8.5 17h4.5'
  ],
  home: ['m3.5 11.2 8.5-6.7 8.5 6.7', 'M6 9.8V20h12V9.8'],

  // Session
  signIn: [
    'M10.5 4H6.5A1.5 1.5 0 0 0 5 5.5v13A1.5 1.5 0 0 0 6.5 20h4',
    'm15 8.2 3.8 3.8L15 15.8',
    'M18.5 12H9.5'
  ],
  signOut: [
    'M13.5 4h4A1.5 1.5 0 0 1 19 5.5v13a1.5 1.5 0 0 1-1.5 1.5h-4',
    'm9 8.2-3.8 3.8L9 15.8',
    'M5.5 12h9'
  ],
  user: [
    'M12 4.5a3.6 3.6 0 1 0 0 7.2 3.6 3.6 0 0 0 0-7.2Z',
    'M5 20c0-3.7 3.1-6.2 7-6.2s7 2.5 7 6.2'
  ],
  key: [
    'M20 4.6a4.6 4.6 0 0 0-7.5 5.1L4 18.2V21h2.8v-1.9h1.9v-1.9h1.9l1.7-1.7A4.6 4.6 0 0 0 20 4.6Z',
    'M17.2 7.1h.01'
  ],
  lock: [
    'M6.2 10.8h11.6a1.2 1.2 0 0 1 1.2 1.2v7.3a1.2 1.2 0 0 1-1.2 1.2H6.2A1.2 1.2 0 0 1 5 19.3V12a1.2 1.2 0 0 1 1.2-1.2Z',
    'M8.4 10.8V8a3.6 3.6 0 0 1 7.2 0v2.8'
  ],

  // Controls
  menu: ['M4 7h16', 'M4 12h16', 'M4 17h16'],
  close: ['m6.5 6.5 11 11', 'm17.5 6.5-11 11'],
  chevronRight: ['m9.8 6 6 6-6 6'],
  download: [
    'M4 15v3.5A1.5 1.5 0 0 0 5.5 20h13a1.5 1.5 0 0 0 1.5-1.5V15',
    'M12 4v11.5',
    'm7.6 11.1 4.4 4.4 4.4-4.4'
  ],
  copy: [
    'M9.5 8h9A1.5 1.5 0 0 1 20 9.5v9a1.5 1.5 0 0 1-1.5 1.5h-9A1.5 1.5 0 0 1 8 18.5v-9A1.5 1.5 0 0 1 9.5 8Z',
    'M5.5 16A1.5 1.5 0 0 1 4 14.5v-9A1.5 1.5 0 0 1 5.5 4h9A1.5 1.5 0 0 1 16 5.5'
  ],
  link: [
    'M10.2 13.8a3.6 3.6 0 0 0 5.1 0l2.9-2.9a3.6 3.6 0 0 0-5.1-5.1l-1.3 1.3',
    'M13.8 10.2a3.6 3.6 0 0 0-5.1 0l-2.9 2.9a3.6 3.6 0 0 0 5.1 5.1l1.3-1.3'
  ]
} as const;

export type IconName = keyof typeof ICON_PATHS;

/**
 * One icon, sized to the text beside it.
 *
 * Always `aria-hidden`: an icon in this console never carries meaning on its own. Every one of
 * them sits next to a written label, or inside a control that has an accessible name of its
 * own, because an interface that says "revoked" only in red and only as a symbol has told a
 * screen-reader user nothing at all.
 */
@Component({
  selector: 'app-icon',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg
      [attr.width]="size()"
      [attr.height]="size()"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      [attr.stroke-width]="weight()"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      @for (d of paths(); track d) {
        <path [attr.d]="d" />
      }
    </svg>
  `,
  styles: `
    :host {
      display: inline-flex;
      flex: none;
      align-items: center;
      justify-content: center;
    }
  `
})
export class IconComponent {
  readonly name = input.required<IconName>();
  readonly size = input(18);

  /** Thinner strokes at larger sizes, so a 48px icon does not read as a heavier weight. */
  protected readonly weight = computed(() => (this.size() >= 32 ? 1.5 : 1.75));

  protected readonly paths = computed<readonly string[]>(() => ICON_PATHS[this.name()]);
}
