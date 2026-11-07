## ADDED Requirements

### Requirement: Lyrics results are bound to song identity
The lyrics system SHALL associate every load with a stable song identity and SHALL deliver results only when that identity is still current.

#### Scenario: An old lyrics request completes after a song change
- **WHEN** lyrics for song A complete after the current song changed to B
- **THEN** the UI SHALL ignore A's result and SHALL continue displaying B's lyrics state

### Requirement: Lyrics requests have lifecycle cancellation
The lyrics coordinator SHALL cancel or logically invalidate pending work when a new song is selected or its observing screen stops.

#### Scenario: A new song is selected
- **WHEN** a new song is selected while lyrics are loading
- **THEN** the old request SHALL be cancelled or invalidated before the new request is committed

#### Scenario: The player screen stops
- **WHEN** the player screen stops observing
- **THEN** pending lyrics callbacks SHALL not update destroyed views

### Requirement: Lyrics rendering follows playback state
The lyrics UI SHALL render from a state containing song identity and playback position instead of independently polling the concrete playback service.

#### Scenario: Playback is paused after seeking
- **WHEN** playback is paused at a new position
- **THEN** the lyrics view SHALL render the line corresponding to that position
