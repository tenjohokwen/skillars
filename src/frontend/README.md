# skillars (frontend)

Marketing platform

## Install the dependencies

```bash
yarn
# or
npm install
```

### Start the app in development mode (hot-code reloading, error reporting, etc.)

```bash
quasar dev
```

### Lint the files

```bash
yarn lint
# or
npm run lint
```

### Format the files

```bash
yarn format
# or
npm run format
```

### Run the unit tests

Vitest + Vue Test Utils. Not part of `mvn verify` — opt-in only
(see [docs/testing/frontend-unit-tests.md](../../docs/testing/frontend-unit-tests.md)).

```bash
npm run test:unit
# or, watch mode
npm run test:unit:watch
```

### Build the app for production

```bash
quasar build
```

### Customize the configuration

See [Configuring quasar.config.js](https://v2.quasar.dev/quasar-cli-vite/quasar-config-js).
