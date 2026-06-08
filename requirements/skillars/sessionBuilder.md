# Session Builder & Development Intelligence Specification (Revised)

## 1. Product Vision

The Session Builder is a mobile-first coaching workflow system designed to help coaches:

* build structured football training sessions quickly,
* automatically track player development exposure,
* monitor long-term skill progression,
* and visualize training balance across time.

The platform is centered around:

> Development Exposure Tracking rather than simple time logging.

The system automatically estimates:

* skill-specific training load,
* repetition exposure,
* cognitive demand,
* and match realism

based on drill metadata and session completion behavior.

The goal is to eliminate manual administrative work for coaches while still generating meaningful developmental intelligence.

---

# 2. Core Concepts

## 2.1 Skill Load Units (SLU)

The platform uses a normalized internal metric called:

# Skill Load Units (SLU)

SLUs represent estimated developmental exposure for a specific skill.

SLUs are automatically generated from:

* drill duration,
* skill weighting,
* repetition density,
* intensity,
* pressure level,
* and realism modifiers.

Coaches are never required to manually count repetitions.

---

## 2.2 Development Exposure

The platform measures:

* consistency,
* training balance,
* skill prioritization,
* and developmental trends.

The system does NOT attempt to measure:

* “talent,”
* player ratings,
* or overall football ability.

---

# 3. Drill Library Architecture

## 3.1 Foundational Content Packs

The platform launches with 20 professionally curated drill clips grouped into:

| Pack              | Focus                                         |
| ----------------- | --------------------------------------------- |
| The Master Touch  | Ball mastery and close control                |
| The Sniper        | Ball striking and weak-foot development       |
| The Escape Artist | 1v1 dominance and deceptive turns             |
| The Wall          | Receiving on the move and directional touches |

---

## 3.2 Categorization by Development Intent

Drills must be tagged by:

* player weakness,
* technical objective,
* and developmental outcome.

Examples:

* Scanning
* First touch under pressure
* Weak foot finishing
* Receiving across body
* Explosive first step

The system avoids generic-only categories like:

* “Passing”
* “Dribbling”

unless used as secondary tags.

---

## 3.3 Skill Taxonomy

### Core Skills

| Code | Skill            |
| ---- | ---------------- |
| PAC  | Pace             |
| SHO  | Shooting         |
| PAS  | Passing          |
| DRI  | Dribbling        |
| PHY  | Physicality      |
| DEF  | Defending        |
| WEF  | Weak Foot        |
| FIN  | Finishing        |
| 1V1  | One-on-Ones      |
| HED  | Heading          |
| CRO  | Crossing         |
| IBS  | In-Box Shooting  |
| OBS  | Out-Box Shooting |
| FKI  | Free Kicks       |
| FIT  | First Touch      |

The taxonomy must remain extensible.

---

# 4. Drill Intelligence Layer

## 4.1 Hidden Drill Metadata

Each drill contains invisible metadata used for automatic development tracking (SLU).

### Required Metadata

| Field                  | Description                     |
| ---------------------- | ------------------------------- |
| Primary Skills         | Main developmental outcomes     |
| Secondary Skills       | Supporting outcomes             |
| Skill Weighting        | Relative contribution per skill |
| Rep Density            | Estimated reps per minute       |
| Intensity              | Physical load (1–5)             |
| Pressure Level         | Opposition/time pressure (1–5)  |
| Cognitive Load         | Decision complexity (1–5)       |
| Match Realism          | Match transfer similarity (1–5) |
| Weak Foot Bias         | Left / Right / Both / None      |
| Difficulty Tier        | U8 → Pro                        |
| Equipment Required     | Cones, rebounders, goals, etc   |
| Recommended Group Size | Solo / Pair / Group             |

---

## 4.2 Example Drill Metadata

```json id="l8x3pw"
{
  "title": "Weak Foot Wall Passing",
  "skills": {
    "PAS": {
      "weight": 0.6,
      "rep_density": 18
    },
    "WEF": {
      "weight": 0.4,
      "rep_density": 18
    }
  },
  "intensity": 2,
  "pressure": 1,
  "cognitive_load": 2,
  "match_realism": 1,
  "weak_foot_bias": "left"
}
```

---

# 5. Drill Card UI (Mobile First)

Each drill is represented by a mobile-optimized card containing:

## Required Components

### 15-Second Infinite Loop

* silent autoplay clip,
* optimized for quick coach demonstrations.

### Coaching Points

3–4 concise bullet points.

Example:

* Lock the ankle
* Receive across body
* Scan before first touch

### Setup Diagram

Static pitch/grid illustration.

### Equipment List

Automatically generated equipment requirements.

### Development Summary

Quick visual indicators:

* Skills trained
* Intensity
* Match realism
* Weak foot focus

### Expected Exposure

System-generated estimate (SLU):

* “Estimated 120 weak-foot contacts in 8 mins.”

---

# 6. Session Builder Architecture

## 6.1 Session Framework

Sessions use a guided block structure.

| Block                | Focus                 | Recommended Duration |
| -------------------- | --------------------- | -------------------- |
| Warm-Up              | Activation & mobility | 10 mins              |
| Technical Foundation | Technical repetition  | 15 mins              |
| Game Intensity       | Competitive realism   | 25 mins              |
| Cool Down & Review   | Reflection & recovery | 10 mins              |

Blocks remain customizable.

---

## 6.2 Session Goals Layer

Each session begins with:

# Development Focus Selection

Example:

* Weak Foot
* Finishing Under Pressure
* First Touch
* 1v1 Confidence

Session recommendations adapt dynamically based on selected focus areas.

---

## 6.3 Intelligent Drill Suggestions

The system recommends drills based on:

* selected development goals,
* neglected skills,
* recent training load (SLU),
* age group,
* difficulty level,
* and equipment availability.

---

## 6.4 Session DNA Analysis

As drills are added, the system generates a live “Session DNA” summary.

### Example Dimensions

| Dimension       | Score |
| --------------- | ----- |
| Technical       | 82    |
| Physical        | 44    |
| Cognitive       | 67    |
| Match Realism   | 71    |
| Weak Foot Focus | 90    |

This helps coaches balance session design instantly.

---

# 7. Automatic Skill Load Tracking

## 7.1 SLU Generation

When a session is completed, the platform automatically generates SLUs. SLUs measure training volume and exposure, not player ability.

SLUs are calculated from:

* duration,
* drill weighting,
* rep density,
* intensity,
* and pressure modifiers.

The calculation remains internal to the platform.

---

## 7.2 Example Skill Load Output

```json id="0w9qyt"
{
  "PAS": 42.6,
  "WEF": 28.2,
  "FIN": 11.8
}
```

---

# 8. Session Completion Workflows

## 8.1 Live Session Mode

Optional full-session workflow for coaches who want active guidance.

### Features

* live drill timer,
* haptic transition alerts,
* current drill playback,
* one-handed operation,
* outdoor readability optimization.

---

## 8.2 Quick Complete Mode

For coaches who do not use Live Session Mode.

Workflow:

1. Coach taps:

   * “Session Completed”
2. Parent/player receives confirmation request.
3. Session is marked consumed.

No detailed live interaction required.

---

# 9. Parent & Player Confirmation

## 9.1 Session Verification

Players/parents confirm:

* attendance,
* session completion,
* optional effort feedback.

Example:

* Easy
* Good
* Hard

---

## 9.2 Homework Workflow

Coaches may assign 1–2 drills as homework.

Players only access:

* drills specifically assigned to them.

Players cannot browse the full library.

---

# 10. Development Intelligence Dashboard

## 10.1 Weekly Skill Exposure (SLU)

Players and coaches can view cumulative training volume:

* total SLU per skill,
* trends over time,
* neglected areas,
* and target completion progress.

Note: SLUs represent effort/volume, not ability.

---

## 10.2 Target Exposure System

Coaches may define weekly targets.

Example:

| Skill | Target SLU |
| ----- | ---------- |
| WEF   | 80         |
| FIN   | 60         |
| PAS   | 40         |

Progress updates automatically as sessions complete.

---

## 10.3 Neglected Skill Detection

The system proactively highlights undertrained areas.

Example insights:

* “Defensive exposure decreased 38% this week.”
* “Weak foot training has not reached target load in 10 days.”

---

## 10.4 Exposure Trend Charts

Charts display:

* SLU accumulation,
* weekly consistency,
* and long-term development balance.

Supported visualizations:

* radar charts (for volume),
* line graphs,
* stacked weekly bars,
* rolling averages.

---

# 11. The "Big Test" & Skills Radar

## 11.1 Periodic Assessments

Coaches periodically evaluate player ability through standardized "Big Tests." Results are entered into the Skills Radar using the 1–100 universal scoring scale.

Example:

* Passing: 78
* Weak Foot: 45
* Finishing: 82

## 11.2 Multi-Coach Integration

If a player has multiple coaches, all assessment entries from all coaches contribute cumulatively to the same Skills Radar (Rating/Ability) and Weekly Skill Exposure (SLU/Volume).

---

## 11.3 Development Correlation Engine

The system correlates:

* accumulated SLU (Volume)
  with
* assessment improvements (Ability).

Example insight:

* “Weak foot rating improved by 5 points after 210 WEF-SLU over 5 weeks.”

---

# 12. Master Templates & Deployment

## 12.1 Master Template Vault

Coaches can save reusable session structures.

---

## 12.2 Player-Specific Deployment

Templates may be deployed to individual players.

Coaches may customize:

* duration,
* notes,
* focus areas,
* and homework

without affecting the original template.

---

# 13. User Generated Content (UGC)

## 13.1 Custom Drill Uploads

Coaches can:

* upload custom drills,
* add private coaching notes,
* and clone platform drills into personal libraries.

---

## 13.2 Storage Constraints

Custom drill upload limits are tied to subscription tier.

Videos are transcoded to:

* maximum 720p.

---

# 14. Technical Optimization

## 14.1 Video Delivery

Use HLS streaming to support:

*   fast playback,
*   frame scrubbing,
*   low bandwidth usage,
*   and slow-motion review.

---

## 14.2 Timezone & Scheduling

The platform utilizes a **Pitch-First** timezone policy:

*   Storage: All timestamps are saved in **UTC**.
*   Display: Sessions are rendered in the **Pitch Timezone** by default to maintain "Wall Time" consistency for outdoor training.
*   Travel: System detects browser/profile timezone mismatches and alerts the user.

---

## 14.3 Mobile Optimization

The platform must prioritize:

*   outdoor visibility,
*   one-handed interaction,
*   large tap targets,
*   and low cognitive friction.


---

# 15. Future Expansion Layer

The architecture should support future:

* AI session recommendations,
* automated workload balancing,
* development forecasting,
* and personalized growth insights.

Example future insight:

* “Player responds best to high-pressure finishing drills after 150 FIN-SLU exposure.”

