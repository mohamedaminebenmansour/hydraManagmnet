package tn.hydra.controle;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import tn.hydra.common.BusinessRuleException;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

/**
 * ControleExcelReader — reads « Caisse des projets.xlsx » into a
 * {@link ControleImportFile.ImportFile}, EXACTLY like tools/controle/convert.py.
 *
 * <p>convert.py is the reference: same sheets, same columns, same rules, same
 * line ids (justifications and validations are keyed by them). The method and
 * constant names below are the Python names, in the same order, so the two
 * files can be read side by side. Where Python and Java differ (float text,
 * whitespace, {@code str.title()}, openpyxl cell values) a helper reproduces
 * the Python behaviour; see the "Python semantics" section at the end.
 */
@Component
public class ControleExcelReader {

    // ════════════════════════════════════════════════════════════════════════
    // CONSTANTS (convert.py)
    // ════════════════════════════════════════════════════════════════════════

    private static final String[][] MODES = {
            {"esp", "Espèces"}, {"vir", "Virement"}, {"prél", "Prélèvement"}, {"prel", "Prélèvement"},
            {"vers", "Versement"}, {"ch", "Chèque"}, {"carte", "Carte"}, {"mand", "Mandat"}, {"mond", "Mandat"},
            {"trait", "Traite"}, {"télécharg", "Téléchargement"}, {"telecharg", "Téléchargement"},
            {"caisse chantier", "Caisse chantier"}, {"caisse ngnet", "Caisse NGNET"}, {"ngnet", "NGNET"},
            {"hani", "Hani Barkia"}};

    private static final Map<String, String> PROJ = Map.ofEntries(
            Map.entry("hajeb", "Hajeb"), Map.entry("hammed", "Hammed"),
            Map.entry("ouled khalfallah", "Ouled Khalfallah"), Map.entry("zaghdoud", "Zaghdoud"),
            Map.entry("guttar messiouta", "Guttar Messiouta"), Map.entry("soualem", "Soualem"),
            Map.entry("sigat", "SIGAT (frais généraux)"), Map.entry("sidi rebeh", "Sidi Rebeh"),
            Map.entry("msalsel", "Msalsel"), Map.entry("nebeur", "Nebeur"), Map.entry("alaa", "Alaa"));

    private static final String[][] PERSONS = {
            {"hamza", "Hamza Msalmi"}, {"ismai", "Ismaiil Mansouri"}, {"ismail", "Ismaiil Mansouri"},
            {"hedi", "Med Hedi El Abed"}, {"houimli", "Ahmed Houimli"}, {"moula", "Abd El Moula"},
            {"aymen", "Aymen Amri"}, {"haider", "Haider Methneni"}, {"taher", "Taher"}, {"nejib", "Nejib"},
            {"ammar", "Ammar Hamdi"}, {"said", "Said"}, {"moncef", "Moncef"}, {"hamed", "Hamed"},
            {"aqua", "Sté Aqua Réseau"}};

    private static final Set<Integer> INTERNAL = Set.of(606, 607, 962, 963, 1647, 1648);

    static final String ECHEANCE = "Caisse à échéance";

    private static final Map<String, String> ACCTS = Map.of(
            "UIB SIGAT", "uib-sigat", "UIB OMAR", "uib-omar", "UIB NGNET", "uib-ngnet",
            "Caisse SIGAT", "caisse-sigat");

    /** sheet → (account id, project). Insertion order = convert.py order. */
    static final LinkedHashMap<String, String[]> SITE = new LinkedHashMap<>();
    /** sheet → (account id, project, owner). */
    static final LinkedHashMap<String, String[]> PARTNER = new LinkedHashMap<>();
    /** sheet → (person, project). Sheet names are exact, trailing spaces included. */
    static final LinkedHashMap<String, String[]> ST_SHEETS = new LinkedHashMap<>();

    static {
        SITE.put("Caisse Chantier Hajeb", new String[]{"chantier-hajeb", "Hajeb"});
        SITE.put("Caisse Chantier Hammed", new String[]{"chantier-hammed", "Hammed"});
        SITE.put("Caisse Chantier Ouled Khalfalah", new String[]{"chantier-okh", "Ouled Khalfallah"});
        SITE.put("Caisse Chantier Zaghdoud", new String[]{"chantier-zaghdoud", "Zaghdoud"});

        PARTNER.put("STE NGNET Hajeb", new String[]{"ngnet-hajeb", "Hajeb", "NGNET"});
        PARTNER.put("SOCIETE NGNET HAMMED", new String[]{"ngnet-hammed", "Hammed", "NGNET"});
        PARTNER.put("STE NGNET OULED KHALFALLAH", new String[]{"ngnet-okh", "Ouled Khalfallah", "NGNET"});
        PARTNER.put("SOCIETE HANI BARKIA", new String[]{"hb-zaghdoud", "Zaghdoud", "Hani Barkia"});

        st("ST Ismail Hajeb", "Ismaiil Mansouri", "Hajeb");
        st("S.T Ismaiil Mansouri zagh", "Ismaiil Mansouri", "Zaghdoud");
        st("S.T Hamza Msalmi + AMIN Hajeb", "Hamza Msalmi", "Hajeb");
        st("ST Hamza", "Hamza Msalmi", "Hajeb");
        st("S.T Hamza Msalmi zagh", "Hamza Msalmi", "Zaghdoud");
        st("S.T Med Hedi Abed", "Med Hedi El Abed", "Zaghdoud");
        st("S.T Ahmed Houimli Hajeb", "Ahmed Houimli", "Hajeb");
        st("S.T Ahmed Houimli", "Ahmed Houimli", "Hammed");
        st("ST Abd El Moula ouled", "Abd El Moula", "Ouled Khalfallah");
        st("ST Abd El Moula Hajeb", "Abd El Moula", "Hajeb");
        st("S.T Aymen  Amri", "Aymen Amri", "Hammed");
        st("ST Aymen Amri - Soualem", "Aymen Amri", "Soualem");
        st("ST Haider Methneni", "Haider Methneni", "Hajeb");
        st("S.T Taher Zagh", "Taher", "Zaghdoud");
        st("ST TAHER o,kh ", "Taher", "Ouled Khalfallah");
        st("st hamed O,KH", "Hamed", "Ouled Khalfallah");
        st("ST NEJIB O,KH", "Nejib", "Ouled Khalfallah");
        st("ST SAID TD O,KH", "Said", "Ouled Khalfallah");
        st("st said R 500", "Said", "Ouled Khalfallah");
        st("ST SAID ABRIS", "Said", "Ouled Khalfallah");
        st("st said clôture", "Said", "Ouled Khalfallah");
        st("ST Said BC", "Said", "Ouled Khalfallah");
        st("MO RSE 150", "Équipe MO RSE 150", "Hajeb");
        st("MO RSE 100", "Équipe MO RSE 100", "Hajeb");
        st("MO RSP Hajeb", "Équipe MO RSP", "Hajeb");
        st("MO Station surp", "Équipe MO station surpresseur", "Hajeb");
        st("MO Hammed", "Moncef", "Hammed");
        st("MO OULED KHALFALLAH", "Équipe MO Ouled Khalfallah", "Ouled Khalfallah");
        st("Sté Aqua Réseau", "Sté Aqua Réseau", "Soualem / abris");
    }

    private static void st(String sheet, String person, String proj) {
        ST_SHEETS.put(sheet, new String[]{person, proj});
    }

    private static final Pattern PERSON_START = Pattern.compile("^(st|s\\.t|s\\.t\\.|mo|sous|sté|ste )");
    private static final Pattern ST_START = Pattern.compile("^(st |s\\.t)");
    private static final Pattern MO_START = Pattern.compile("^mo ");
    private static final Pattern OUVRAGE_START = Pattern.compile(
            "^(rsp|rse|station|surpress|abri|clôture|cloture|bc$|niche|ouvrage|regard|branchement|vanne)");
    private static final Pattern DOUBLON_EXEMPT = Pattern.compile("^(cnss|agrement|prime|pour omar|gazoil|voiture omar)");
    private static final Pattern SID_CLEAN = Pattern.compile("[^a-z0-9]+");

    private static final DateTimeFormatter GENERATED = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    // ════════════════════════════════════════════════════════════════════════
    // READ
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Read the workbook. {@code today} is used for the date flags and {@code now}
     * for {@code meta.generated}, like convert.py's TODAY and datetime.now().
     *
     * @throws BusinessRuleException IMPORT_FORMAT when the file is not a readable
     *         .xlsx, a sheet is missing or a caisse sheet has no header row.
     */
    public ControleImportFile.ImportFile read(InputStream xlsx, String sourceName,
                                              LocalDate today, LocalDateTime now) {
        XSSFWorkbook wb;
        try {
            wb = new XSSFWorkbook(xlsx);
        } catch (Exception e) {
            throw new BusinessRuleException("IMPORT_FORMAT", "Le fichier Excel ne peut pas être lu.");
        }
        try (wb) {
            return new Run(wb, today).convert(sourceName, now);
        } catch (java.io.IOException e) {
            throw new BusinessRuleException("IMPORT_FORMAT", "Le fichier Excel ne peut pas être lu.");
        }
    }

    /** One conversion: holds the state convert.py keeps in globals (seen, lines). */
    private static final class Run {
        private final XSSFWorkbook wb;
        private final boolean date1904;
        private final String todayIso;
        private final Map<String, Integer> seen = new HashMap<>();
        private final List<L> lines = new ArrayList<>();
        private final List<ControleImportFile.Account> accounts = new ArrayList<>();
        private final List<ControleImportFile.StAccount> stAccounts = new ArrayList<>();
        private final List<S> stLines = new ArrayList<>();

        Run(XSSFWorkbook wb, LocalDate today) {
            this.wb = wb;
            this.date1904 = wb.isDate1904();
            this.todayIso = today.toString();
        }

        ControleImportFile.ImportFile convert(String sourceName, LocalDateTime now) {
            accounts.add(new ControleImportFile.Account("uib-sigat", "UIB SIGAT", "banque", "SIGAT", "", null));
            accounts.add(new ControleImportFile.Account("uib-omar", "UIB OMAR", "banque", "SIGAT", "",
                    "Compte SIGAT ; « OMAR » est seulement le nom de référence du compte."));
            accounts.add(new ControleImportFile.Account("uib-ngnet", "UIB NGNET", "banque", "NGNET", "", null));
            accounts.add(new ControleImportFile.Account("caisse-sigat", "Caisse SIGAT", "especes", "SIGAT", "",
                    "À préciser : caisse espèces du bureau ?"));
            SITE.forEach((sh, v) -> accounts.add(new ControleImportFile.Account(
                    v[0], "Caisse chantier " + v[1], "chantier", "SIGAT", v[1], null)));
            PARTNER.forEach((sh, v) -> accounts.add(new ControleImportFile.Account(
                    v[0], "Caisse " + v[2] + " " + v[1], "partenaire", v[2], v[1], null)));
            Sheet echeanceSheet = sheetOrNull(ECHEANCE);
            if (echeanceSheet != null) {
                accounts.add(new ControleImportFile.Account("caisse-echeance", ECHEANCE, "echeance", "SIGAT", "", null));
            }

            caisseCentral();
            SITE.forEach((sh, v) -> loadBox(sh, v[0], v[1], false));
            PARTNER.forEach((sh, v) -> loadBox(sh, v[0], v[1], true));
            stSheets();
            if (echeanceSheet != null) echeance(echeanceSheet);
            rapprochement();
            flags();

            String source = sourceName == null ? "" : sourceName.substring(sourceName.lastIndexOf('/') + 1);
            source = source.substring(source.lastIndexOf('\\') + 1);
            var meta = new ControleImportFile.Meta(source, now.truncatedTo(ChronoUnit.MINUTES).format(GENERATED),
                    lines.size(), stLines.size());
            return new ControleImportFile.ImportFile(meta, accounts, stAccounts,
                    lines.stream().map(L::toRecord).toList(),
                    stLines.stream().map(S::toRecord).toList());
        }

        // ── make_id ───────────────────────────────────────────────────────

        String makeId(String sheet, String date, double e, double d, String desig) {
            String base = sheet + "|" + date + "|" + fmt3(e) + "|" + fmt3(d) + "|" + lower(desig);
            String h = sha1Hex(base).substring(0, 10);
            int n = seen.getOrDefault(h, 0);
            seen.put(h, n + 1);
            return n == 0 ? h : h + "-" + n;
        }

        // ── Caisse Central ────────────────────────────────────────────────

        void caisseCentral() {
            Sheet ws = sheet("Caisse Central");
            for (int r = 4; r <= maxRow(ws); r++) {
                final int row = r;
                java.util.function.IntFunction<Object> g = c -> value(ws, row, c);
                Double cr = num(g.apply(11)), db = num(g.apply(12));
                if (!(truthy(cr) || truthy(db)) && !truthy(g.apply(8))) continue;
                String cat = txt(g.apply(6)), dest = txt(g.apply(7)), desig = txt(g.apply(8));
                String acct = ACCTS.getOrDefault(txt(g.apply(10)), "");
                String date = day(g.apply(3));
                double e = orZero(cr), d = orZero(db);
                String kind = INTERNAL.contains(r) ? "transfert"
                        : (lower(cat).equals("caisse chantier") ? "transfert"
                        : (e != 0 && d == 0 ? "entree" : "sortie"));
                L l = new L();
                l.id = makeId("Caisse Central", date, e, d, desig);
                l.sheet = "Caisse Central"; l.row = r; l.date = date; l.acct = acct; l.proj = projOf(dest);
                l.e = round3(e); l.d = round3(d); l.mode = modeOf(g.apply(9));
                l.bon = txt(g.apply(4)); l.fct = txt(g.apply(5)); l.cat = cat; l.desig = desig;
                l.party = personOf(cat);
                l.poste = e != 0 && d == 0 ? natureIn(desig, cat) : posteOut(cat, "", desig);
                l.kind = kind; l.obs = txt(g.apply(14));
                lines.add(l);
            }
        }

        // ── caisses chantier et partenaires ───────────────────────────────

        void loadBox(String sheetName, String acct, String proj, boolean partner) {
            Sheet ws = sheet(sheetName);
            HeaderRow hr = headerRow(ws);
            if (hr == null) {
                throw new BusinessRuleException("IMPORT_FORMAT",
                        "En-tête « Désignation » introuvable dans la feuille « " + sheetName + " ».");
            }
            List<String> vals = hr.vals;
            Integer cD = col(vals, "Date"), cDes = col(vals, "Désignation"), cMode = col(vals, "Mode");
            Integer cCr = col(vals, "Crédi", "Encaissement"), cDb = col(vals, "Débit", "Décaissement");
            Integer cDest = col(vals, "Sous-traitant", "Déstination", "Destination");
            Integer cBon = col(vals, "Bon"), cFct = col(vals, "Facture", "Fct"), cObs = col(vals, "Observation");
            for (int r = hr.row + 1; r <= maxRow(ws); r++) {
                final int row = r;
                java.util.function.Function<Integer, Object> g = c -> c == null ? null : value(ws, row, c);
                double e = orZero(num(g.apply(cCr))), d = orZero(num(g.apply(cDb)));
                String desig = txt(g.apply(cDes));
                if (!(e != 0 || d != 0) && desig.isEmpty()) continue;
                String dest = txt(g.apply(cDest));
                String date = day(g.apply(cD));
                String p = proj;
                if (lower(dest).startsWith("projet")) p = !partner ? projOf(pyStrip(codePointsFrom(dest, 6))) : proj;
                L l = new L();
                l.id = makeId(sheetName, date, e, d, desig);
                l.sheet = sheetName; l.row = r; l.date = date; l.acct = acct; l.proj = p;
                l.e = round3(e); l.d = round3(d); l.mode = modeOf(g.apply(cMode));
                l.bon = txt(g.apply(cBon)); l.fct = txt(g.apply(cFct)); l.cat = dest; l.desig = desig;
                l.party = personOf(dest);
                if (e != 0 || d != 0) {
                    if (e != 0 && d == 0) {
                        l.poste = partner
                                ? "Financement " + (sheetName.contains("NGNET") ? "NGNET" : "Hani Barkia")
                                : "Alimentation reçue";
                    } else {
                        l.poste = posteOut("", dest, desig);
                    }
                } else {
                    l.poste = "";
                }
                l.kind = partner ? "partenaire" : (e != 0 && d == 0 ? "transfert" : "sortie");
                l.obs = txt(g.apply(cObs));
                lines.add(l);
            }
        }

        // ── comptes sous-traitants ────────────────────────────────────────

        void stSheets() {
            ST_SHEETS.forEach((sh, v) -> {
                String person = v[0], proj = v[1];
                Sheet ws = sheet(sh);
                HeaderRow hr = headerRow(ws);
                int h = hr == null ? 7 : hr.row;
                List<String> vals = hr == null ? List.of() : hr.vals;
                Integer cD = col(vals, "Date"), cDes = col(vals, "Désignation"), cMode = col(vals, "Mode");
                Integer cCr = col(vals, "Crédi", "Encaissement"), cDb = col(vals, "Débit", "Décaissement");
                String sid = "st-" + stripDashes(SID_CLEAN.matcher(lower(sh)).replaceAll("-"));
                stAccounts.add(new ControleImportFile.StAccount(sid, sh, person, proj, sh.startsWith("Sté")));
                for (int r = h + 1; r <= maxRow(ws); r++) {
                    final int row = r;
                    java.util.function.Function<Integer, Object> g = c -> c == null ? null : value(ws, row, c);
                    double w = orZero(num(g.apply(cCr))), p = orZero(num(g.apply(cDb)));
                    String desig = txt(g.apply(cDes));
                    if (!(w != 0 || p != 0)) continue;
                    String date = day(g.apply(cD));
                    S s = new S();
                    s.id = makeId(sh, date, w, p, desig);
                    s.st = sid; s.sheet = sh; s.row = r; s.date = date;
                    s.travaux = round3(w); s.paye = round3(p);
                    s.mode = modeOf(g.apply(cMode)); s.desig = desig; s.link = "";
                    stLines.add(s);
                }
            });
        }

        // ── Caisse à échéance (after every other line: existing ids do not move) ──

        void echeance(Sheet ws) {
            HeaderRow hr = headerRow(ws);
            if (hr == null) {
                throw new BusinessRuleException("IMPORT_FORMAT",
                        "En-tête « Désignation » introuvable dans la feuille « " + ECHEANCE + " ».");
            }
            List<String> vals = hr.vals;
            Integer cD = col(vals, "Date"), cDes = col(vals, "Désignation"), cMode = col(vals, "Mode");
            Integer cDb = col(vals, "Débit"), cDest = col(vals, "Sous-traitant"), cProj = col(vals, "Destination");
            Integer cBon = col(vals, "Bon"), cFct = col(vals, "Fct"), cObs = col(vals, "Observation");
            for (int r = hr.row + 1; r <= maxRow(ws); r++) {
                final int row = r;
                java.util.function.Function<Integer, Object> g = c -> c == null ? null : value(ws, row, c);
                double d = orZero(num(g.apply(cDb)));
                if (d == 0) continue;
                String cat = txt(g.apply(cDest)), desig = txt(g.apply(cDes)), date = day(g.apply(cD));
                L l = new L();
                l.id = makeId(ECHEANCE, date, 0.0, d, desig);
                l.sheet = ECHEANCE; l.row = r; l.date = date; l.acct = "caisse-echeance";
                l.proj = projOf(g.apply(cProj));
                l.e = 0.0; l.d = round3(d); l.mode = modeOf(g.apply(cMode));
                l.bon = txt(g.apply(cBon)); l.fct = txt(g.apply(cFct)); l.cat = cat; l.desig = desig;
                l.party = personOf(cat);
                l.poste = posteOut(cat, "", desig);
                l.kind = "sortie"; l.obs = txt(g.apply(cObs));
                lines.add(l);
            }
        }

        // ── rapprochement ─────────────────────────────────────────────────

        void rapprochement() {
            Map<Double, List<Integer>> byamt = new HashMap<>();
            for (int i = 0; i < lines.size(); i++) {
                L l = lines.get(i);
                if (l.d > 0) byamt.computeIfAbsent(round3(l.d) + 0.0, k -> new ArrayList<>()).add(i);
            }
            Set<Integer> used = new HashSet<>();
            Map<String, String> acctProj = new HashMap<>();
            for (var a : accounts) acctProj.put(a.id(), a.project());
            Map<String, ControleImportFile.StAccount> stmap = new HashMap<>();
            for (var a : stAccounts) stmap.put(a.id(), a);
            for (S s : stLines) {
                if (s.paye <= 0) continue;
                var a = stmap.get(s.st);
                Long dn = dnum(s.date);
                int bestScore = 0;
                Integer best = null;
                for (int i : byamt.getOrDefault(round3(s.paye) + 0.0, List.of())) {
                    if (used.contains(i)) continue;
                    L l = lines.get(i);
                    if (!l.proj.equals(a.proj()) && !Objects.equals(acctProj.get(l.acct), a.proj())) continue;
                    Long ln = dnum(l.date);
                    long gap = (ln != null && dn != null) ? Math.abs(ln - dn) : 999;
                    if (gap > 10 && gap != 999) continue;
                    int score = (int) gap + (l.party.equals(a.person()) ? 0 : 20);
                    if (best == null || score < bestScore) { bestScore = score; best = i; }
                }
                if (best != null) {
                    used.add(best);
                    s.link = lines.get(best).id;
                    if (lines.get(best).stLink == null) lines.get(best).stLink = s.id;
                }
            }
        }

        // ── contrôles automatiques par ligne ──────────────────────────────

        void flags() {
            for (L l : lines) {
                List<String> f = new ArrayList<>();
                if (l.mode.equals("Espèces") && l.d > 5000 && !l.kind.equals("transfert")) f.add("especes");
                if (l.date.isEmpty()) f.add("sans-date");
                else if (l.date.compareTo(todayIso) > 0 || l.date.compareTo("2023-01-01") < 0) f.add("date");
                if (!l.party.isEmpty() && l.d > 0 && l.stLink == null && l.kind.equals("sortie")) f.add("hors-compte");
                if (l.poste.equals("Avance sur bénéfice (ST)")) f.add("benefice");
                if (l.kind.equals("transfert") && INTERNAL.contains(l.row)) f.add("interne");
                l.flags = f;
            }
            Set<String> doublonSheets = new HashSet<>(SITE.keySet());
            doublonSheets.add("Caisse Central");
            Map<List<Object>, List<L>> key = new LinkedHashMap<>();
            for (L l : lines) {
                List<Object> k = List.of(l.sheet, l.date, l.e + 0.0, l.d + 0.0, lower(l.desig), l.proj, l.acct);
                key.computeIfAbsent(k, x -> new ArrayList<>()).add(l);
            }
            key.forEach((k, g) -> {
                double e = (Double) k.get(2), d = (Double) k.get(3);
                if (g.size() > 1 && doublonSheets.contains((String) k.get(0))
                        && !DOUBLON_EXEMPT.matcher((String) k.get(4)).lookingAt() && (e != 0 || d != 0)) {
                    for (L l : g.subList(1, g.size())) l.flags.add("doublon");
                }
            });
            for (S s : stLines) {
                s.flags = new ArrayList<>();
                if (s.paye > 0 && s.link.isEmpty()) s.flags.add("non-retrouve");
                if (!s.date.isEmpty() && (s.date.compareTo(todayIso) > 0 || s.date.compareTo("2023-01-01") < 0)) {
                    s.flags.add("date");
                }
            }
        }

        // ── sheets and cells (openpyxl data_only=True) ────────────────────

        /** wb[name]: exact name (POI's getSheet ignores case, openpyxl does not). */
        Sheet sheet(String name) {
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                if (wb.getSheetName(i).equals(name)) return wb.getSheetAt(i);
            }
            throw new BusinessRuleException("IMPORT_FORMAT",
                    "La feuille « " + name + " » est absente du fichier Excel.");
        }

        /** {@code name in wb.sheetnames}: the sheet, or null when absent (optional sheets). */
        Sheet sheetOrNull(String name) {
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                if (wb.getSheetName(i).equals(name)) return wb.getSheetAt(i);
            }
            return null;
        }

        static int maxRow(Sheet ws) {
            return Math.max(1, ws.getLastRowNum() + 1);
        }

        static int maxColumn(Sheet ws) {
            int max = 1;
            for (Row row : ws) max = Math.max(max, row.getLastCellNum());
            return max;
        }

        record HeaderRow(int row, List<String> vals) {}

        /** header_row(ws): null where Python raises ValueError. */
        HeaderRow headerRow(Sheet ws) {
            int maxCol = maxColumn(ws);
            for (int r = 1; r < 15; r++) {
                List<String> vals = new ArrayList<>();
                for (int c = 1; c <= maxCol; c++) vals.add(txt(value(ws, r, c)));
                if (vals.stream().anyMatch(v -> v.startsWith("Désignation"))) return new HeaderRow(r, vals);
            }
            return null;
        }

        /**
         * ws.cell(r, c).value with openpyxl data_only=True: cached formula result,
         * null for blank / no cached value, error text for error cells,
         * datetime / time for date-formatted numbers.
         */
        Object value(Sheet ws, int r, int c) {
            Row row = ws.getRow(r - 1);
            if (row == null) return null;
            Cell cell = row.getCell(c - 1);
            if (cell == null) return null;
            CellType t = cell.getCellType();
            if (t == CellType.FORMULA) {
                if (cell instanceof XSSFCell x && !x.getCTCell().isSetV()) return null;
                t = cell.getCachedFormulaResultType();
            }
            switch (t) {
                case STRING:
                    return cell.getStringCellValue();
                case BOOLEAN:
                    return cell.getBooleanCellValue();
                case ERROR:
                    return cell instanceof XSSFCell x ? x.getErrorCellString() : "#ERROR";
                case NUMERIC: {
                    double v = cell.getNumericCellValue();
                    var style = cell.getCellStyle();
                    if (style != null && DateUtil.isADateFormat(style.getDataFormat(), style.getDataFormatString())) {
                        return fromExcel(v, date1904);
                    }
                    return v;
                }
                default:
                    return null;
            }
        }
    }

    /** Mutable line while converting (stLink and flags are set after reading). */
    private static final class L {
        String id, sheet, date, acct, proj, mode, bon, fct, cat, desig, party, poste, kind, obs, stLink;
        int row;
        double e, d;
        List<String> flags = new ArrayList<>();

        ControleImportFile.Line toRecord() {
            return new ControleImportFile.Line(id, sheet, row, date, acct, proj, e, d, mode, bon, fct, cat,
                    desig, party, poste, kind, obs, List.copyOf(flags), stLink);
        }
    }

    private static final class S {
        String id, st, sheet, date, mode, desig, link;
        int row;
        double travaux, paye;
        List<String> flags = new ArrayList<>();

        ControleImportFile.StLine toRecord() {
            return new ControleImportFile.StLine(id, st, sheet, row, date, travaux, paye, mode, desig, link,
                    List.copyOf(flags));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // RULES (convert.py functions)
    // ════════════════════════════════════════════════════════════════════════

    static Double num(Object v) {
        if (v == null || "".equals(v)) return 0.0;
        if (v instanceof Double x) return x;
        if (v instanceof Boolean b) return b ? 1.0 : 0.0;
        return pyFloat(pyStr(v).replace(" ", "").replace(",", "."));
    }

    static String day(Object v) {
        if (v instanceof LocalDateTime dt) return dt.toLocalDate().toString();
        return "";
    }

    static String txt(Object v) {
        if (v == null) return "";
        if (v instanceof Double x && isInteger(x)) return new BigDecimal(x).toBigInteger().toString();
        return pyStrip(collapseSpaces(pyStr(v)));
    }

    static String modeOf(Object m) {
        String t = lower(txt(m));
        if (t.isEmpty()) return "Non renseigné";
        for (String[] kv : MODES) {
            if (t.startsWith(kv[0])) return kv[1];
        }
        return txt(m);
    }

    static String projOf(Object v) {
        String t = lower(txt(v));
        String p = PROJ.get(t);
        if (p != null) return p;
        return !t.isEmpty() ? pyTitle(txt(v)) : "Sans projet";
    }

    static String personOf(Object v) {
        String t = lower(txt(v));
        if (!PERSON_START.matcher(t).lookingAt() && !codePointsTo(t, 4).contains("st ")) return "";
        for (String[] kp : PERSONS) {
            if (t.contains(kp[0])) return kp[1];
        }
        return "";
    }

    static String kw(String t) {
        t = lower(t);
        if (find("omar", t)) return "Versements « Omar » (à préciser)";
        if (find("carbur|gazoil|gasoil|essence|recharge", t)) return "Carburant";
        if (find("location|loyer|tractopelle|pelle|camion|voiture|transport|voyage", t)) return "Location et transport";
        if (find("caution|enregistrement|timbre|frais banc|agios|commission|acte de|redressement|recette fiscale|cnss|comptable", t)) {
            return "Cautions, fiscal et frais";
        }
        if (find("salaire|prime|gardien|ouvrier|journ", t)) return "Salaires et main-d'œuvre";
        if (find("pr[eê]t", t)) return "Prêts";
        return "Autres charges";
    }

    static String posteOut(String cat, String dest, String desig) {
        String c = lower(txt(cat)), d = lower(txt(dest)), s = lower(txt(desig));
        if (s.contains("avance sur benefice") || s.contains("avance sur bénéfice")) return "Avance sur bénéfice (ST)";
        for (String x : new String[]{c, d}) {
            if (ST_START.matcher(x).lookingAt()) return "Sous-traitants";
            if (MO_START.matcher(x).lookingAt()) return "Salaires et main-d'œuvre";
            if (x.contains("conduite") || x.contains("piéce") || x.contains("pièce")) return "Conduites et pièces spéciales";
            if (OUVRAGE_START.matcher(x).lookingAt()) return "Ouvrages (RSP, RSE, station…)";
        }
        if (c.equals("caisse chantier")) return "Alimentation caisse chantier";
        if (d.startsWith("projet")) return "Dépensé pour un autre projet";
        return kw(s);
    }

    static String natureIn(String desig, String cat) {
        String s = lower(txt(desig));
        if (s.contains("solde de départ")) return "Solde de départ";
        if (s.contains("prêt de")) return "Prêt reçu";
        if (s.contains("remboursement prêt") || s.contains("regule")) return "Remboursement de prêt";
        if (s.contains("caution")) return "Caution récupérée";
        if (s.contains("vente camion")) return "Vente d'un camion";
        if (s.contains("karama")) return "Contrat Karama";
        if (s.contains("benefice") || s.contains("bénéfice")) return "Bénéfice SIGAT";
        if (s.contains("décompte") || s.contains("decompte") || s.contains("budjet")) return "Décompte client";
        if (s.contains("encaissement projet")) return "Encaissement projet";
        if (s.contains("alimentation") || s.contains("virement") || s.contains("versement")) {
            return "Alimentation / virement reçu";
        }
        return "Autre entrée";
    }

    static Integer col(List<String> vals, String... prefixes) {
        for (String p : prefixes) {
            for (int i = 0; i < vals.size(); i++) {
                if (lower(vals.get(i)).startsWith(lower(p))) return i + 1;
            }
        }
        return null;
    }

    /** dnum(s): date.fromisoformat(s).toordinal(), null when s is not a date. */
    static Long dnum(String s) {
        try {
            return LocalDate.parse(s).toEpochDay();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // PYTHON SEMANTICS
    // ════════════════════════════════════════════════════════════════════════

    /** Python truthiness of a cell value or number. */
    static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Double x) return x != 0;
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return !s.isEmpty();
        return true; // datetime, time
    }

    /** {@code x or 0.0}: None, 0.0 and -0.0 all give 0.0. */
    static double orZero(Double x) {
        return x == null || x == 0 ? 0.0 : x;
    }

    static boolean isInteger(double x) {
        return !Double.isNaN(x) && !Double.isInfinite(x) && x == Math.rint(x);
    }

    /** f"{x:.3f}": exact binary value, round half even, keeps "-0.000". */
    static String fmt3(double x) {
        if (Double.isNaN(x)) return "nan";
        if (Double.isInfinite(x)) return x > 0 ? "inf" : "-inf";
        BigDecimal bd = new BigDecimal(x).setScale(3, RoundingMode.HALF_EVEN);
        String s = bd.toPlainString();
        boolean negative = x < 0 || (x == 0 && 1 / x < 0);
        return bd.signum() == 0 && negative ? "-" + s : s;
    }

    /** round(x, 3) on a Python float. */
    static double round3(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x)) return x;
        double r = new BigDecimal(x).setScale(3, RoundingMode.HALF_EVEN).doubleValue();
        return r == 0 && (x < 0 || 1 / x < 0) ? -0.0 : r;
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    static String sha1Hex(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean find(String regex, String t) {
        return Pattern.compile(regex).matcher(t).find();
    }

    /** str.isspace() for one code point (Java's isWhitespace skips no-break spaces and U+0085). */
    static boolean pySpace(int cp) {
        return Character.isWhitespace(cp) || cp == 0x85 || cp == 0xA0 || cp == 0x2007 || cp == 0x202F;
    }

    /** re.sub(r'\s+', ' ', s). */
    static String collapseSpaces(String s) {
        StringBuilder b = new StringBuilder(s.length());
        boolean inSpace = false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (pySpace(cp)) {
                if (!inSpace) b.append(' ');
                inSpace = true;
            } else {
                b.appendCodePoint(cp);
                inSpace = false;
            }
        }
        return b.toString();
    }

    /** str.strip(). */
    static String pyStrip(String s) {
        int start = 0, end = s.length();
        while (start < end && pySpace(s.codePointAt(start))) start += Character.charCount(s.codePointAt(start));
        while (end > start && pySpace(s.codePointBefore(end))) end -= Character.charCount(s.codePointBefore(end));
        return s.substring(start, end);
    }

    /** s[n:] by code points. */
    static String codePointsFrom(String s, int n) {
        if (s.codePointCount(0, s.length()) <= n) return "";
        return s.substring(s.offsetByCodePoints(0, n));
    }

    /** s[:n] by code points. */
    static String codePointsTo(String s, int n) {
        if (s.codePointCount(0, s.length()) <= n) return s;
        return s.substring(0, s.offsetByCodePoints(0, n));
    }

    static String stripDashes(String s) {
        int start = 0, end = s.length();
        while (start < end && s.charAt(start) == '-') start++;
        while (end > start && s.charAt(end - 1) == '-') end--;
        return s.substring(start, end);
    }

    /** str.title(): upper after a non-cased character, lower after a cased one. */
    static String pyTitle(String s) {
        StringBuilder b = new StringBuilder(s.length());
        boolean prevCased = false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            b.appendCodePoint(prevCased ? Character.toLowerCase(cp) : Character.toTitleCase(cp));
            prevCased = Character.isUpperCase(cp) || Character.isLowerCase(cp) || Character.isTitleCase(cp);
        }
        return b.toString();
    }

    /** str(v) for the values openpyxl returns. */
    static String pyStr(Object v) {
        if (v instanceof String s) return s;
        if (v instanceof Double x) return pyRepr(x);
        if (v instanceof Boolean b) return b ? "True" : "False";
        if (v instanceof LocalDateTime dt) return dt.toLocalDate() + " " + pyTime(dt.toLocalTime());
        if (v instanceof LocalTime t) return pyTime(t);
        return String.valueOf(v);
    }

    private static String pyTime(LocalTime t) {
        String s = String.format("%02d:%02d:%02d", t.getHour(), t.getMinute(), t.getSecond());
        int micros = t.getNano() / 1000;
        return micros == 0 ? s : s + String.format(".%06d", micros);
    }

    /** repr(float): shortest round-trip digits, scientific when exponent < -4 or >= 16. */
    static String pyRepr(double x) {
        if (Double.isNaN(x)) return "nan";
        if (Double.isInfinite(x)) return x > 0 ? "inf" : "-inf";
        if (x == 0) return 1 / x < 0 ? "-0.0" : "0.0";
        // Double.toString gives the shortest round-trip digits on JDK 19+.
        BigDecimal bd = new BigDecimal(Double.toString(Math.abs(x))).stripTrailingZeros();
        String digits = bd.unscaledValue().toString();
        int exp = digits.length() - 1 - bd.scale();
        String sign = x < 0 ? "-" : "";
        if (exp < -4 || exp >= 16) {
            String mant = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
            return sign + mant + "e" + (exp < 0 ? "-" : "+") + String.format("%02d", Math.abs(exp));
        }
        String plain = bd.toPlainString();
        return sign + (plain.contains(".") ? plain : plain + ".0");
    }

    private static final Pattern PY_FLOAT = Pattern.compile(
            "[+-]?(?:(?:\\d(?:_?\\d)*(?:\\.(?:\\d(?:_?\\d)*)?)?|\\.\\d(?:_?\\d)*)(?:[eE][+-]?\\d(?:_?\\d)*)?"
                    + "|(?i:inf|infinity|nan))");

    /** float(s): Python's grammar (Unicode digits, '_' between digits, inf/nan); null on ValueError. */
    static Double pyFloat(String s) {
        StringBuilder b = new StringBuilder();
        String t = pyStrip(s);
        for (int i = 0; i < t.length(); ) {
            int cp = t.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER) b.append((char) ('0' + Character.digit(cp, 10)));
            else b.appendCodePoint(cp);
        }
        String n = b.toString();
        if (!PY_FLOAT.matcher(n).matches()) return null;
        String lowerN = lower(n).replace("+", "");
        if (lowerN.endsWith("nan")) return Double.NaN;
        if (lowerN.endsWith("inf") || lowerN.endsWith("infinity")) {
            return lowerN.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        return Double.parseDouble(n.replace("_", ""));
    }

    /** openpyxl.utils.datetime.from_excel: time for 0 <= v < 1, else datetime. */
    static Object fromExcel(double v, boolean date1904) {
        double dayPart = Math.floor(v);
        double fraction = v - dayPart;
        long ms = (long) Math.rint(fraction * 86_400_000.0);
        if (v >= 0 && v < 1 && ms < 86_400_000L) return LocalTime.ofNanoOfDay(ms * 1_000_000L);
        LocalDateTime epoch = date1904 ? LocalDateTime.of(1904, 1, 1, 0, 0) : LocalDateTime.of(1899, 12, 30, 0, 0);
        if (v > 0 && v < 60 && !date1904) dayPart += 1;
        return epoch.plusDays((long) dayPart).plus(ms, ChronoUnit.MILLIS);
    }
}
