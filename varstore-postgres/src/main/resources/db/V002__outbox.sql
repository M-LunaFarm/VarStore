CREATE TABLE vs_subscriptions (
 network_id text COLLATE "C" NOT NULL REFERENCES vs_networks(network_id),
 subscriber_id text COLLATE "C" NOT NULL CHECK(subscriber_id ~ '^[a-z0-9._-]{1,64}$'),
 namespace text COLLATE "C" NOT NULL CHECK(namespace ~ '^[a-z0-9._-]{1,64}$'),
 mode text NOT NULL CHECK(mode IN ('EPHEMERAL','DURABLE')),
 storage_epoch uuid NOT NULL,
 session_token uuid NOT NULL,
 lease_until timestamptz NOT NULL,
 retention_millis bigint NOT NULL CHECK(retention_millis >= 3600000 AND retention_millis <= 2592000000),
 resync_required boolean NOT NULL DEFAULT false,
 active boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(network_id,subscriber_id)
);
CREATE TABLE vs_outbox (
 event_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 network_id text COLLATE "C" NOT NULL,
 namespace text COLLATE "C" NOT NULL,
 scope_kind text COLLATE "C" NOT NULL CHECK(scope_kind IN ('NETWORK','SERVER')),
 scope_id text COLLATE "C" NOT NULL,
 owner_type text COLLATE "C" NOT NULL,
 owner_id text COLLATE "C" NOT NULL,
 variable_key text COLLATE "C" NOT NULL,
 operation_id uuid NOT NULL,
 storage_epoch uuid NOT NULL,
 generation uuid NOT NULL,
 revision bigint NOT NULL CHECK(revision>0),
 kind text NOT NULL CHECK(kind IN ('SET','DELETE')),
 source_server text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(network_id,namespace,operation_id) REFERENCES vs_operations(network_id,namespace,operation_id),
 UNIQUE(network_id,namespace,operation_id,scope_kind,scope_id,owner_type,owner_id,variable_key)
);
CREATE INDEX vs_outbox_retention ON vs_outbox(created_at,event_id);
CREATE TABLE vs_outbox_delivery (
 network_id text COLLATE "C" NOT NULL,
 subscriber_id text COLLATE "C" NOT NULL,
 event_id bigint NOT NULL REFERENCES vs_outbox(event_id) ON DELETE CASCADE,
 state text NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','LEASED','ACKED','DEAD')),
 lease_token uuid,
 lease_until timestamptz,
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0),
 next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 ack_at timestamptz,
 last_error text CHECK(last_error IS NULL OR last_error ~ '^[A-Z0-9_]{1,64}$'),
 PRIMARY KEY(network_id,subscriber_id,event_id),
 FOREIGN KEY(network_id,subscriber_id) REFERENCES vs_subscriptions(network_id,subscriber_id) ON DELETE CASCADE,
 CHECK((state='LEASED' AND lease_token IS NOT NULL AND lease_until IS NOT NULL) OR (state<>'LEASED' AND lease_token IS NULL AND lease_until IS NULL)),
 CHECK((state='ACKED')=(ack_at IS NOT NULL))
);
CREATE INDEX vs_outbox_delivery_claim ON vs_outbox_delivery(network_id,subscriber_id,state,next_attempt_at,event_id);
CREATE INDEX vs_outbox_delivery_expired_lease ON vs_outbox_delivery(lease_until) WHERE state='LEASED';
