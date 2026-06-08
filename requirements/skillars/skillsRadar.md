# Skills Radar

# Player Skill Analytics & Rating System Specification

## Version 1.1 — Serious Football Development Platform

# 

* * *

# 1\. Purpose of the System

# 

The Skill Radar system (Player DNA) is designed to provide:

*   Standardized player evaluations ("Big Tests")
    
*   Consistent scoring across all contributing coaches
    
*   Longitudinal player development tracking (Rating/Ability)
    
*   Objective and evidence-based analytics
    
*   Marketplace trust and transparency
    

The system is NOT intended to be entertainment-based like FIFA ratings.  
It is intended to function as a professional development analytics framework.

* * *

# 2\. Core Principles

# 

The platform MUST adhere to the following principles:

## 2.1 Standardization

# 

All coaches evaluate players using:

*   The same drills
    
*   The same scoring rubrics
    
*   The same weighted formulas
    

* * *

## 2.2 Evidence-Based Scoring

# 

Scores should be generated from:

*   Measurable "Big Tests"
    
*   Match observations
    
*   Video evidence
    
*   Session performance
    

NOT “gut feeling”.

* * *

## 2.3 Cumulative Multi-Coach Evaluation

# 

The Skills Radar is a unified representation of a player's ability. If a player has multiple coaches, all coach entries and assessment results contribute to the same cumulative Skills Radar and Weekly Skill Exposure. There is no concept of a "Primary Coach" for data ownership.

* * *

## 2.4 Coach Normalization

# 

The platform should automatically detect:

*   Lenient coaches
    
*   Strict coaches
    
*   Score inflation
    
*   Score compression
    

and normalize ratings accordingly across the ecosystem.

* * *

# 3\. Overall Rating Architecture

# Final Skill Score Formula

# 

Each skill rating in the "Big Test" is calculated using:

| Component | Weight |
| --- | --- |
| Objective Assessment Results | 50% |
| Match Observation | 30% |
| Coach Technical Evaluation | 20% |

*   MVP Note: Specific automated benchmark drills (e.g., "30-Second Toe Taps") are NOT included in the MVP. Assessment results are based on coach-led drills and observations recorded in the platform.

* * *

# 4\. Universal Scoring Scale

# 

All skills on the Player DNA page use a 1–100 scale.

| Score Range | Interpretation |
| --- | --- |
| 90–100 | Elite |
| 80–89 | Excellent |
| 70–79 | Advanced |
| 60–69 | Intermediate |
| 50–59 | Developing |
| 40–49 | Beginner |
| Below 40 | Very Weak |

* * *

# 5\. Skill Definitions & Measurement Specifications

The Player DNA Radar supports 15 skills. Coaches have full autonomy to select which of these skills they want to regularly test or include in a player's Performance Index PDF.

### PAC — Pace
Acceleration and sprint speed with/without the ball.

### SHO — Shooting
Overall shooting ability (power, technique, accuracy).

### PAS — Passing
Accuracy, timing, and effectiveness of passing.

### DRI — Dribbling
Ability to manipulate and retain the ball while moving.

### PHY — Physicality
Strength, balance, endurance, and physical dominance.

### DEF — Defence
Ability to stop attacks and regain possession.

### WEF — Weak Foot
Effectiveness using non-dominant foot.

### FIN — Finishing
Ability to convert scoring opportunities.

### 1V1 — One-on-Ones
Effectiveness in isolated attacking or defending situations.

### HED — Heading
Accuracy, timing, and effectiveness in aerial play.

### CRO — Crossing
Quality and consistency of wide delivery.

### IBS — In-Box Shooting
Finishing effectiveness inside the penalty area.

### OBS — Out-Box Shooting
Long-range shooting ability.

### FKI — Free Kicks
Effectiveness from dead-ball shooting situations.

### FIT — First Touch
Quality of receiving the ball under various pressures and angles.

* * *

# 12\. Radar Chart Rules (Player DNA)

## Display Rules

# 

*   Radar Capability: Supports all 15 skills on the interactive Player DNA Radar.
    
*   Coach Autonomy: Coaches select which skills to test and display. The radar adjusts its geometry to represent the selected data set.
    
*   Confidence Indicator: Based on assessment frequency and data volume.
    
*   Last Updated: Timestamp of the most recent assessment for each skill.
    

* * *

## Skills Taxonomy (Reference)

*   PAC, SHO, PAS, DRI, PHY, DEF, WEF, FIN, 1V1, HED, CRO, IBS, OBS, FKI, FIT.
