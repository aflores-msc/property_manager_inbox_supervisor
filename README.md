# Estate Beacon

A multi-module Gradle project with two independent implementations of the same property-management inbox triage app:

| Module | Language | Status |
|---|---|---|
| [`python-app/`](python-app/README.md) | Python 3.13, PyQt6, LangGraph, Gemini | Working application |
| `java-app/` | Java 25 | Scaffold only — not yet implemented |

Gradle orchestrates both modules as one build even though `python-app` has no JVM sources; see [`python-app/build.gradle.kts`](python-app/build.gradle.kts) for how its tasks shell out to a real Python virtual environment.

## Common tasks

```bash
./gradlew tasks                    # list everything available across both modules

./gradlew :python-app:run          # run the Python desktop app
./gradlew :python-app:packageApp   # build a native macOS bundle (PyInstaller)

./gradlew :java-app:run            # run the Java module (currently a placeholder)
./gradlew :java-app:build          # compile and test the Java module
```

## Layout

```
estate-beacon/
├── settings.gradle.kts     # declares the python-app and java-app modules
├── build.gradle.kts        # shared group/version
├── python-app/             # the working Python implementation (see its own README)
└── java-app/               # Java implementation — to be built out
```

Project-level documentation (architecture tutorials, etc.) lives in [`docs/`](docs/).
