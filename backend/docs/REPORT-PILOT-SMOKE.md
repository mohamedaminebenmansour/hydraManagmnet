# HYDRA — Pilot Smoke Test Report

> **Script:** `scripts/qa-pilot-smoke.sh`
> **Result:** ✅ **8/8 PASS**

## Results

| # | Scenario | Status | Evidence |
|---|---|---|---|
| S1 | BC confirm → BL post → stock balance increases | ✅ PASS | balance 781 → 861 (Δ=80) |
| S2 | Facture validate → match status MATCHED | ✅ PASS | status=MATCHED (ordered=80, received=80, stock=80, invoiced=80) |
| S3 | Payment submit WITH bcId (MATCHED) → warnings empty | ✅ PASS | warnings=0 |
| S4 | Payment submit WITH bcId (no BL) → warnings + detail | ✅ PASS | submit=1 warning, detail=1 materialWarning |
| S5 | TRANSFER internal (no facture) → 200 | ✅ PASS | move created |
| S6 | Payment submit → process instance created | ✅ PASS | processInstanceId set |
| S7 | IDOR cross-project → FORBIDDEN | ✅ PASS | karim (P9) → P1 BC create = FORBIDDEN |
| S8 | Doc folder BL + FACTURE resolves | ✅ PASS | BL=05.01 BL, FACTURE=05.03 FACTURE |

## How to re-run

```bash
# 1. Start services
cd /tmp/my-project/.pgdata && pg_ctl start ...
cd /tmp/my-project/backend && java -jar target/hydra-0.0.1-SNAPSHOT.jar &

# 2. Run smoke
bash /home/z/my-project/scripts/qa-pilot-smoke.sh
# → PASS=8 FAIL=0
```

## Business paths verified

1. **Procurement chain**: BC (commande) → BL (réception) → stock IN → Facture → MATCHED
2. **Payment with match**: bcId linked, all MATCHED → no warnings (clean payment)
3. **Payment without match**: bcId linked, no BL → warning "aucune réception (BL) enregistrée" on both submit response AND movement detail
4. **Internal transfer**: TRANSFER central → site without any commercial link → works
5. **Process instance**: payment submit creates a PAYMENT_APPROVAL workflow instance
6. **IDOR**: cross-project access blocked at service layer
7. **Document folders**: BL → 05.01 BL, FACTURE → 05.03 FACTURE (auto-resolved)
