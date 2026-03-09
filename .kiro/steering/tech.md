# Technology Stack

## Build System

- Gradle with Kotlin DSL (build.gradle.kts)
- Android Gradle Plugin
- Kotlin 2.x with JVM target 21
- KSP (Kotlin Symbol Processing) for annotation processing

## Core Technologies

- Language: Kotlin
- Min SDK: 24 (Android 8.0 Oreo)
- Target SDK: 36
- Compile SDK: 36
- Java Version: 21

## Architecture & Libraries

### UI Framework
- Jetpack Compose (Material 3)
- Compose Navigation
- Adaptive layouts for tablets/foldables
- Coil for image loading

### Architecture Components
- Hilt for dependency injection
- Room database for local storage
- ViewModel + StateFlow for state management
- Coroutines + Flow for async operations
- DataStore for preferences

### Media Playback
- Media3 (ExoPlayer) for playback
- Media3 Session for media controls
- Media3 WorkManager for background tasks
- Custom download manager

### Networking
- Ktor client with OkHttp engine
- Ktor content negotiation with JSON serialization

### Firebase
- Firebase Auth
- Firebase Firestore
- Firebase Realtime Database

### Custom Modules
- `innertube`: YouTube Music API client
- `kugou`: KuGou lyrics provider
- `lrclib`: LrcLib lyrics provider
- `taglib`: Native tag extraction
- `ffMetadataEx`: FFmpeg-based metadata extraction (full variant only)
- `material-color-utilities`: Material color system

## Build Variants

### Flavors
- `core`: Standard build without FFmpeg features
- `full`: Includes FFmpeg metadata extractor and audio decoders (requires extra setup)

### Build Types
- `debug`: Development builds
- `release`: Production builds (no minification currently)
- `userdebug`: Release builds without minification, profileable

## Common Commands

### Building
```bash
# Clone with submodules
git clone --recurse-submodules <url>

# Update submodules if needed
git submodule update --init --recursive

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Build specific variant
./gradlew assembleCoreDebug
./gradlew assembleFullRelease
```

### Testing
```bash
# Run unit tests
./gradlew test

# Run instrumented tests
./gradlew connectedAndroidTest
```

### Code Quality
```bash
# Run lint checks
./gradlew lint

# Generate Compose compiler reports (if enabled)
./gradlew assembleDebug -PenableComposeCompilerReports=true
```

### Cleaning
```bash
# Clean build artifacts
./gradlew clean
```

## Important Notes

- Core library desugaring enabled for API 24+ compatibility
- Room schema location: `app/schemas/`
- Locale config auto-generated from resources
- Dependency metadata excluded from APKs for reproducible builds
- AboutLibraries plugin configured with strict license mode
