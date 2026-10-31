#!/usr/bin/env python3
"""Investigate why the timeline UNION doesn't show process events."""
import pgserver, json

PGDATA = "/home/z/my-project/.pgdata"
srv = pgserver.get_server(PGDATA, cleanup_mode=None)
srv.psql("alter user postgres password 'hydra';")

MID = "54459c38-954d-4942-bc52-53bfcfe42b68"

def q(sql, *args):
    return srv.psql(sql, *args)

print("=" * 70)
print("MOVEMENT ROW:")
print(q(f"select id, status, process_instance_id, claimed_amount, assessed_amount, "
       f"variance_reason from cash_movement where id = '{MID}';"))

print("=" * 70)
print("PROCESS INSTANCE rows whose subject_id = this movement:")
print(q(f"select id, template_id, status, subject_type, subject_id, created_at "
       f"from process_instance where subject_id = '{MID}';"))

print("=" * 70)
print("event_log rows for this movement (entity_type='cash_movement'):")
print(q(f"select action, created_at, user_id, "
       f"substring(details::text,1,80) as details "
       f"from event_log where entity_type = 'cash_movement' and entity_id = '{MID}' "
       f"order by created_at;"))

print("=" * 70)
print("event_log rows for process_instance entities linked to this movement:")
print(q(f"""
    select el.action, el.entity_type, el.entity_id, el.created_at
    from event_log el
    join process_instance pi on pi.id = el.entity_id
    where el.entity_type = 'process_instance'
      and pi.subject_type = 'CASH_MOVEMENT'
      and pi.subject_id = '{MID}'
    order by el.created_at;
"""))

print("=" * 70)
print("event_log rows for process_step_instance entities whose process_instance.subject_id = this movement:")
print(q(f"""
    select el.action, el.entity_type, el.entity_id, psi.process_instance_id, psi.id as step_id
    from event_log el
    join process_step_instance psi on psi.id = el.entity_id
    join process_instance pi on pi.id = psi.process_instance_id
    where el.entity_type = 'process_step_instance'
      and pi.subject_type = 'CASH_MOVEMENT'
      and pi.subject_id = '{MID}'
    order by el.created_at;
"""))

print("=" * 70)
print("All event_log rows mentioning process_instance whose subject_id = this movement:")
print(q(f"""
    select el.action, el.entity_type, el.entity_id, el.created_at
    from event_log el
    where el.entity_id in (
        select id from process_instance where subject_id = '{MID}'
        union
        select psi.id from process_step_instance psi
        join process_instance pi on pi.id = psi.process_instance_id
        where pi.subject_id = '{MID}'
    )
    order by el.created_at;
"""))

print("=" * 70)
print("RUN THE EXACT UNION query the backend uses:")
print(q(f"""
    select el.action, el.created_at, el.user_id, u.full_name, el.details,
           'cash_movement' as source
    from event_log el
    left join app_user u on u.id = el.user_id
    where el.entity_type = 'cash_movement' and el.entity_id = '{MID}'
    union all
    select el.action, el.created_at, el.user_id, u.full_name, el.details,
           'process_instance' as source
    from event_log el
    left join app_user u on u.id = el.user_id
    join process_instance pi on pi.id = el.entity_id
    where el.entity_type = 'process_instance'
      and pi.subject_type = 'CASH_MOVEMENT'
      and pi.subject_id = '{MID}'
    union all
    select el.action, el.created_at, el.user_id, u.full_name, el.details,
           'process_step' as source
    from event_log el
    left join app_user u on u.id = el.user_id
    join process_step_instance psi on psi.id = el.entity_id
    join process_instance pi on pi.id = psi.process_instance_id
    where el.entity_type = 'process_step_instance'
      and pi.subject_type = 'CASH_MOVEMENT'
      and pi.subject_id = '{MID}'
    order by created_at;
"""))
