---
name: ts-skill
description: This skill should be used when the user wants to "write test scenarios", "create test cases", "plan testing", or "verify functionality". Make sure to use this skill whenever the user is moving from System Design (SD) to the verification phase, or when they need to define how a feature should be validated across UI, Database, and Performance metrics. It follows expert testing principles including atomic testing, state machine validation, and multi-dimensional contract testing.
---

# TS Skill: Expert Test Scenario Design and Documentation

This skill enables Claude to design high-fidelity Test Scenarios (TS) that ensure system reliability, stability, and performance. It shifts the focus from simple UI verification to a comprehensive, multi-dimensional validation strategy following the project's standardized template.

## Professional Testing Mindsets

Apply these expert-level thinking dimensions during the TS design process:

### 1. Gherkin Logic (Given / When / Then)
- **Mindset**: Define the background context and the expected contract clearly.
- **Given**: Specify exact data states, system load, or environmental conditions.
- **When**: Describe the trigger event (user action, API call, system event).
- **Then**: Define the expected behavior, both functional and technical.

### 2. Multi-dimensional Verification
- **Mindset**: A "passed" test isn't just about the UI showing a success message.
- **Functional**: Verify the business flow and UI presentation.
- **Technical (NFR)**: Verify the **Database State** (idempotency, atomic updates), **Log Traces** (Correlation-ID, error codes), and **Performance Metrics** (Latency, Status Codes).

### 3. Edge Case & Destructive Testing
- **Concurrency Traps**: Always consider what happens when multiple users update the same resource simultaneously (validate Optimistic Locking).
- **Data Pollution**: Test against SQL Injection, XSS, or invalid payloads.
- **Resource Limits**: Consider behavior when cloud resources (GCP Cloud Run) reach auto-scaling limits or when dependent services are offline.

### 4. Atomic Testing (Minimal Path)
- **One TS, One Goal**: Each scenario verifies exactly one distinct logic or boundary.
- **Benefit**: Provides unambiguous root cause analysis when tests fail.

## Workflow

### Step 1: Input Analysis
- Read the **SA (PRD)** to understand business requirements and NFRs.
- Read the **SD (System Design)** to understand the Database Schema, API Contracts, and sequence flows.

### Step 2: Scenario Identification
- Identify "Happy Paths" and critical "Cemetery Paths" (exceptions).
- Focus on high-risk areas: payments, state transitions, and high-concurrency endpoints.

### Step 3: Document Writing
- Copy the template from `references/template.md`.
- Fill in the details using the Gherkin logic and the multi-dimensional verification table.
- Ensure all technical verification points (DB/Log) are specific and derived from the SD.

### Step 4: Verification & Refinement
- Check if the scenarios are truly atomic.
- Ensure data cleanup steps are included if the test modifies the environment.

## Example: Professional Test Scenario

### TS-001: Duplicate Booking Prevention (Concurrency)

| Item | Content |
| :--- | :--- |
| **Corresponding Req** | PRD-102 (Booking Service) / NFR-005 (Concurrency) |
| **Test Type** | ⬜ Functional / ✅ Performance & Resilience |
| **Auto Status** | ⬜ Not Automated |

#### I. Test Logic Definition
* **Given**: User A has a valid session and is on the final checkout page. The system is under moderate load.
* **When**: User A double-clicks the "Pay Now" button within 200ms.
* **Then**: Only one booking is created in the DB. The second request returns a 429 or 409 error with a "Processing" message.

#### II. Verification Table
| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | Click "Pay Now" | Redirect to success page | API 201 Created / DB: `bookings` has 1 record |
| 2 | Rapid Click (2nd) | Show "Order in progress" | API 409 Conflict / **Idempotency**: No duplicate record |

#### III. Edge Cases
- **Optimistic Lock**: Verify the `version` field in the DB increments correctly and prevents the second write.
- **Network Timeout**: Simulate a 504 Gateway Timeout during the first request and verify if the retry logic triggers the idempotency check.
