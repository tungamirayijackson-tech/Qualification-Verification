# QVS console

The Angular 18 front end. Two front doors live here (§04): the authenticated registrar console,
and — from week three — the public credential check that never requires an account.

## Run against a local backend

```bash
# from the repository root
docker compose up -d postgres redis
./gradlew :backend:bootRun --args='--spring.profiles.active=dev'

# then, here
npm start
```

`proxy.conf.json` forwards `/api`, `/public` and `/actuator` to `localhost:8080`, so the console
talks to one origin in development exactly as it does in the published image, where the API
serves these static files. There is no base-URL environment file to get wrong at deploy time.

## Verify

| Command | What it checks |
|---------|----------------|
| `npm run lint` | ESLint over TypeScript **and** templates, including accessibility rules |
| `npm run test:ci` | Karma/Jasmine in headless Chrome, with coverage |
| `npm run build` | Production build, subject to the bundle budgets in `angular.json` |

Template accessibility is linted rather than hoped for: usability is one of the four criteria
the working system is marked on.

## Conventions

- **Standalone components only.** No `NgModule`.
- **Signals for component state.** The institution list distinguishes `loading`, `ready` and
  `failed`; a screen that renders "empty" while it is still loading is the usual way a list
  misleads its reader.
- **The API decides, the console displays.** Eligibility, verdicts and revocation are domain
  decisions made server-side. Anything the console computes locally is presentation only.
- **Interfaces mirror the API's response shape.** If a screen needs a field, the contract
  changes first and the OpenAPI diff shows it in the pull request.
