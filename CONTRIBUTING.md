# Contributing to improv_wifi

Thank you for your interest in contributing! This guide will help you get started.

## Getting Started

1. Fork the repository
2. Clone your fork with submodules:
   ```bash
   git clone --recurse-submodules https://github.com/<your-username>/improv_wifi_flutter.git
   ```
3. Create a feature branch:
   ```bash
   git checkout -b feature/my-change
   ```

## Development Setup

- Flutter SDK >= 3.3.0
- Dart SDK >= 3.10.7
- Xcode (for iOS development)
- Android Studio (for Android development)

Run the example app to verify your setup:
```bash
cd example
flutter run
```

## Making Changes

1. Keep changes focused and minimal — one concern per pull request.
2. Follow existing code style and conventions.
3. Test your changes on both Android and iOS when possible.
4. Update `CHANGELOG.md` under an `## Unreleased` section if your change is user-facing.

## Submitting a Pull Request

1. Push your branch to your fork.
2. Open a pull request against `main`.
3. Describe what your change does and why.
4. Link any related issues.

## Reporting Bugs

Use the [bug report template](https://github.com/azimut-energy/improv_wifi_flutter/issues/new?template=bug_report.md) and include:

- Flutter version (`flutter --version`)
- Device/OS details
- Steps to reproduce
- Expected vs actual behavior

## Code of Conduct

This project follows our [Code of Conduct](CODE_OF_CONDUCT.md). By participating, you agree to uphold it.

## License

By contributing, you agree that your contributions will be licensed under the [Apache 2.0 License](LICENSE).
