# Project Structure

## Root Level

```
OuterTune/
├── app/                    # Main application module
├── innertube/             # YouTube Music API client module
├── kugou/                 # KuGou lyrics provider module
├── lrclib/                # LrcLib lyrics provider module
├── taglib/                # Native tag extraction module
├── ffMetadataEx/          # FFmpeg metadata extractor (full variant only)
├── material-color-utilities/  # Material color system module
└── build.gradle.kts       # Root build configuration
```

## App Module Structure

### Main Package: `com.dd3boh.outertune`

```
app/src/main/java/com/dd3boh/outertune/
├── constants/             # App-wide constants and configuration
├── db/                    # Room database, DAOs, entities
│   ├── daos/             # Data Access Objects
│   └── entities/         # Database entity classes
├── di/                    # Hilt dependency injection modules
├── extensions/            # Kotlin extension functions
├── lyrics/                # Lyrics providers and helpers
├── models/                # Data models and DTOs
├── playback/              # Media playback logic
│   ├── downloadManager/  # Download management
│   └── queues/           # Queue management
├── services/              # Android services (messaging, etc.)
├── social/                # Social features repository
├── sync/                  # Sync features (Spotify, etc.)
├── ui/                    # UI layer (Compose)
│   ├── component/        # Reusable UI components
│   ├── dialog/           # Dialog components
│   ├── menu/             # Menu components
│   ├── player/           # Player UI
│   ├── screens/          # Screen composables
│   ├── theme/            # Material theme configuration
│   └── utils/            # UI utilities
├── utils/                 # General utilities
│   ├── dud/              # Stub implementations
│   ├── potoken/          # PO token handling
│   └── scanners/         # Media scanners
├── viewmodels/            # ViewModels for screens
├── App.kt                 # Application class
└── MainActivity.kt        # Main activity
```

## Architecture Patterns

### MVVM with Compose
- ViewModels manage UI state and business logic
- Compose screens observe ViewModel state via StateFlow
- Repository pattern for data access
- Hilt for dependency injection throughout

### Database Layer
- Room database: `MusicDatabase`
- DAOs in `db/daos/` for data operations
- Entities in `db/entities/` for database tables
- Schema versioning with migrations

### Playback Architecture
- `MusicService`: Foreground service for playback
- `PlayerConnection`: Interface to media session
- `QueueBoard`: Multi-queue management
- `MediaLibrarySessionCallback`: Media session callbacks

### UI Organization
- Screens: Full-screen composables in `ui/screens/`
- Components: Reusable UI elements in `ui/component/`
- Dialogs: Modal dialogs in `ui/dialog/`
- Menus: Context menus in `ui/menu/`
- Theme: Material 3 theming in `ui/theme/`

## Key Files

- `App.kt`: Application initialization, Hilt setup
- `MainActivity.kt`: Single activity, Compose navigation
- `MusicService.kt`: Media playback service
- `MusicDatabase.kt`: Room database definition
- `AppModule.kt`: Hilt dependency injection configuration

## Resource Organization

```
app/src/main/res/
├── drawable/              # Vector drawables and icons
├── mipmap-*/             # App launcher icons
├── values/               # Default strings, styles, colors
├── values-<lang>/        # Localized strings (managed via Weblate)
└── xml/                  # XML configurations
```

## Build Configuration

- `app/build.gradle.kts`: App module build config
- `build.gradle.kts`: Root project config
- `settings.gradle.kts`: Module inclusion and repositories
- `gradle/libs.versions.toml`: Version catalog (implied)

## Testing Structure

- Unit tests: `app/src/test/`
- Instrumented tests: `app/src/androidTest/`
- Test resources: `app/src/test/resources/`

## Important Conventions

- Package structure follows feature-based organization
- ViewModels are named `<Feature>ViewModel`
- Screens are named `<Feature>Screen`
- Extensions are in `<Type>Ext.kt` files
- Constants are grouped by domain in `constants/`
- All UI code uses Jetpack Compose (no XML layouts)
