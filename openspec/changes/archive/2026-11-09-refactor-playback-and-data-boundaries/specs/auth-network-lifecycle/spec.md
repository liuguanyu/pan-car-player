## ADDED Requirements

### Requirement: Authentication state has controlled writes
Authentication state SHALL be represented by an immutable or copied snapshot, and refresh, device polling, and logout SHALL be serialized or invalidated by generation.

#### Scenario: Logout races with device polling
- **WHEN** logout occurs before a pending device poll completes successfully
- **THEN** the stale poll SHALL not restore the logged-out account

#### Scenario: A refresh response omits refresh token
- **WHEN** a successful refresh response has no new refresh token
- **THEN** the existing refresh token SHALL be retained

### Requirement: Network work is cancellable and testable
Network operations SHALL use repository boundaries with an explicit executor/callback thread contract and SHALL support lifecycle cancellation or logical invalidation.

#### Scenario: An activity stops during playlist loading
- **WHEN** the observing activity stops
- **THEN** the operation SHALL be cancelled or its callback SHALL be prevented from updating the stopped UI

### Requirement: Production logs do not expose credentials
Production network logging SHALL not record access tokens, refresh tokens, or token-bearing URLs.

#### Scenario: A token-bearing request is logged
- **WHEN** a request or response is logged in production
- **THEN** credentials SHALL be omitted or redacted
