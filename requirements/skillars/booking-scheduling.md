# 

This module extends the Session entity defined in requirements-v1\_2.md.

This specification defines the functional and technical requirements for the Smart Scheduling, RSVP, and Booking System, integrating the core marketplace request/approval workflow with strict session-consumption rules to prevent platform abuse.

### 1\. Booking Eligibility & Credit Enforcement

# 

To ensure the platform’s financial integrity and prevent "gaming the system," booking is strictly tied to a user’s available session credits.

*   Hard Booking Block: A parent or player is not allowed to initiate a booking request if their session credits are fully consumed.
    
*   Credit/Pack Dashboard: The Parent View must feature a visual "Session Pack" tracker (e.g., "7/10 Sessions Used") to provide transparency before they attempt to book.
    
*   Activation Logic: Premium coaching features (such as video uploads or reports) are only activated for a player while they have an active, unexhausted paid session pack.
    
*   Deduction Point: Critically, a session is deducted from the player's pack ONLY when its state becomes COMPLETED, which requires coach submission and the absence of a dispute.
    

### 2\. Request/Approval Booking Workflow

# 

The platform utilizes a multi-step workflow to manage expectations between independent trainers and busy parents.

1.  Request Submission: The parent/player selects an available slot from the coach’s calendar. The system validates session credit availability before allowing the submission.
    
2.  Coach Review: The coach receives a notification and can Approve or Decline the request.
    
3.  Payment/Confirmation: If approved, the payment is processed (or credit is locked), and the state transitions to CONFIRMED.
    
4.  Rescheduling (One-Tap RSVP): Instead of messy text threads, parents can use a "Request Change" button. They suggest a new time, and the coach can "Accept" to automatically update the schedule.
    

### 3\. Smart Scheduling & Calendar Integration

# 

The scheduling module acts as a virtual assistant for "solopreneur" coaches to reduce administrative overhead.

*   Automated Sync: The system must support syncing with Google and Apple Calendars to handle external commitments and prevent double-booking.
    
*   Availability Management: Coaches define Availability Windows and can manually block out time for personal training or travel.
    
*   Automated Reminders: To reduce "no-shows," the system sends automated notifications to parents and players at configurable intervals (e.g., 24 hours and 2 hours before the session).
    
*   Session Duplication: Coaches can use a "Repeat for next week" feature to quickly schedule recurring sessions for long-term clients.
    

### 4\. RSVP & Attendance Tracking (The Session Lifecycle)

# 

The "RSVP" is treated as a high-fidelity audit trail rather than a simple calendar event to prevent fraud.

*   Explicit Start Session: The coach must tap \[Start Session\] within the app when the player arrives. This creates a timestamp and changes the state to IN\_PROGRESS.
    
*   Audit Trail: This explicit action prevents coaches from charging for forgotten bookings and provides evidence in case of a dispute.
    
*   No-Show Policy Enforcement:
    

*   Player No-Show: If a player does not arrive, the session is automatically marked as COMPLETED after 15 minutes of the scheduled start, and the session is deducted from their pack.
    
*   Coach No-Show: If the coach fails to start the session, the parent receives an automatic refund, and the coach receives a "reliability strike".
    

### 5\. Technical Implementation of States

# 

To support these requirements, the backend must track the following Booking States as a central entity:

| State | Description |
| --- | --- |
| REQUESTED | Initial submission by parent; credit checked but not locked. |
| APPROVED | Coach accepts; session is reserved. |
| UPCOMING | Transitioned 24h before start; reminders sent. |
| IN_PROGRESS | Coach has tapped [Start Session] on the pitch. |
| COMPLETED | Only at this stage is the session pack decremented. |
| CANCELLED | Subject to platform-wide refund windows (e.g., <6h = no refund). |
| DISPUTED | Parent flags a no-show or issue; session deduction is frozen for admin review. |

### 6\. User Experience for Scheduling

# 

The scheduling UI must be optimized for the specific needs of each user role.

*   Coach View: A "Command Center" managing all 20+ clients, highlighting gaps in the schedule and total projected revenue.
    
*   Parent View: A simplified view showing only their child's specific 1-hour slot and remaining credits.
    
*   One-Handed Operation: The "Start/End" and "Wrap-up" workflow must be completed in under 30 seconds to ensure it remains usable in outdoor training conditions.
    

This system ensures that coaches spend less time on spreadsheets and more time on the pitch, while parents have clear, data-backed evidence of their child’s attendance and progress.

Some coaches may not want to use the Live session feature and may just want to mark a drill as done. The parent/player will still need to confirm that the session has been consumed.
