---
name: ts-skill
description: This skill should be used when the user wants to "write test scenarios", "create test cases", "plan testing", or "verify functionality". It follows expert testing principles including atomic testing, state machine validation, and contract testing.
---

# TS Skill: Expert Test Scenario Design and Documentation

This skill enables Claude to design comprehensive Test Scenarios (TS) that ensure system reliability and stability. It focuses on atomic verification, state transition edge cases, and API contract compliance, following project templates and professional testing mindsets.

## Testing Mindsets

Apply these expert-level thinking dimensions during the TS design process:

### 1. Expert Checklist Dimensions
- **Preconditions**: Must specify exact data states (e.g., "Account balance is exactly 0", "User has expired subscription").
- **Environmental Dependencies**: Define behavior when third-party APIs return 500 or are offline. Ensure graceful degradation.
- **Data Cleanup**: Ensure test data generation does not pollute the environment or impact subsequent tests.
- **Automation Potential**: Identify high-frequency paths and mark them for automation (e.g., "✅ Automated").

### 2. Atomic Testing (Minimal Path)
- **One TS, One Goal**: Each scenario should verify exactly one thing.
- **Example**: Separate "Invalid Password" from "Account Locked". Do not combine multiple failure modes in a single TS.
- **Benefit**: Provides clear, unambiguous automation reports.

### 3. State Machine Validation
- **Invalid Transitions**: Test transitions that should *not* be possible.
- **Example**: If a pet reservation is "Completed", verify that the system blocks an attempt to "Cancel" the reservation.
- **Coverage**: Ensure all invalid states are covered, especially in complex cloud or caregiving systems.

### 4. Speculative Maintenance (Contract Testing)
- **Focus on Contracts**: Ensure API Input/Output matches the specification regardless of underlying infrastructure changes (e.g., migrating to Valkey or GCP architecture shifts).
- **Decoupling**: Business logic should remain stable as long as the API contract is met.

## Workflow

### Step 1: Scenario Identification
- Analyze the SA (PRD) and SD documents to identify functional and non-functional test requirements.
- Map out the "Happy Path" and "Cemetery Path" (exceptions).

### Step 2: Atomic Breakdown
- Split complex flows into atomic test scenarios.
- Define specific preconditions for each scenario.

### Step 3: Document Writing
- Use the template located at `references/template.md`.
- **Preconditions**: Be specific about data and environmental state.
- **Test Steps**: List clear, reproducible steps with expected results.
- **Boundary/Exception**: Specifically document edge cases and invalid state transitions.

### Step 4: Verification
- Check if scenarios are atomic.
- Verify if environment degradation (external API fail) is covered.
- Assess automation potential.

## Document Template
The skill uses the standard template provided in `references/template.md`. Always maintain the structure while filling in the test scenario details.

## Example Atomic Test Case
| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | Attempt to cancel an "Already Completed" reservation | System returns 400/409 error |
| 2 | Verify reservation status remains "Completed" | Status is unchanged |
