CREATE TABLE vs_networks (
 network_id text COLLATE "C" PRIMARY KEY CHECK (network_id ~ '^[a-z0-9._-]{1,64}$'),
 storage_epoch uuid NOT NULL
);
CREATE TABLE vs_variables (
 network_id text COLLATE "C" NOT NULL REFERENCES vs_networks(network_id),
 namespace text COLLATE "C" NOT NULL CHECK (namespace ~ '^[a-z0-9._-]{1,64}$'),
 scope_kind text COLLATE "C" NOT NULL CHECK (scope_kind IN ('NETWORK','SERVER')),
 scope_id text COLLATE "C" NOT NULL,
 owner_type text COLLATE "C" NOT NULL CHECK (owner_type ~ '^[A-Z][A-Z0-9_]{0,31}$'),
 owner_id text COLLATE "C" NOT NULL CHECK (octet_length(owner_id) >= 1 AND octet_length(owner_id) <= 128 AND owner_id !~ '[[:cntrl:]]'),
 variable_key text COLLATE "C" NOT NULL CHECK (variable_key ~ '^[a-z0-9._/-]{1,128}$'),
 value_type text NOT NULL CHECK (value_type IN ('STRING','LONG','BOOLEAN','UUID')),
 string_value text CHECK (octet_length(string_value) <= 16384),
 long_value bigint,
 boolean_value boolean,
 uuid_value uuid,
 generation uuid NOT NULL,
 revision bigint NOT NULL CHECK (revision >= 0),
 deleted boolean NOT NULL,
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 last_writer text NOT NULL CHECK (last_writer ~ '^[a-z0-9._-]{1,64}$'),
 PRIMARY KEY (network_id,namespace,scope_kind,scope_id,owner_type,owner_id,variable_key),
 CHECK ((scope_kind='NETWORK' AND scope_id='_') OR (scope_kind='SERVER' AND scope_id ~ '^[a-z0-9._-]{1,64}$')),
 CHECK ((deleted AND num_nonnulls(string_value,long_value,boolean_value,uuid_value)=0) OR
  (NOT deleted AND num_nonnulls(string_value,long_value,boolean_value,uuid_value)=1 AND
   ((value_type='STRING' AND string_value IS NOT NULL) OR (value_type='LONG' AND long_value IS NOT NULL) OR
    (value_type='BOOLEAN' AND boolean_value IS NOT NULL) OR (value_type='UUID' AND uuid_value IS NOT NULL))))
);
CREATE INDEX vs_variables_owner ON vs_variables(network_id,namespace,owner_type,owner_id,scope_kind,scope_id,variable_key);
CREATE TABLE vs_operations (
 network_id text COLLATE "C" NOT NULL REFERENCES vs_networks(network_id),
 namespace text COLLATE "C" NOT NULL CHECK (namespace ~ '^[a-z0-9._-]{1,64}$'),
 operation_id uuid NOT NULL,
 fingerprint bytea NOT NULL CHECK (octet_length(fingerprint)=32),
 outcome text CHECK (outcome IN ('APPLIED','NO_CHANGE','CONDITION_FAILED')),
 result_payload bytea,
 result_expired boolean NOT NULL DEFAULT false,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 completed_at timestamptz,
 PRIMARY KEY(network_id,namespace,operation_id),
 CHECK ((completed_at IS NULL AND outcome IS NULL AND result_payload IS NULL AND NOT result_expired) OR
        (completed_at IS NOT NULL AND outcome IS NOT NULL AND ((result_expired AND result_payload IS NULL) OR (NOT result_expired AND result_payload IS NOT NULL))))
);
CREATE INDEX vs_operations_retention ON vs_operations(completed_at) WHERE NOT result_expired;
CREATE TABLE vs_admin_audit (
 audit_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 network_id text COLLATE "C" NOT NULL,
 namespace text COLLATE "C" NOT NULL,
 operation_id uuid NOT NULL,
 actor text NOT NULL CHECK (octet_length(actor) BETWEEN 1 AND 256),
 action text NOT NULL CHECK (octet_length(action) BETWEEN 1 AND 64),
 target text NOT NULL,
 outcome text NOT NULL CHECK (outcome IN ('APPLIED','NO_CHANGE','CONDITION_FAILED')),
 before_version text,
 after_version text,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(network_id,namespace,operation_id) REFERENCES vs_operations(network_id,namespace,operation_id)
);
CREATE TABLE vs_schema_history (
 version integer PRIMARY KEY,
 checksum text NOT NULL,
 catalog_checksum text NOT NULL,
 applied_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 tool_version text NOT NULL
);
