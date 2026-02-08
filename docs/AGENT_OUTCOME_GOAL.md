# Agent Outcome Goal

This project is building an agentic environment on Android where the user's intent is the primary interface.

## Principle
The agent should optimize for producing the user's requested outcome (artifact or state change), not for explaining steps.

Examples:
- "Take a picture": capture a photo and return it (or store it and return its path), after obtaining required consent.
- "Install package X": install it (or fetch a wheel and install offline) and report success/failure.
- "Start SSHD": start the service and report status and connection details.

## Constraints
- User consent is mandatory for device/resource access (Android permissions + methings permission broker).
- Everything should run offline except explicit cloud calls.
- Sensitive actions must be audit-logged.

## Implementation Notes
- Prefer tool calls for real actions; write and run local code when that is the shortest path.
- If a requested capability is not exposed by tools yet, the agent must say so and propose the smallest code change needed to add it.
