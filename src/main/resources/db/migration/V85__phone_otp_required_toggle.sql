INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at)
VALUES
    (600, 'security.registration.phone-otp-required', 'false', 'STRING',
     'Whether phone OTP verification is required before a Player/Parent/Coach account can log in', NOW())
ON CONFLICT (key) DO NOTHING;
