# Parent Player Portal

#   

The Parent and Player Portal is designed as the "sticky" core of the platform, transforming the coaching service from an hourly transaction into a longitudinal development history. While the coach uses the app for business operations, parents and players use the portal to track growth and training investment.

### 1\. Session Management and Scheduling

# 

*   Credit/Pack Dashboard: A visual progress bar displays the status of "Session Packs". Credits are deducted when a session reaches the "COMPLETED" state.
*   Upcoming Sessions View: Displays training slots specifically for the player.
    *   **Timezone Policy:** By default, sessions are shown in the **Pitch Timezone** (the local time of the training field) to prevent confusion. Parents may toggle to "My Current Time" if traveling.
*   One-Tap RSVP/Rescheduling: Allows parents (or players of to suggest new times directly through the app.

### 2\. Feedback and Skills Attribute Radar (Player DNA)

# 

The platform uses gamified visualization to motivate players and provide parents with professional-grade analysis.

*   Weekly Skill Exposure (SLU): This page exposes total Skill Load Units (SLU) per skill, trends over time, and target completion progress. It tracks training volume and consistency across the 15 core skills. This is cumulative across all coaches training the player.
    
*   The "Big Test" (Skills Radar): The interactive Player DNA Radar displays results from standardized periodic assessments. It uses the 1–100 universal scoring scale (Rating/Ability) for all 15 skills:
    *   PAC: Pace
    *   SHO: Shooting
    *   PAS: Passing
    *   DRI: Dribbling
    *   PHY: Physicality
    *   DEF: Defence
    *   WEF: Weak Foot
    *   FIN: Finishing
    *   1V1: One-on-ones
    *   HED: Heading
    *   CRO: Crossing
    *   IBS: In-box-shooting
    *   OBS: Out-box-shooting
    *   FKI: Free-kicks
    *   FIT: First Touch
    
*   Multi-Coach Cumulative Data: Every coach connected to the player contributes to the same unified Skills Radar and Weekly Skill Exposure dashboard.
    
*   Baseline Comparison: The portal tracks growth over time, comparing current stats against the "Baseline Session".

### 3\. Objective Data (MVP Constraints)

# 

*   MVP Note: Specific automated benchmark tests (e.g., "30-Second Toe Taps" or "6-cone Slalom") are NOT included in the MVP.
*   Measured Growth: The portal tracks growth based on the "Big Test" assessments conducted by coaches using the 1–100 universal scoring scale.

### 4\. Video Management and Parental Governance

# 

*   Parental Approval Workflow: For players who are minors, any video loaded by any coach must be approved by a parent before it becomes visible to the player.
*   Upload Restrictions: Minors are prohibited from uploading performance videos.
*   Quota Usage Tracking: Real-time UI showing Bandwidth and Upload Quota.
*   Self-Service Quota Management: Users can delete older videos to free up space.
*   Auto-Archive Policy: Performance videos are subject to an auto-archive policy after 30 days.

### 5\. The "Locker Room" and Player Timeline

# 

*   The Locker Room (Homework): Players view 15-second "pro-demo" loops of drills assigned by their coaches.
*   Access Gatekeeping: Players only see drills specifically tagged for them. Access terminates when paid sessions are exhausted.
*   Unified Timeline: Every session summary, homework submission, and coach review clip from all coaches is saved to a single vertical scroll.

### 6\. Account Architecture: "Shadow Accounts"

# 

*   Parent/Player Relationship: Maximum of one parent account per player.
*   Shadow Account Management: A single parent login can manage multiple player profiles.
*   Messaging Transparency: All messages between players and coaches are visible to parents.

### 7\. Progression and Reporting

# 

*   Performance Index PDFs: Parents can download professional PDF reports. These reports are coach-driven:
    *   Skill Selection: The PDF will contain only the skills that the coach chooses to include for that specific player/report.
    *   Attribute Radar: Displays the 1-100 scores for the selected skills.
    *   Development Plan: Includes the coach's qualitative "Next Step" summary.
    
*   Cumulative Effort Tracking: The portal displays the total hours and SLU a player has dedicated to specific skills both weekly and cumulatively across all coaches.

---

# Performance Index PDF Specification

To generate a professional Performance Index PDF, the report is structured to provide a high-level visual summary and granular technical data based on coach selection.

### 1\. Report Header & Branding

# 

*   Player & Coach Information: Fields for the Player Name, Date of Report, Coach Name, and the specific focus of the review.
*   Coach Branding: The PDF pulls the Coach’s Logo and brand colors.

### 2\. Performance Radar (Player DNA)

# 

This is the primary visual at the top of the page, representing the player's proficiency across the skills selected by the coach:

*   Selected Skills: The coach chooses which of the 15 core skills (PAC, SHO, PAS, DRI, PHY, DEF, WEF, FIN, 1V1, HED, CRO, IBS, OBS, FKI, FIT) to include in the report.
*   Coach’s Summary: A qualitative text field where the coach provides a high-level assessment.

### 3\. Assessment Data

# 

This section tracks growth by comparing the "Baseline" (the player's first-ever assessment) against the "Current" performance in the selected skills.

*   Universal Scale: All results use the 1–100 universal scoring scale.
*   Improvement Percentage: An automated calculation showing the growth curve.
