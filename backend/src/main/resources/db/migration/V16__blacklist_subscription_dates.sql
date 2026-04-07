-- Add plan_code to subscription_plans
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS plan_code VARCHAR(20) UNIQUE;

-- Seed/update plan codes matching frontend constants (FREE, STANDARD, PRO, PREMIUM)
-- Update existing plans by name, then insert FREE
UPDATE subscription_plans SET plan_code = 'STANDARD' WHERE LOWER(name) = 'standard';
UPDATE subscription_plans SET plan_code = 'PRO'      WHERE LOWER(name) = 'pro';
UPDATE subscription_plans SET plan_code = 'PREMIUM'  WHERE LOWER(name) = 'premium';

-- Insert FREE plan if not present (no monthly_price = 0 row yet)
INSERT INTO subscription_plans (id, name, plan_code, order_limit, monthly_price, yearly_price, is_active, created_at, updated_at)
SELECT gen_random_uuid(), '免費版', 'FREE', 3, 0.00, 0.00, TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM subscription_plans WHERE plan_code = 'FREE');

-- Update prices to match frontend (STANDARD 499, PRO 899, PREMIUM 1299)
UPDATE subscription_plans SET monthly_price=499, yearly_price=4990, order_limit=20 WHERE plan_code='STANDARD';
UPDATE subscription_plans SET monthly_price=899, yearly_price=8990, order_limit=999 WHERE plan_code='PRO';
UPDATE subscription_plans SET monthly_price=1299, yearly_price=12990, order_limit=999 WHERE plan_code='PREMIUM';

-- Make plan_code NOT NULL after update
ALTER TABLE subscription_plans ALTER COLUMN plan_code SET NOT NULL;

-- Add effective_date to services table
ALTER TABLE services ADD COLUMN IF NOT EXISTS effective_date DATE;

-- Create blacklist table
CREATE TABLE IF NOT EXISTS sitter_client_blacklists (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sitter_profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    client_profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    reason TEXT,
    created_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(sitter_profile_id, client_profile_id)
);
CREATE INDEX IF NOT EXISTS idx_blacklists_sitter ON sitter_client_blacklists(sitter_profile_id);

-- Seed smoke-test sitter's active subscription (PRO plan)
INSERT INTO sitter_subscriptions (sitter_profile_id, plan_id, start_date, end_date, status)
SELECT
    'efefefef-0000-0000-0000-000000000011',
    sp.id,
    CURRENT_DATE,
    CURRENT_DATE + INTERVAL '30 days',
    'ACTIVE'
FROM subscription_plans sp
WHERE sp.plan_code = 'PRO'
  AND EXISTS (SELECT 1 FROM profiles WHERE id = 'efefefef-0000-0000-0000-000000000011')
  AND NOT EXISTS (
    SELECT 1 FROM sitter_subscriptions
    WHERE sitter_profile_id = 'efefefef-0000-0000-0000-000000000011'
  );
