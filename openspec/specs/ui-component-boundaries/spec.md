# ui-component-boundaries Specification

## Purpose
TBD - created by archiving change refactor-playback-and-data-boundaries. Update Purpose after archive.
## Requirements
### Requirement: Activities render state and forward intents
Activities SHALL not directly own playback business state, network repositories, or ExoPlayer internals; they SHALL render observable UI state and forward user intents to coordinators.

#### Scenario: Player activity is recreated
- **WHEN** the player activity is recreated
- **THEN** it SHALL re-subscribe to the current state without starting a duplicate recovery or playback flow

### Requirement: Adapters are pure renderers
Adapters SHALL receive immutable row data including selection and current-playing state, SHALL render it, and SHALL report stable IDs for user actions without owning business selection state.

#### Scenario: A filtered row is clicked
- **WHEN** a filtered song row is clicked
- **THEN** the adapter SHALL report the stable song ID and SHALL not rely on a filtered position as the business identity

### Requirement: Visualizer lifecycle has one coordinator
Visualizer attachment, permission state, playback state, visibility, and release SHALL be coordinated by one lifecycle-aware component.

#### Scenario: The player screen stops
- **WHEN** the screen stops or the audio session becomes invalid
- **THEN** visualizer capture SHALL be released and no stale callback SHALL update the view

