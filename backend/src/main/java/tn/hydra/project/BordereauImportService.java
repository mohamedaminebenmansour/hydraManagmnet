package tn.hydra.project;

import org.apache.poi.ss.usermodel.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tn.hydra.audit.EventLogService;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/**
 * BordereauImportService — imports a bordereau from an Excel .xls/.xlsx file.
 *
 * <p>Implements every rule from docs/BORDEREAU-ANALYSIS.md:
 * <ul>
 *   <li>§2.2 — fourniture/pose pairing (second pass, exact match on materiau+de_mm+pn_bar).</li>
 *   <li>§2.3 — article_no is free text, not unique.</li>
 *   <li>§2.4 — normalise units (ML, M3, M2, U, KG, ENS, FORFAIT, LOT).</li>
 *   <li>§6 — never reject a file; show warnings per line.</li>
 *   <li>§7.3 — extract specs from designation.</li>
 *   <li>§7.4 — suggest ouvrage per line.</li>
 * </ul>
 *
 * <p>Multi-sheet: reads every sheet that has a header row. The sheet name
 * becomes the first element of section_path. Sheets without a header row
 * (e.g. "Résultat attendu") are skipped.
 *
 * <p>Accepts .xls (HSSF) and .xlsx (XSSF) via {@link WorkbookFactory}.
 */
@Service
public class BordereauImportService {

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final ProjectService projects;
    private final EventLogService audit;

    public BordereauImportService(JdbcTemplate jdbc, AppUserRepository users,
                                  ProjectService projects, EventLogService audit) {
        this.jdbc = jdbc; this.users = users; this.projects = projects; this.audit = audit;
    }

    // ═══════════════════════════════════════════════════════════════
    // RESPONSE SHAPES
    // ═══════════════════════════════════════════════════════════════

    public record ImportPreview(UUID versionId, int versionNo, String label,
                                int lineCount, int warningCount, int pairCount,
                                List<PreviewLine> lines) {}

    public record PreviewLine(String[] sectionPath, String articleNo, int sortOrder,
                             String designation, String unitRaw, String unit,
                             BigDecimal quantity, BigDecimal unitPrice, BigDecimal montant,
                             UUID suggestedOuvrageId, String suggestedOuvrageType,
                             String workNature, UUID pairedLineId,
                             Map<String, Object> specs, List<String> warnings) {}

    public record CreateVersionRequest(String label, String reason, UUID documentId) {}

    // ═══════════════════════════════════════════════════════════════
    // IMPORT
    // ═══════════════════════════════════════════════════════════════

    @Transactional
    public ImportPreview importExcel(Authentication auth, UUID projectId, MultipartFile file) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        projects.requireManageableForOuvrage(auth, me, projectId);

        if (file == null || file.isEmpty())
            throw new BusinessRuleException("FILE_REQUIRED", "Un fichier Excel est requis.");
        String filename = file.getOriginalFilename();
        if (filename == null || (!filename.toLowerCase().endsWith(".xlsx") && !filename.toLowerCase().endsWith(".xls")))
            throw new BusinessRuleException("INVALID_FORMAT",
                    "Le fichier doit être au format .xls ou .xlsx (Excel).");

        // Parse with WorkbookFactory (handles both .xls and .xlsx).
        List<ParsedSheet> sheets;
        try {
            sheets = parseWorkbook(file.getBytes());
        } catch (IOException e) {
            throw new BusinessRuleException("FILE_UNREADABLE", "Fichier illisible : " + e.getMessage());
        }

        if (sheets.isEmpty())
            throw new BusinessRuleException("NO_HEADER_FOUND",
                    "Aucune feuille avec une ligne d'en-tête n'a été trouvée.");

        // Find next version_no.
        Integer maxVersionNo = jdbc.queryForObject(
                "select coalesce(max(version_no), 0) from bordereau_version where org_id = ? and project_id = ?",
                Integer.class, me.getOrgId(), projectId);
        int newVersionNo = (maxVersionNo != null ? maxVersionNo : 0) + 1;
        String label = "Importé (" + (filename != null ? filename : "bordereau") + ")";

        // Create DRAFT version.
        UUID versionId = jdbc.queryForObject("""
                insert into bordereau_version (org_id, project_id, version_no, label, status, created_by)
                values (?, ?, ?, ?, 'DRAFT', ?)
                returning id
                """, UUID.class, me.getOrgId(), projectId, newVersionNo, label, me.getId());

        // Load ouvrages for suggestion.
        List<Map<String, Object>> ouvrages = jdbc.queryForList(
                "select id, type, name, characteristics::text from ouvrage where org_id = ? and project_id = ? order by sort_order, name",
                me.getOrgId(), projectId);

        // Track for duplicate detection + pairing.
        Set<String> seenArticleKeys = new HashSet<>();
        List<InsertedLine> allInserted = new ArrayList<>();
        List<InsertedLine> fournitureLines = new ArrayList<>();

        int sortOrder = 0;
        int totalWarnings = 0;

        for (ParsedSheet sheet : sheets) {
            for (ParsedRow row : sheet.rows) {
                sortOrder++;
                List<String> warnings = new ArrayList<>();

                // §6 rule 8: skip TOTAL/SOUS-TOTAL/TVA/MONTANT/ARRÊTÉ rows.
                if (isTotalRow(row.designation)) {
                    sortOrder--; // don't count skipped rows
                    continue;
                }

                // §6 rule 2: section title (designation only, no unit, no qty).
                boolean isSectionTitle = (row.designation != null && !row.designation.isBlank())
                        && (row.unitRaw == null || row.unitRaw.isBlank())
                        && (row.quantity == null)
                        && (row.unitPrice == null);
                if (isSectionTitle) {
                    warnings.add("Titre de section (pas de prix).");
                }

                // §6 rule 3: unit but no quantity → quantity 0 + warning.
                if (!isSectionTitle && row.unitRaw != null && !row.unitRaw.isBlank() && row.quantity == null) {
                    warnings.add("Quantité manquante (considérée comme 0).");
                }

                // §6 rule 5: unknown unit → keep raw, set unit null, warn.
                String normalisedUnit = normaliseUnit(row.unitRaw);
                if (row.unitRaw != null && !row.unitRaw.isBlank() && normalisedUnit == null) {
                    warnings.add("Unité inconnue : « " + row.unitRaw + " ».");
                }

                // §6 rule 4: duplicate article_no in same section.
                if (row.articleNo != null && !row.articleNo.isBlank()) {
                    String articleKey = sectionKey(row.sectionPath) + "|" + row.articleNo.trim();
                    if (seenArticleKeys.contains(articleKey)) {
                        warnings.add("Numéro d'article dupliqué dans cette section : « " + row.articleNo + " ».");
                    } else {
                        seenArticleKeys.add(articleKey);
                    }
                }

                // §6: quantity not a number.
                if (row.quantityParseFailed) {
                    warnings.add("La quantité n'est pas un nombre valide : « " + row.quantityRaw + " ».");
                }

                // §7.3: extract specs.
                Map<String, Object> specs = extractSpecs(row.designation);
                // Also check section path for capacite_m3.
                String sectionText = String.join(" ", row.sectionPath);
                Map<String, Object> sectionSpecs = extractSpecs(sectionText);
                sectionSpecs.forEach(specs::putIfAbsent);

                // §7.4: suggest ouvrage.
                OuvrageSuggestion suggestion = suggestOuvrage(row, ouvrages);

                // Work nature.
                String workNature = inferWorkNature(row.designation, row.sectionPath);

                // Montant.
                BigDecimal montant = null;
                if (row.quantity != null && row.unitPrice != null) {
                    montant = row.quantity.multiply(row.unitPrice);
                }

                // Insert line.
                UUID lineId = UUID.randomUUID();
                jdbc.update("""
                        insert into bordereau_line (id, org_id, version_id, section_path, article_no,
                            sort_order, designation, unit_raw, unit, quantity, unit_price,
                            ouvrage_id, work_nature, paired_line_id, specs, import_warnings)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                        """,
                        lineId, me.getOrgId(), versionId,
                        row.sectionPath.toArray(new String[0]),
                        row.articleNo, sortOrder,
                        row.designation,
                        row.unitRaw, normalisedUnit,
                        row.quantity, row.unitPrice,
                        suggestion.ouvrageId,
                        workNature,
                        null,
                        specsToJson(specs),
                        warnings.toArray(new String[0]));

                InsertedLine il = new InsertedLine(lineId, row, normalisedUnit, specs, suggestion, workNature, warnings, montant);
                allInserted.add(il);
                if (isFourniture(row.designation)) {
                    fournitureLines.add(il);
                }

                totalWarnings += warnings.size();
            }
        }

        // §2.2: fourniture/pose pairing — second pass.
        int pairCount = 0;
        for (InsertedLine fourniture : fournitureLines) {
            // Find pose lines with matching materiau + de_mm + pn_bar.
            String fMateriau = (String) fourniture.specs.get("materiau");
            Object fDe = fourniture.specs.get("de_mm");
            Object fPn = fourniture.specs.get("pn_bar");
            if (fMateriau == null || fDe == null || fPn == null) continue;

            List<InsertedLine> matches = new ArrayList<>();
            for (InsertedLine candidate : allInserted) {
                if (!isPose(candidate.row.designation)) continue;
                String cMateriau = (String) candidate.specs.get("materiau");
                Object cDe = candidate.specs.get("de_mm");
                Object cPn = candidate.specs.get("pn_bar");
                if (fMateriau.equals(cMateriau) && String.valueOf(fDe).equals(String.valueOf(cDe))
                        && String.valueOf(fPn).equals(String.valueOf(cPn))) {
                    matches.add(candidate);
                }
            }

            if (matches.size() == 1) {
                // Exactly one match — link them.
                UUID poseLineId = matches.get(0).lineId;
                jdbc.update("update bordereau_line set paired_line_id = ? where id = ? and org_id = ?",
                        poseLineId, fourniture.lineId, me.getOrgId());
                fourniture.pairedLineId = poseLineId;
                pairCount++;
            } else if (matches.size() > 1) {
                fourniture.warnings.add("Plusieurs lignes de pose correspondent (matériau + DE + PN). Aucun lien créé.");
            } else if (matches.isEmpty()) {
                fourniture.warnings.add("Aucune ligne de pose correspondante trouvée.");
            }
        }

        audit.log("BORDEREAU_IMPORTED", "bordereau_version", versionId,
                "{\"projectId\":\"" + projectId + "\",\"lines\":" + sortOrder
                        + ",\"warnings\":" + totalWarnings + ",\"pairs\":" + pairCount + "}");

        // Build preview lines.
        List<PreviewLine> previewLines = new ArrayList<>();
        for (InsertedLine il : allInserted) {
            previewLines.add(new PreviewLine(
                    il.row.sectionPath.toArray(new String[0]),
                    il.row.articleNo, il.sortOrder,
                    il.row.designation, il.row.unitRaw, il.normalisedUnit,
                    il.row.quantity, il.row.unitPrice, il.montant,
                    il.suggestion.ouvrageId, il.suggestion.ouvrageType,
                    il.workNature, il.pairedLineId,
                    il.specs, il.warnings));
        }

        return new ImportPreview(versionId, newVersionNo, label, sortOrder, totalWarnings, pairCount, previewLines);
    }

    // ═══════════════════════════════════════════════════════════════
    // EXCEL PARSING (multi-sheet, WorkbookFactory)
    // ═══════════════════════════════════════════════════════════════

    private static List<ParsedSheet> parseWorkbook(byte[] bytes) throws IOException {
        Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes));
        List<ParsedSheet> sheets = new ArrayList<>();
        FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();

        for (int s = 0; s < wb.getNumberOfSheets(); s++) {
            Sheet sheet = wb.getSheetAt(s);
            String sheetName = sheet.getSheetName();

            // Detect header row.
            int headerRowIdx = -1;
            int colNo = -1, colDesignation = -1, colUnit = -1, colQty = -1, colPrice = -1;

            for (int r = 0; r < Math.min(sheet.getLastRowNum() + 1, 50); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                int matches = 0;
                int foundNo = -1, foundDesig = -1, foundUnit = -1, foundQty = -1, foundPrice = -1;
                for (int c = 0; c < row.getLastCellNum(); c++) {
                    Cell cell = row.getCell(c);
                    String val = getCellAsString(cell, evaluator);
                    if (val == null) continue;
                    String lower = val.toLowerCase().trim();
                    if (foundNo < 0 && (lower.contains("n°") || lower.contains("n° prix") || lower.contains("numéro")
                            || lower.contains("article") || lower.equals("n") || lower.equals("n°")
                            || lower.equals("n° prix") || lower.equals("no") || lower.equals("art.")
                            || lower.equals("prix n°"))) {
                        foundNo = c; matches++;
                    }
                    if (foundDesig < 0 && (lower.contains("désignation") || lower.contains("designation")
                            || lower.contains("libellé") || lower.contains("description")
                            || lower.contains("désignation des ouvrages"))) {
                        foundDesig = c; matches++;
                    }
                    if (foundUnit < 0 && (lower.contains("unité") || lower.contains("unite")
                            || lower.equals("unit") || lower.equals("unité") || lower.equals("u") || lower.equals("u.m.")
                            || lower.equals("u."))) {
                        foundUnit = c; matches++;
                    }
                    if (foundQty < 0 && (lower.contains("quantité") || lower.contains("quantite")
                            || lower.contains("qty") || lower.contains("qte") || lower.contains("qté")
                            || lower.equals("q"))) {
                        foundQty = c; matches++;
                    }
                    if (foundPrice < 0 && (lower.contains("prix") || lower.contains("unitaire")
                            || lower.contains("p.u") || lower.equals("pu"))) {
                        foundPrice = c; matches++;
                    }
                }
                // Only accept the header if we found at least 2 DISTINCT columns
                // and at least one is colNo or colDesignation. This prevents
                // false matches on sheets like "Résultat attendu" where a
                // data row happens to contain "quantité" and "non" (→ "no").
                Set<Integer> distinctCols = new HashSet<>();
                if (foundNo >= 0) distinctCols.add(foundNo);
                if (foundDesig >= 0) distinctCols.add(foundDesig);
                if (foundUnit >= 0) distinctCols.add(foundUnit);
                if (foundQty >= 0) distinctCols.add(foundQty);
                if (foundPrice >= 0) distinctCols.add(foundPrice);
                if (distinctCols.size() >= 2 && (foundDesig >= 0 || foundNo >= 0)) {
                    headerRowIdx = r;
                    colNo = foundNo; colDesignation = foundDesig; colUnit = foundUnit;
                    colQty = foundQty;
                    // "N° Prix" matches both colNo (contains "n°") and colPrice
                    // (contains "prix"). If they collided on the same column,
                    // colPrice is wrong — search for the real price column.
                    colPrice = foundPrice;
                    if (colPrice >= 0 && colPrice == colNo) {
                        colPrice = -1;
                        // Re-scan this row for a price column that's NOT colNo.
                        Row recheckRow = sheet.getRow(r);
                        for (int c = 0; c < recheckRow.getLastCellNum(); c++) {
                            if (c == colNo || c == colDesignation || c == colUnit || c == colQty) continue;
                            Cell cell = recheckRow.getCell(c);
                            String val = getCellAsString(cell, evaluator);
                            if (val == null) continue;
                            String lower = val.toLowerCase().trim();
                            if (lower.contains("prix") || lower.contains("unitaire")
                                    || lower.contains("p.u") || lower.equals("pu")) {
                                colPrice = c;
                                break;
                            }
                        }
                    }
                    break;
                }
            }

            if (headerRowIdx < 0) continue; // skip sheets without a header row

            List<ParsedRow> rows = new ArrayList<>();
            List<String> currentSection = new ArrayList<>();
            currentSection.add(sheetName); // sheet name = first element of section_path

            for (int r = headerRowIdx + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                String articleNo = colNo >= 0 ? getCellAsString(row.getCell(colNo), evaluator) : null;
                String designation = colDesignation >= 0 ? getCellAsString(row.getCell(colDesignation), evaluator) : null;
                String unitRaw = colUnit >= 0 ? getCellAsString(row.getCell(colUnit), evaluator) : null;
                String qtyRaw = colQty >= 0 ? getCellAsString(row.getCell(colQty), evaluator) : null;
                String priceRaw = colPrice >= 0 ? getCellAsString(row.getCell(colPrice), evaluator) : null;

                // Skip fully empty rows.
                if (isBlank(articleNo) && isBlank(designation) && isBlank(unitRaw) && isBlank(qtyRaw)) continue;

                // §6 rule 8: skip TOTAL/SOUS-TOTAL/TVA/MONTANT/ARRÊTÉ rows.
                if (isTotalRow(designation)) continue;

                // §6 rule 2 + rule 7: section title — a row where either the
                // designation or the articleNo has text, but there's no unit,
                // no quantity, and no price. Handles merged cells where the
                // section title is in the first column (articleNo).
                String sectionText = !isBlank(designation) ? designation.trim()
                        : (!isBlank(articleNo) ? articleNo.trim() : null);
                // Section title: has text but no unit, no quantity, no price.
                // This handles merged cells where the section title is in the
                // first column (which looks like articleNo).
                if (sectionText != null && isBlank(unitRaw) && isBlank(qtyRaw) && isBlank(priceRaw)) {
                    currentSection.add(sectionText);
                    rows.add(new ParsedRow(new ArrayList<>(currentSection),
                            null, sectionText, null, null, null, null, false));
                    continue;
                }

                // Parse quantity.
                BigDecimal quantity = null;
                boolean qtyParseFailed = false;
                if (!isBlank(qtyRaw)) {
                    try { quantity = parseDecimal(qtyRaw); }
                    catch (NumberFormatException e) { qtyParseFailed = true; }
                }

                // Parse unit price.
                BigDecimal unitPrice = null;
                if (!isBlank(priceRaw)) {
                    try { unitPrice = parseDecimal(priceRaw); }
                    catch (NumberFormatException e) { /* keep null */ }
                }

                rows.add(new ParsedRow(new ArrayList<>(currentSection),
                        !isBlank(articleNo) ? articleNo.trim() : null,
                        !isBlank(designation) ? designation.trim() : null,
                        !isBlank(unitRaw) ? unitRaw.trim() : null,
                        qtyRaw, quantity, unitPrice, qtyParseFailed));
            }

            if (!rows.isEmpty()) {
                sheets.add(new ParsedSheet(sheetName, rows));
            }
        }

        wb.close();
        return sheets;
    }

    private static String getCellAsString(Cell cell, FormulaEvaluator evaluator) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                if (d == Math.floor(d) && !Double.isInfinite(d)) yield String.valueOf((long) d);
                yield BigDecimal.valueOf(d).toPlainString();
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                // Use cached value if available, otherwise evaluate.
                try {
                    CellValue cv = evaluator.evaluate(cell);
                    if (cv == null) yield null;
                    yield switch (cv.getCellType()) {
                        case STRING -> cv.getStringValue().trim();
                        case NUMERIC -> {
                            double d = cv.getNumberValue();
                            if (d == Math.floor(d) && !Double.isInfinite(d)) yield String.valueOf((long) d);
                            yield BigDecimal.valueOf(d).toPlainString();
                        }
                        case BOOLEAN -> String.valueOf(cv.getBooleanValue());
                        default -> null;
                    };
                } catch (Exception e) {
                    try { yield cell.getStringCellValue().trim(); }
                    catch (Exception e2) {
                        try { yield BigDecimal.valueOf(cell.getNumericCellValue()).toPlainString(); }
                        catch (Exception e3) { yield null; }
                    }
                }
            }
            default -> null;
        };
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    /** Parse a decimal from text: remove spaces + non-breaking spaces, use comma as decimal separator. */
    static BigDecimal parseDecimal(String s) {
        String cleaned = s.trim()
                .replace(" ", "")
                .replace("\u00a0", "")  // non-breaking space
                .replace("\u202f", ""); // narrow no-break space
        // If there's both a comma and a dot, assume comma is thousands separator.
        if (cleaned.contains(",") && cleaned.contains(".")) {
            cleaned = cleaned.replace(",", "");
        } else if (cleaned.contains(",")) {
            cleaned = cleaned.replace(",", ".");
        }
        return new BigDecimal(cleaned);
    }

    // ═══════════════════════════════════════════════════════════════
    // §6 rule 8 — skip total rows
    // ═══════════════════════════════════════════════════════════════

    static boolean isTotalRow(String designation) {
        if (designation == null || designation.isBlank()) return false;
        String upper = designation.toUpperCase().trim();
        return upper.startsWith("TOTAL") || upper.startsWith("SOUS-TOTAL")
                || upper.startsWith("TVA") || upper.startsWith("MONTANT")
                || upper.startsWith("ARRÊTÉ") || upper.startsWith("ARRETE");
    }

    // ═══════════════════════════════════════════════════════════════
    // §2.4 — UNIT NORMALISATION
    // ═══════════════════════════════════════════════════════════════

    static String normaliseUnit(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String lower = raw.toLowerCase().trim();
        if (lower.equals("ml") || lower.equals("mètre linéaire") || lower.equals("metre lineaire")
                || lower.equals("m. linéaire"))
            return "ML";
        if (lower.equals("m3") || lower.equals("m³") || lower.equals("mètre cube") || lower.equals("metre cube"))
            return "M3";
        if (lower.equals("m2") || lower.equals("m²") || lower.equals("mètre carré") || lower.equals("metre carre"))
            return "M2";
        if (lower.equals("u") || lower.equals("unité") || lower.equals("l'unité") || lower.equals("l'unite") || lower.equals("unit"))
            return "U";
        if (lower.equals("kg") || lower.equals("kilogramme") || lower.equals("kilos"))
            return "KG";
        if (lower.equals("ens") || lower.equals("ens.") || lower.equals("ensemble"))
            return "ENS";
        if (lower.equals("forfait") || lower.equals("forf") || lower.equals("ff"))
            return "FORFAIT";
        if (lower.equals("lot") || lower.equals("lots"))
            return "LOT";
        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    // §7.3 — SPECS EXTRACTION
    // ═══════════════════════════════════════════════════════════════

    static Map<String, Object> extractSpecs(String designation) {
        Map<String, Object> specs = new LinkedHashMap<>();
        if (designation == null || designation.isBlank()) return specs;
        String lower = designation.toLowerCase();

        // DE → de_mm
        var deM = Pattern.compile("DE\\s*(\\d{2,3})", Pattern.CASE_INSENSITIVE).matcher(designation);
        if (deM.find()) try { specs.put("de_mm", Integer.parseInt(deM.group(1))); } catch (NumberFormatException ignored) {}

        // DN → dn_mm
        var dnM = Pattern.compile("DN\\s*(\\d{2,4})", Pattern.CASE_INSENSITIVE).matcher(designation);
        if (dnM.find()) try { specs.put("dn_mm", Integer.parseInt(dnM.group(1))); } catch (NumberFormatException ignored) {}

        // PN → pn_bar
        var pnM = Pattern.compile("PN\\s*(\\d{2})", Pattern.CASE_INSENSITIVE).matcher(designation);
        if (pnM.find()) try { specs.put("pn_bar", Integer.parseInt(pnM.group(1))); } catch (NumberFormatException ignored) {}

        // capacite_m3 — in section/designation with "réservoir", "brise", "bâche"
        if (lower.contains("réservoir") || lower.contains("reservoir") || lower.contains("brise") || lower.contains("bâche")) {
            var capM = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*m(?:3|³)", Pattern.CASE_INSENSITIVE).matcher(designation);
            if (capM.find()) try { specs.put("capacite_m3", Double.parseDouble(capM.group(1).replace(",", "."))); } catch (NumberFormatException ignored) {}
        }

        // dosage_kg_m3 — dos[ée]\s*(?:à\s*)?(\d{3})\s*kg
        var dosM = Pattern.compile("dos[ée]\\s*(?:à\\s*)?(\\d{3})\\s*kg", Pattern.CASE_INSENSITIVE).matcher(designation);
        if (dosM.find()) try { specs.put("dosage_kg_m3", Integer.parseInt(dosM.group(1))); } catch (NumberFormatException ignored) {}

        // materiau
        if (lower.contains("pehd")) specs.put("materiau", "PEHD");
        else if (lower.contains("fonte")) specs.put("materiau", "FONTE");
        else if (lower.contains("pvc")) specs.put("materiau", "PVC");
        else if (lower.contains("acier galvanis") || lower.contains("galva")) specs.put("materiau", "ACIER_GALVANISE");

        return specs;
    }

    // ═══════════════════════════════════════════════════════════════
    // §7.4 — OUVRAGE SUGGESTION
    // ═══════════════════════════════════════════════════════════════

    record OuvrageSuggestion(UUID ouvrageId, String ouvrageType) {}

    static OuvrageSuggestion suggestOuvrage(ParsedRow row, List<Map<String, Object>> ouvrages) {
        String sectionLower = row.sectionPath.stream().reduce("", (a, b) -> a + " " + b).toLowerCase();
        String desigLower = row.designation != null ? row.designation.toLowerCase() : "";
        String combined = sectionLower + " " + desigLower;

        if (combined.contains("réservoir") || combined.contains("reservoir")
                || combined.contains("brise charge") || combined.contains("brise-charge")
                || combined.contains("bâche")) return findOuvrageByType(ouvrages, "RESERVOIR");
        if (combined.contains("station") || combined.contains("pompage") || combined.contains("reprise")
                || combined.contains("local gic") || combined.contains("hydromécanique")
                || combined.contains("solaire") || combined.contains("photovoltaïque"))
            return findOuvrageByType(ouvrages, "STATION_POMPAGE");
        if (combined.contains("forage") || combined.contains("puits")) return findOuvrageByType(ouvrages, "FORAGE");
        if (combined.contains("installation") || combined.contains("repli") || combined.contains("plans d'exécution") || combined.contains("amenée"))
            return findOuvrageByType(ouvrages, "COMMUN");
        if (combined.contains("conduite") || combined.contains("pose") || combined.contains("tranchée")
                || combined.contains("tranchee") || combined.contains("pehd") || combined.contains("fouille")
                || combined.contains("remblai") || combined.contains("sable"))
            return findOuvrageByType(ouvrages, "CONDUITE");
        if (combined.contains("borne fontaine") || combined.contains("potence") || combined.contains("branchement"))
            return findOuvrageByType(ouvrages, "BRANCHEMENTS");
        if (combined.contains("regard") || combined.contains("ventouse") || combined.contains("vidange")
                || combined.contains("sectionnement") || combined.contains("piquage")) {
            OuvrageSuggestion annex = findOuvrageByType(ouvrages, "OUVRAGE_ANNEXE");
            return annex.ouvrageId != null ? annex : findOuvrageByType(ouvrages, "CONDUITE");
        }
        return new OuvrageSuggestion(null, null);
    }

    private static OuvrageSuggestion findOuvrageByType(List<Map<String, Object>> ouvrages, String type) {
        for (Map<String, Object> o : ouvrages) {
            if (type.equals(o.get("type"))) return new OuvrageSuggestion((UUID) o.get("id"), type);
        }
        return new OuvrageSuggestion(null, type);
    }

    // ═══════════════════════════════════════════════════════════════
    // §2.2 — FOURNITURE / POSE
    // ═══════════════════════════════════════════════════════════════

    static boolean isFourniture(String designation) {
        if (designation == null) return false;
        String lower = designation.toLowerCase();
        return lower.contains("fourniture") && !lower.contains("pose");
    }

    static boolean isPose(String designation) {
        if (designation == null) return false;
        String lower = designation.toLowerCase();
        return lower.contains("pose") || lower.contains("essai");
    }

    // ═══════════════════════════════════════════════════════════════
    // WORK NATURE
    // ═══════════════════════════════════════════════════════════════

    static String inferWorkNature(String designation, List<String> sectionPath) {
        // Check the DESIGNATION first — it's more specific than the section.
        // Only fall back to the section path if the designation doesn't match.
        String desigLower = designation != null ? designation.toLowerCase() : "";
        String sectionLower = sectionPath != null ? String.join(" ", sectionPath).toLowerCase() : "";

        // Designation-level checks (specific).
        if (desigLower.contains("fourniture") && !desigLower.contains("pose")) return "FOURNITURE";
        if (desigLower.contains("pose") || desigLower.contains("essai")) return "POSE";
        if (desigLower.contains("fouille") || desigLower.contains("déblai") || desigLower.contains("remblai")
                || desigLower.contains("terrassement") || desigLower.contains("tranchée") || desigLower.contains("tranchee"))
            return "TERRASSEMENT";
        if (desigLower.contains("béton") || desigLower.contains("aciers") || desigLower.contains("acier")
                || desigLower.contains("coffrage") || desigLower.contains("enduit") || desigLower.contains("maçon") || desigLower.contains("brique"))
            return "GENIE_CIVIL";
        if (desigLower.contains("pompe") || desigLower.contains("panneau") || desigLower.contains("solaire")
                || desigLower.contains("chlor") || desigLower.contains("tableau") || desigLower.contains("câble")
                || desigLower.contains("robinet") || desigLower.contains("trappe") || desigLower.contains("échelle")
                || desigLower.contains("porte") || desigLower.contains("éclairage") || desigLower.contains("clôture"))
            return "EQUIPEMENT";
        if (desigLower.contains("installation") || desigLower.contains("repli")) return "INSTALLATION";

        // Section-level fallback (less specific).
        if (sectionLower.contains("fourniture") && !sectionLower.contains("pose")) return "FOURNITURE";
        if (sectionLower.contains("pose") || sectionLower.contains("essai")) return "POSE";
        if (sectionLower.contains("terrassement") || sectionLower.contains("tranchée")) return "TERRASSEMENT";
        if (sectionLower.contains("génie civil") || sectionLower.contains("genie civil")) return "GENIE_CIVIL";
        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════

    private static String sectionKey(List<String> sectionPath) {
        return String.join("/", sectionPath);
    }

    private static String specsToJson(Map<String, Object> specs) {
        if (specs == null || specs.isEmpty()) return "{}";
        try {
            tools.jackson.databind.ObjectMapper m = new tools.jackson.databind.ObjectMapper();
            return m.writeValueAsString(specs);
        } catch (Exception e) { return "{}"; }
    }

    // ═══════════════════════════════════════════════════════════════
    // INTERNAL TYPES
    // ═══════════════════════════════════════════════════════════════

    record ParsedSheet(String sheetName, List<ParsedRow> rows) {}

    record ParsedRow(List<String> sectionPath, String articleNo, String designation,
                  String unitRaw, String quantityRaw,
                  BigDecimal quantity, BigDecimal unitPrice, boolean quantityParseFailed) {}

    /** A line that has been inserted into the DB — carries the lineId for pairing. */
    static class InsertedLine {
        final UUID lineId;
        final ParsedRow row;
        final String normalisedUnit;
        final Map<String, Object> specs;
        final OuvrageSuggestion suggestion;
        final String workNature;
        final List<String> warnings;
        final BigDecimal montant;
        UUID pairedLineId;
        final int sortOrder;

        InsertedLine(UUID lineId, ParsedRow row, String normalisedUnit, Map<String, Object> specs,
                     OuvrageSuggestion suggestion, String workNature, List<String> warnings, BigDecimal montant) {
            this.lineId = lineId;
            this.row = row;
            this.normalisedUnit = normalisedUnit;
            this.specs = specs;
            this.suggestion = suggestion;
            this.workNature = workNature;
            this.warnings = warnings;
            this.montant = montant;
            this.sortOrder = 0;
        }
    }
}
