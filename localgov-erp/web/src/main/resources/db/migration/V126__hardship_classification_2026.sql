-- =====================================================================
-- V126 — Hardship classification per council, 2026
-- =====================================================================
-- One compliance_rule row per council whose hardship classification
-- was decided in the 2026 exercise. THREE classifications only:
--
--   NONE    -- 0% hardship allowance (explicitly classified as zero)
--   RURAL   -- 20% of basic
--   REMOTE  -- 25% of basic
--
-- Councils marked PENDING in the source xlsx get NO row. Their
-- classification is not yet decided; the payroll engine treats "no
-- row" as "0% hardship, pending classification" — same immediate
-- outcome as NONE, but distinguishable in the data (auditors can see
-- "23 classified NONE" vs "53 pending").
--
-- Source: Hardship Allowance.xlsx (Ministry of Local Government and
-- Rural Development), plus the newly added councils in the 2026
-- ZULAWU and FIRESUZ circulars.
--
-- Row counts:
--   NONE:    23 councils
--   RURAL:   17 councils
--   REMOTE:  13 councils
--   Pending: 63 councils (no row in this migration)
--   Total classified: 53
--
-- NOTE on LUSAKA_CITY: the current authorities table (V99) uses
-- 'LUSAKA_CITY'. This migration uses that exact value so that the
-- row is valid against the current registry. V127 renames LUSAKA_CITY
-- to LUSAKA and must cascade to this table's authority_code in the
-- same transaction.
-- =====================================================================

BEGIN;

INSERT INTO compliance_rule (rule_code, scope, authority_code, effective_from, parameters, source, notes)
VALUES

-- =====================================================================
-- NONE (0% hardship) — 23 councils
-- =====================================================================
('HARDSHIP_CLASSIFICATION','TENANT','KABWE',        DATE '2026-01-01',
 '{"classification":"NONE","allowance_pct":0.00}'::jsonb,
 'Hardship Allowance 2026','Kabwe Municipal Council — no hardship allowance'),
('HARDSHIP_CLASSIFICATION','TENANT','CHILILABOMBWE',DATE '2026-01-01',
 '{"classification":"NONE","allowance_pct":0.00}'::jsonb,
 'Hardship Allowance 2026','Chililabombwe Municipal Council — no hardship allowance'),
('HARDSHIP_CLASSIFICATION','TENANT','CHINGOLA',     DATE '2026-01-01',
 '{"classification":"NONE","allowance_pct":0.00}'::jsonb,
 'Hardship Allowance 2026','Chingola Municipal Council — no hardship allowance'),
('HARDSHIP_CLASSIFICATION','TENANT','KALULUSHI',    DATE '2026-01-01',
 '{"classification":"NONE","allowance_pct":0.00}'::jsonb,
 'Hardship Allowance 2026','Kalulushi Municipal Council — no hardship allowance'),
('HARDSHIP_CLASSIFICATION','TENANT','KITWE',        DATE '2026-01-01',
 '{"classification":"NONE","allowance_pct":0.00}'::jsonb,
 'Hardship Allowance 2026','Kitwe City Council — no hardship allowance'),
('HARDSHIP_CLASSIFICATION','TENANT','LUANSHYA',     DATE '2026-01-01',
 '{"classification":"NONE","allowance_pct":0.00}'::jsonb,
 'Hardship Allowance 2026','Luanshya Municipal Council — no hardship allowance'),
('HARDSHIP_CLASSIFICATION','TENANT','MUFULIRA',     DATE '2026-01-01',
 '{"classification":"NONE","allowance_pct":0.00}'::jsonb,
 'Hardship Allowance 2026','Mufulira Municipal Council — no hardship allowance'),
('HARDSHIP_CLASSIFICATION','TENANT','NDOLA',        DATE '2026-01-01',
 '{"classification":"NONE","allowance_pct":0.00}'::jsonb,
 'Hardship Allowance 2026','Ndola City Council — no hardship allowance'),
('HARDSHIP_CLASSIFICATION','TENANT','LUNDAZI',      DATE '2026-01-01',
 '{"classification":"NONE","allowance_pct":0.00}'::jsonb,
 'Hardship Allowance 2026','Lundazi Town Council — no hardship allowance'),
('HARDSHIP_CLASSIFICATION','TENANT','PETAUKE',      DATE '2026-01-01',
 '{"classification":"NONE","allowance_pct":0.00}'::jsonb,
 'Hardship Allowance 2026','Petauke Town Council — no hardship allowance'),
('HARDSHIP_CLASSIFICATION','TENANT','MANSA',        DATE '2026-01-01',
 '{"classification":"NONE","allowance_pct":0.00}'::jsonb,
 'Hardship Allowance 2026','Mansa Municipal Council — no hardship allowance'),
('HARDSHIP_CLASSIFICATION','TENANT','SAMFYA',       DATE '2026-01-01',
 '{"classification":"NONE","allowance_pct":0.00}'::jsonb,
 'Hardship Allowance 2026','Samfya Town Council — no hardship allowance'),
('HARDSHIP_CLASSIFICATION','TENANT','CHILANGA',     DATE '2026-01-01',
 '{"classification":"NONE","allowance_pct":0.00}'::jsonb,
 'Hardship Allowance 2026','Chilanga Town Council — no hardship allowance'),
('HARDSHIP_CLASSIFICATION','TENANT','CHONGWE',      DATE '2026-01-01',
 '{"classification":"NONE","allowance_pct":0.00}'::jsonb,
 'Hardship Allowance 2026','Chongwe Municipal Council — no hardship allowance'),
('HARDSHIP_CLASSIFICATION','TENANT','KAFUE',        DATE '2026-01-01',
 '{"classification":"NONE","allowance_pct":0.00}'::jsonb,
 'Hardship Allowance 2026','Kafue Town Council — no hardship allowance'),
('HARDSHIP_CLASSIFICATION','TENANT','LUSAKA_CITY',  DATE '2026-01-01',
 '{"classification":"NONE","allowance_pct":0.00}'::jsonb,
 'Hardship Allowance 2026','Lusaka City Council — no hardship allowance (V127 will rename to LUSAKA)'),
('HARDSHIP_CLASSIFICATION','TENANT','KASAMA',       DATE '2026-01-01',
 '{"classification":"NONE","allowance_pct":0.00}'::jsonb,
 'Hardship Allowance 2026','Kasama Municipal Council — no hardship allowance'),
('HARDSHIP_CLASSIFICATION','TENANT','SOLWEZI',      DATE '2026-01-01',
 '{"classification":"NONE","allowance_pct":0.00}'::jsonb,
 'Hardship Allowance 2026','Solwezi Municipal Council — no hardship allowance'),
('HARDSHIP_CLASSIFICATION','TENANT','CHINSALI',     DATE '2026-01-01',
 '{"classification":"NONE","allowance_pct":0.00}'::jsonb,
 'Hardship Allowance 2026','Chinsali Municipal Council — no hardship allowance'),
('HARDSHIP_CLASSIFICATION','TENANT','CHOMA',        DATE '2026-01-01',
 '{"classification":"NONE","allowance_pct":0.00}'::jsonb,
 'Hardship Allowance 2026','Choma Municipal Council — no hardship allowance'),
('HARDSHIP_CLASSIFICATION','TENANT','LIVINGSTONE',  DATE '2026-01-01',
 '{"classification":"NONE","allowance_pct":0.00}'::jsonb,
 'Hardship Allowance 2026','Livingstone City Council — no hardship allowance'),
('HARDSHIP_CLASSIFICATION','TENANT','MAZABUKA',     DATE '2026-01-01',
 '{"classification":"NONE","allowance_pct":0.00}'::jsonb,
 'Hardship Allowance 2026','Mazabuka Municipal Council — no hardship allowance'),
('HARDSHIP_CLASSIFICATION','TENANT','MONGU',        DATE '2026-01-01',
 '{"classification":"NONE","allowance_pct":0.00}'::jsonb,
 'Hardship Allowance 2026','Mongu Municipal Council — no hardship allowance'),

-- =====================================================================
-- RURAL (20% hardship) — 17 councils
-- =====================================================================
('HARDSHIP_CLASSIFICATION','TENANT','CHIBOMBO',     DATE '2026-01-01',
 '{"classification":"RURAL","allowance_pct":0.20}'::jsonb,
 'Hardship Allowance 2026','Chibombo Town Council — 20% rural hardship'),
('HARDSHIP_CLASSIFICATION','TENANT','LUFWANYAMA',   DATE '2026-01-01',
 '{"classification":"RURAL","allowance_pct":0.20}'::jsonb,
 'Hardship Allowance 2026','Lufwanyama Town Council — 20% rural hardship'),
('HARDSHIP_CLASSIFICATION','TENANT','MASAITI',      DATE '2026-01-01',
 '{"classification":"RURAL","allowance_pct":0.20}'::jsonb,
 'Hardship Allowance 2026','Masaiti Town Council — 20% rural hardship'),
('HARDSHIP_CLASSIFICATION','TENANT','MPONGWE',      DATE '2026-01-01',
 '{"classification":"RURAL","allowance_pct":0.20}'::jsonb,
 'Hardship Allowance 2026','Mpongwe Town Council — 20% rural hardship'),
('HARDSHIP_CLASSIFICATION','TENANT','CHIPANGALI',   DATE '2026-01-01',
 '{"classification":"RURAL","allowance_pct":0.20}'::jsonb,
 'Hardship Allowance 2026','Chipangali Town Council — 20% rural hardship'),
('HARDSHIP_CLASSIFICATION','TENANT','SINDA',        DATE '2026-01-01',
 '{"classification":"RURAL","allowance_pct":0.20}'::jsonb,
 'Hardship Allowance 2026','Sinda Town Council — 20% rural hardship'),
('HARDSHIP_CLASSIFICATION','TENANT','CHIFUNABULI',  DATE '2026-01-01',
 '{"classification":"RURAL","allowance_pct":0.20}'::jsonb,
 'Hardship Allowance 2026','Chifunabuli Town Council — 20% rural hardship'),
('HARDSHIP_CLASSIFICATION','TENANT','MWENSE',       DATE '2026-01-01',
 '{"classification":"RURAL","allowance_pct":0.20}'::jsonb,
 'Hardship Allowance 2026','Mwense Town Council — 20% rural hardship'),
('HARDSHIP_CLASSIFICATION','TENANT','NCHELENGE',    DATE '2026-01-01',
 '{"classification":"RURAL","allowance_pct":0.20}'::jsonb,
 'Hardship Allowance 2026','Nchelenge Town Council — 20% rural hardship'),
('HARDSHIP_CLASSIFICATION','TENANT','LUANGWA',      DATE '2026-01-01',
 '{"classification":"RURAL","allowance_pct":0.20}'::jsonb,
 'Hardship Allowance 2026','Luangwa Town Council — 20% rural hardship'),
('HARDSHIP_CLASSIFICATION','TENANT','RUFUNSA',      DATE '2026-01-01',
 '{"classification":"RURAL","allowance_pct":0.20}'::jsonb,
 'Hardship Allowance 2026','Rufunsa Town Council — 20% rural hardship'),
('HARDSHIP_CLASSIFICATION','TENANT','ZAMBEZI',      DATE '2026-01-01',
 '{"classification":"RURAL","allowance_pct":0.20}'::jsonb,
 'Hardship Allowance 2026','Zambezi Town Council — 20% rural hardship'),
('HARDSHIP_CLASSIFICATION','TENANT','NAKONDE',      DATE '2026-01-01',
 '{"classification":"RURAL","allowance_pct":0.20}'::jsonb,
 'Hardship Allowance 2026','Nakonde Town Council — 20% rural hardship'),
('HARDSHIP_CLASSIFICATION','TENANT','GWEMBE',       DATE '2026-01-01',
 '{"classification":"RURAL","allowance_pct":0.20}'::jsonb,
 'Hardship Allowance 2026','Gwembe Town Council — 20% rural hardship'),
('HARDSHIP_CLASSIFICATION','TENANT','ITEZHI_TEZHI', DATE '2026-01-01',
 '{"classification":"RURAL","allowance_pct":0.20}'::jsonb,
 'Hardship Allowance 2026','Itezhi Tezhi Town Council — 20% rural hardship'),
('HARDSHIP_CLASSIFICATION','TENANT','NAMWALA',      DATE '2026-01-01',
 '{"classification":"RURAL","allowance_pct":0.20}'::jsonb,
 'Hardship Allowance 2026','Namwala Town Council — 20% rural hardship'),
('HARDSHIP_CLASSIFICATION','TENANT','PEMBA',        DATE '2026-01-01',
 '{"classification":"RURAL","allowance_pct":0.20}'::jsonb,
 'Hardship Allowance 2026','Pemba Town Council — 20% rural hardship'),

-- =====================================================================
-- REMOTE (25% hardship) — 13 councils
-- =====================================================================
('HARDSHIP_CLASSIFICATION','TENANT','LUANO',        DATE '2026-01-01',
 '{"classification":"REMOTE","allowance_pct":0.25}'::jsonb,
 'Hardship Allowance 2026','Luano Town Council — 25% remote hardship'),
('HARDSHIP_CLASSIFICATION','TENANT','VUBWI',        DATE '2026-01-01',
 '{"classification":"REMOTE","allowance_pct":0.25}'::jsonb,
 'Hardship Allowance 2026','Vubwi Town Council — 25% remote hardship'),
('HARDSHIP_CLASSIFICATION','TENANT','CHIENGI',      DATE '2026-01-01',
 '{"classification":"REMOTE","allowance_pct":0.25}'::jsonb,
 'Hardship Allowance 2026','Chiengi Town Council — 25% remote hardship'),
('HARDSHIP_CLASSIFICATION','TENANT','MILENGE',      DATE '2026-01-01',
 '{"classification":"REMOTE","allowance_pct":0.25}'::jsonb,
 'Hardship Allowance 2026','Milenge Town Council — 25% remote hardship'),
('HARDSHIP_CLASSIFICATION','TENANT','CHILUBI',      DATE '2026-01-01',
 '{"classification":"REMOTE","allowance_pct":0.25}'::jsonb,
 'Hardship Allowance 2026','Chilubi Town Council — 25% remote hardship'),
('HARDSHIP_CLASSIFICATION','TENANT','KAPUTA',       DATE '2026-01-01',
 '{"classification":"REMOTE","allowance_pct":0.25}'::jsonb,
 'Hardship Allowance 2026','Kaputa Town Council — 25% remote hardship'),
('HARDSHIP_CLASSIFICATION','TENANT','NSAMA',        DATE '2026-01-01',
 '{"classification":"REMOTE","allowance_pct":0.25}'::jsonb,
 'Hardship Allowance 2026','Nsama Town Council — 25% remote hardship'),
('HARDSHIP_CLASSIFICATION','TENANT','IKELENGE',     DATE '2026-01-01',
 '{"classification":"REMOTE","allowance_pct":0.25}'::jsonb,
 'Hardship Allowance 2026','Ikelenge Town Council — 25% remote hardship'),
('HARDSHIP_CLASSIFICATION','TENANT','MAFINGA',      DATE '2026-01-01',
 '{"classification":"REMOTE","allowance_pct":0.25}'::jsonb,
 'Hardship Allowance 2026','Mafinga Town Council — 25% remote hardship'),
('HARDSHIP_CLASSIFICATION','TENANT','MITETE',       DATE '2026-01-01',
 '{"classification":"REMOTE","allowance_pct":0.25}'::jsonb,
 'Hardship Allowance 2026','Mitete Town Council — 25% remote hardship'),
('HARDSHIP_CLASSIFICATION','TENANT','MULOBEZI',     DATE '2026-01-01',
 '{"classification":"REMOTE","allowance_pct":0.25}'::jsonb,
 'Hardship Allowance 2026','Mulobezi Town Council — 25% remote hardship'),
('HARDSHIP_CLASSIFICATION','TENANT','SHANGOMBO',    DATE '2026-01-01',
 '{"classification":"REMOTE","allowance_pct":0.25}'::jsonb,
 'Hardship Allowance 2026','Shangombo Town Council — 25% remote hardship'),
('HARDSHIP_CLASSIFICATION','TENANT','SIKONGO',      DATE '2026-01-01',
 '{"classification":"REMOTE","allowance_pct":0.25}'::jsonb,
 'Hardship Allowance 2026','Sikongo Town Council — 25% remote hardship')

ON CONFLICT (rule_code, scope, authority_code, effective_from, rule_version) DO NOTHING;

-- ---------------------------------------------------------------------
-- Verification notice
-- ---------------------------------------------------------------------
DO $$
DECLARE
    v_none   INTEGER;
    v_rural  INTEGER;
    v_remote INTEGER;
    v_total  INTEGER;
BEGIN
    SELECT COUNT(*) FILTER (WHERE parameters->>'classification' = 'NONE'),
           COUNT(*) FILTER (WHERE parameters->>'classification' = 'RURAL'),
           COUNT(*) FILTER (WHERE parameters->>'classification' = 'REMOTE'),
           COUNT(*)
    INTO v_none, v_rural, v_remote, v_total
    FROM compliance_rule
    WHERE rule_code = 'HARDSHIP_CLASSIFICATION'
      AND effective_from = DATE '2026-01-01';

    RAISE NOTICE 'V126 hardship classification: NONE=%, RURAL=%, REMOTE=%, TOTAL=% (expected: 23/17/13/53)',
        v_none, v_rural, v_remote, v_total;
END $$;

-- ---------------------------------------------------------------------
-- Audit chain entry
-- ---------------------------------------------------------------------
SELECT log_audit_event(
    p_event_type := 'SYSTEM',
    p_entity_type := 'PLATFORM',
    p_entity_id := NULL,
    p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'hardship_classification_2026',
        'migration', 'V126',
        'effective_from', '2026-01-01',
        'counts', jsonb_build_object(
            'NONE',   23,
            'RURAL',  17,
            'REMOTE', 13,
            'total_classified', 53,
            'pending', 63
        ),
        'note', 'Pending councils get no row; classification to be added in future migrations as decisions are made.',
        'lusaka_note', 'Row uses LUSAKA_CITY (current DB value). V127 renames to LUSAKA and cascades to this table.'
    ),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM',
    p_user_role := 'SYSTEM',
    p_source_module := 'MIGRATION'
);

COMMIT;
