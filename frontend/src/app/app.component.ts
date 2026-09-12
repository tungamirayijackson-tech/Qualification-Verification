import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';

import { Role } from './core/auth/session.model';
import { SessionService } from './core/auth/session.service';
import { IconComponent, IconName } from './shared/icon.component';

/** One destination in the console's navigation. */
interface NavItem {
  readonly path: string;
  readonly label: string;
  readonly icon: IconName;
  /** What the screen is for, shown under the label in the sidebar. */
  readonly hint: string;
  /** Who may see it. Empty means anyone, `null` means any signed-in user. */
  readonly roles: readonly Role[] | null;
  readonly exact?: boolean;
}

interface NavGroup {
  readonly heading: string;
  readonly items: readonly NavItem[];
}

/**
 * The whole navigation, in one table.
 *
 * Grouped the way the work is actually divided rather than by which controller serves it: a
 * registrar records awards, an auditor examines the record, and everybody occasionally needs to
 * look something up. A registrar who wants to add a graduate should not have to know that the
 * screen for one and the screen for four hundred are different screens until they are looking
 * at both of them side by side.
 */
const NAVIGATION: readonly NavGroup[] = [
  {
    heading: 'Register',
    items: [
      {
        path: '/credentials',
        label: 'Search the register',
        icon: 'search',
        hint: 'Find a recorded qualification',
        roles: ['REGISTRAR', 'AUDITOR'],
        exact: true
      },
      {
        path: '/credentials/register',
        label: 'Register a qualification',
        icon: 'award',
        hint: 'Record and sign one award',
        roles: ['REGISTRAR']
      },
      {
        path: '/credentials/import',
        label: 'Import a cohort',
        icon: 'upload',
        hint: 'A graduation list, as CSV',
        roles: ['REGISTRAR']
      }
    ]
  },
  {
    heading: 'Administration',
    items: [
      {
        path: '/users',
        label: 'Accounts',
        icon: 'user',
        hint: 'Who may do what',
        roles: ['ADMIN']
      }
    ]
  },
  {
    heading: 'Oversight',
    items: [
      {
        path: '/audit',
        label: 'Audit ledger',
        icon: 'ledger',
        hint: 'Every change, in order',
        roles: ['AUDITOR']
      }
    ]
  },
  {
    heading: 'Reference',
    items: [
      {
        path: '/qualifications',
        label: 'Qualifications',
        icon: 'award',
        hint: 'What each body offers',
        roles: ['REGISTRAR', 'AUDITOR', 'ADMIN']
      },
      {
        path: '/institutions',
        label: 'Awarding bodies',
        icon: 'building',
        hint: 'Who may confer what',
        roles: null
      },
      {
        path: '/verify',
        label: 'Check a qualification',
        icon: 'shieldCheck',
        hint: 'The public front door',
        roles: []
      }
    ]
  }
];

/**
 * The application shell.
 *
 * <p>There are two layouts, because there are two audiences and pretending otherwise serves
 * neither. Someone checking a qualification arrives from a link, has no account, and needs one
 * thing: a wide, quiet page with nothing to navigate. Someone working in the console arrives
 * every day, moves between four or five screens, and benefits from all of them being visible at
 * once. The route itself says which it is — see `data: { shell: 'console' }` in the route table
 * — rather than the shell guessing from the URL or from whether anyone is signed in, since
 * `/verify` is a public page whether or not a registrar happens to be logged in.
 *
 * <p>The navigation shows only what the signed-in role can actually use. That is presentation,
 * not protection — the API refuses the rest regardless — but a menu offering an auditor a
 * "register a qualification" link they will be denied is a menu that teaches people to distrust
 * it.
 */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, IconComponent],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss'
})
export class AppComponent {
  protected readonly session = inject(SessionService);
  private readonly router = inject(Router);

  /** Open state of the navigation on a narrow screen. Ignored entirely on a wide one. */
  protected readonly menuOpen = signal(false);

  private readonly lastNavigation = toSignal(
    this.router.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd)
    ),
    { initialValue: null }
  );

  /**
   * Which layout the current route asks for.
   *
   * Reads `lastNavigation` only to take a dependency on it: the router's state is mutable and
   * would not otherwise tell a computed signal that it had changed.
   */
  protected readonly shell = computed<'console' | 'public'>(() => {
    this.lastNavigation();

    let route = this.router.routerState.root;
    while (route.firstChild) {
      route = route.firstChild;
    }
    return route.snapshot.data['shell'] === 'console' ? 'console' : 'public';
  });

  /** The navigation as this particular user should see it, with empty groups dropped. */
  protected readonly groups = computed<readonly NavGroup[]>(() =>
    NAVIGATION.map((group) => ({
      heading: group.heading,
      items: group.items.filter((item) => this.maySee(item))
    })).filter((group) => group.items.length > 0)
  );

  /** The same destinations, flattened, for the slim public header. */
  protected readonly headerLinks = computed<readonly NavItem[]>(() =>
    this.groups().flatMap((group) => group.items)
  );

  protected closeMenu(): void {
    this.menuOpen.set(false);
  }

  protected toggleMenu(): void {
    this.menuOpen.update((open) => !open);
  }

  protected signOut(): void {
    this.closeMenu();
    this.session.signOut();
    void this.router.navigate(['/verify']);
  }

  private maySee(item: NavItem): boolean {
    if (item.roles === null) {
      return this.session.isSignedIn();
    }
    return item.roles.length === 0 || this.session.hasRole(...item.roles);
  }
}
