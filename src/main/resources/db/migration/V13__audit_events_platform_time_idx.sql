-- The platform-admin overview (PlatformAdminService) queries audit_events across ALL tenants
-- ordered by occurred_at, unlike every other query against this table (tenant-scoped and
-- already served by audit_events_tenant_time_idx). Without this, that query has no index
-- whose leading column is occurred_at alone to use.
CREATE INDEX audit_events_time_idx ON audit_events (occurred_at DESC);
