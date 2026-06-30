ALTER TABLE admin.admin_action_log
    DROP CONSTRAINT IF EXISTS admin_action_log_action_type_check;

ALTER TABLE admin.admin_action_log
    ADD CONSTRAINT admin_action_log_action_type_check
    CHECK (action_type IN (
        'MESSAGE_APPROVE','MESSAGE_BLOCK','CONVERSATION_UNBLOCK',
        'REVIEW_APPROVE','REVIEW_BLOCK',
        'COACH_SUSPEND','COACH_REINSTATE',
        'COACH_STRIKE_ISSUED','COACH_STRIKE_DELETED',
        'DISPUTE_RESOLVE'
    ));
