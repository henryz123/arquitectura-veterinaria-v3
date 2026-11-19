-- B06-B2: narrow the app role to the exact read-side audit projection and
-- the tenant-local fields needed to determine active STAFF recipients.
--
-- Forward-only, additive in behavior and privilege-reducing for audit_entry.
-- No SECURITY DEFINER, dynamic SQL, RLS bypass, PUBLIC grant or data mutation.

-- V033 granted a table-wide SELECT solely to compose the protected audit
-- projection. Retain only the fields returned by that projection; in
-- particular, free-text reason remains unavailable to av_app.
revoke all on table tenant_admin.audit_entry from av_app;
grant select (
    id,
    tenant_id,
    actor_principal_id,
    action_code,
    resource_type,
    resource_id,
    outcome,
    created_at
) on table tenant_admin.audit_entry to av_app;

-- Internal notifications need to find active, unrevoked STAFF members in the
-- current tenant. These column grants deliberately omit e-mail, password and
-- identity metadata. Existing tenant RLS policies remain the row authority.
revoke all on table identity_auth.account_profile from av_app;
revoke all on table identity_auth.role_assignment from av_app;
grant select (principal_id, tenant_id, account_type, status)
    on table identity_auth.account_profile to av_app;
grant select (tenant_id, staff_principal_id, role_code, revoked_at)
    on table identity_auth.role_assignment to av_app;
