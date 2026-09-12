CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS accounts (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_name TEXT NOT NULL,
  phone TEXT NOT NULL DEFAULT '',
  email TEXT NOT NULL DEFAULT '',
  password_hash TEXT NOT NULL DEFAULT '',
  recovery_code_hash TEXT NOT NULL DEFAULT '',
  recovery_code_set_at TIMESTAMPTZ,
  status TEXT NOT NULL DEFAULT 'pending',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE accounts ADD COLUMN IF NOT EXISTS recovery_code_hash TEXT NOT NULL DEFAULT '';
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS recovery_code_set_at TIMESTAMPTZ;

CREATE UNIQUE INDEX IF NOT EXISTS accounts_email_unique_idx
  ON accounts (lower(email))
  WHERE email <> '';

CREATE UNIQUE INDEX IF NOT EXISTS accounts_phone_unique_idx
  ON accounts (phone)
  WHERE phone <> '';

CREATE TABLE IF NOT EXISTS restaurants (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  account_id UUID REFERENCES accounts(id) ON DELETE SET NULL,
  name TEXT NOT NULL,
  owner_name TEXT NOT NULL DEFAULT '',
  phone TEXT NOT NULL DEFAULT '',
  email TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL DEFAULT 'approved',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE restaurants ADD COLUMN IF NOT EXISTS account_id UUID REFERENCES accounts(id) ON DELETE SET NULL;
ALTER TABLE restaurants ADD COLUMN IF NOT EXISTS owner_name TEXT NOT NULL DEFAULT '';
ALTER TABLE restaurants ADD COLUMN IF NOT EXISTS phone TEXT NOT NULL DEFAULT '';
ALTER TABLE restaurants ADD COLUMN IF NOT EXISTS email TEXT NOT NULL DEFAULT '';
ALTER TABLE restaurants ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'approved';

CREATE INDEX IF NOT EXISTS restaurants_account_id_idx ON restaurants(account_id);
CREATE INDEX IF NOT EXISTS restaurants_status_idx ON restaurants(status);

CREATE TABLE IF NOT EXISTS devices (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  restaurant_id UUID NOT NULL REFERENCES restaurants(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  platform TEXT NOT NULL DEFAULT 'windows' CHECK (platform IN ('windows', 'android')),
  api_key_hash TEXT NOT NULL,
  active BOOLEAN NOT NULL DEFAULT true,
  last_seen_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE devices ADD COLUMN IF NOT EXISTS platform TEXT NOT NULL DEFAULT 'windows';
ALTER TABLE devices DROP CONSTRAINT IF EXISTS devices_platform_check;
ALTER TABLE devices ADD CONSTRAINT devices_platform_check CHECK (platform IN ('windows', 'android'));

CREATE INDEX IF NOT EXISTS devices_restaurant_id_idx ON devices(restaurant_id);
CREATE INDEX IF NOT EXISTS devices_restaurant_platform_idx ON devices(restaurant_id, platform, active);

CREATE TABLE IF NOT EXISTS subscriptions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  restaurant_id UUID NOT NULL REFERENCES restaurants(id) ON DELETE CASCADE,
  plan_name TEXT NOT NULL DEFAULT 'Monthly',
  status TEXT NOT NULL DEFAULT 'active',
  starts_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL,
  max_devices INTEGER NOT NULL DEFAULT 999999,
  max_users INTEGER,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS max_users INTEGER;
ALTER TABLE subscriptions DROP CONSTRAINT IF EXISTS subscriptions_max_users_check;
ALTER TABLE subscriptions ADD CONSTRAINT subscriptions_max_users_check
  CHECK (max_users IS NULL OR max_users >= 1);

CREATE INDEX IF NOT EXISTS subscriptions_restaurant_status_idx
  ON subscriptions(restaurant_id, status, expires_at);

INSERT INTO subscriptions (restaurant_id, plan_name, status, starts_at, expires_at, max_devices)
SELECT r.id, 'Legacy', 'active', now(), now() + interval '365 days', 999999
FROM restaurants r
WHERE r.status = 'approved'
  AND NOT EXISTS (
    SELECT 1 FROM subscriptions s WHERE s.restaurant_id = r.id
  );

CREATE TABLE IF NOT EXISTS pairing_codes (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  restaurant_id UUID NOT NULL REFERENCES restaurants(id) ON DELETE CASCADE,
  code_hash TEXT NOT NULL,
  device_name TEXT NOT NULL DEFAULT 'POS Counter',
  platform TEXT NOT NULL DEFAULT 'windows' CHECK (platform IN ('windows', 'android')),
  expires_at TIMESTAMPTZ NOT NULL,
  used_at TIMESTAMPTZ,
  used_by_device UUID REFERENCES devices(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE pairing_codes ADD COLUMN IF NOT EXISTS platform TEXT NOT NULL DEFAULT 'windows';

CREATE INDEX IF NOT EXISTS pairing_codes_code_hash_idx ON pairing_codes(code_hash);
CREATE INDEX IF NOT EXISTS pairing_codes_restaurant_idx ON pairing_codes(restaurant_id, expires_at);

CREATE TABLE IF NOT EXISTS cloud_kv (
  restaurant_id UUID NOT NULL REFERENCES restaurants(id) ON DELETE CASCADE,
  key TEXT NOT NULL,
  value JSONB NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by_device UUID REFERENCES devices(id) ON DELETE SET NULL,
  PRIMARY KEY (restaurant_id, key)
);

CREATE TABLE IF NOT EXISTS sync_events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  client_event_id TEXT NOT NULL,
  restaurant_id UUID NOT NULL REFERENCES restaurants(id) ON DELETE CASCADE,
  device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  entity_type TEXT NOT NULL,
  entity_id TEXT NOT NULL,
  operation TEXT NOT NULL,
  payload JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (restaurant_id, device_id, client_event_id)
);

CREATE INDEX IF NOT EXISTS sync_events_restaurant_created_idx ON sync_events(restaurant_id, created_at);

-- Customer support / contact messages submitted from the public Support page.
CREATE TABLE IF NOT EXISTS support_messages (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name TEXT NOT NULL DEFAULT '',
  phone TEXT NOT NULL DEFAULT '',
  email TEXT NOT NULL DEFAULT '',
  message TEXT NOT NULL DEFAULT '',
  source TEXT NOT NULL DEFAULT 'support',
  status TEXT NOT NULL DEFAULT 'new',
  admin_note TEXT NOT NULL DEFAULT '',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE support_messages ADD COLUMN IF NOT EXISTS source TEXT NOT NULL DEFAULT 'support';
ALTER TABLE support_messages ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'new';
ALTER TABLE support_messages ADD COLUMN IF NOT EXISTS admin_note TEXT NOT NULL DEFAULT '';

CREATE INDEX IF NOT EXISTS support_messages_status_idx ON support_messages(status, created_at DESC);

-- Staff / user accounts managed by the customer from the Client Portal Users page.
CREATE TABLE IF NOT EXISTS portal_staff (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  restaurant_id UUID NOT NULL REFERENCES restaurants(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  mobile TEXT NOT NULL DEFAULT '',
  role TEXT NOT NULL DEFAULT 'Staff',
  staff_code TEXT NOT NULL DEFAULT '',
  permissions JSONB NOT NULL DEFAULT '[]'::jsonb,
  table_access JSONB NOT NULL DEFAULT '[]'::jsonb,
  password_hash TEXT NOT NULL DEFAULT '',
  active BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE portal_staff ADD COLUMN IF NOT EXISTS staff_code TEXT NOT NULL DEFAULT '';
ALTER TABLE portal_staff ADD COLUMN IF NOT EXISTS permissions JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE portal_staff ADD COLUMN IF NOT EXISTS table_access JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE INDEX IF NOT EXISTS portal_staff_restaurant_idx ON portal_staff(restaurant_id, active);
