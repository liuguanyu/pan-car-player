## ADDED Requirements

### Requirement: Playback commands have a current request identity
The playback system SHALL assign a monotonically unique request identity to every load or selection command and SHALL commit results only for the current request and current song.

#### Scenario: An old download response arrives after a new selection
- **WHEN** song A is requested, then song B is requested, and A's URL response arrives after B's request is current
- **THEN** the system SHALL ignore A's response and SHALL not replace B in the player

#### Scenario: The playback service is destroyed during a request
- **WHEN** a playback request is pending and the service is destroyed
- **THEN** the request SHALL be invalidated and its callback SHALL not access a released player

### Requirement: Playback state is a single observable snapshot
The system SHALL expose playback state through a multi-subscriber state source containing the current song identity, queue position, play state, position, duration, mode, and audio session identity.

#### Scenario: Multiple screens subscribe
- **WHEN** two screens subscribe to playback state
- **THEN** both SHALL receive independent state updates and neither subscription SHALL replace the other

#### Scenario: A screen stops observing
- **WHEN** a screen unsubscribes or reaches its stopped lifecycle state
- **THEN** it SHALL receive no further callbacks and SHALL not be retained by the playback service

### Requirement: Playback commands preserve autoplay and seek intent
The system SHALL bind seek position and autoplay intent to the specific playback command rather than to global mutable fields.

#### Scenario: Restoring a saved position
- **WHEN** a saved position is restored for song A
- **THEN** that position SHALL be applied only when the matching request for song A becomes ready

#### Scenario: User pauses while media is loading
- **WHEN** the user pauses before the current media becomes ready
- **THEN** the player SHALL remain paused after preparation
