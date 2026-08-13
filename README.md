# Twilio Voice React Native SDK (Close fork)

This is a Close fork of
[twilio/twilio-voice-react-native](https://github.com/twilio/twilio-voice-react-native).
The Close mobile app uses it as a git dependency. We do not publish it to npm.

## Differences from upstream

These parts of upstream are removed:

- the test app
- the documentation and the API report tooling
- the release tooling
- CI and GitHub files

## Repo layout

- `src/` — TypeScript source
- `android/`, `ios/` — native code
- `lib/` — build output. It is committed.
- `constants/`, `scripts/` — code generation

## Native changes

Edit the files in `android/` or `ios/`. Then build and test the change inside
the Close mobile app.

## TypeScript changes

1. Edit the files in `src/`.
2. Run `yarn prepare`.
3. Commit the changed files in `lib/`.

The app uses `lib/`. A change in `src/` has no effect until you rebuild `lib/`.

## Generated files

- After a change in `constants/constants.src`, run `yarn build:constants`.
- After a change in `scripts/errors.js`, run `yarn build:errors`.

Commit the generated files.

## Checks

```sh
yarn check:type
yarn check:lint
yarn test
```
