ALTER TABLE marketplace.coach_profiles
    DROP CONSTRAINT chk_coach_profile_status,
    ADD CONSTRAINT chk_coach_profile_status
        CHECK (status IN ('DRAFT', 'ACTIVE', 'REDUCED', 'PENDING_REVIEW', 'SUSPENDED', 'DEACTIVATED'));
