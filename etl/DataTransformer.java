import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * TRANSFORM: cleans raw rows and converts them into typed records.
 *  - Missing values (blank / "NA" / "null" / "n/a"): text -> "Unknown" where a value is mandatory,
 *    otherwise NULL; numbers and dates -> NULL; Lost deals with no value -> 0.
 *  - accounts.sector standardised (trim, lower-case, typo fixes, Title Case).
 *  - Extra fixes needed for referential integrity: product names ("GTXPro" -> "GTX Pro"),
 *    obvious country typo ("Philipines"), parent companies that don't exist -> NULL.
 */
public class DataTransformer {
    public record Account(String account, String sector, Integer year, BigDecimal revenue,
                          Integer employees, String location, String parent) {}
    public record Product(String product, String series, BigDecimal price) {}
    public record SalesTeam(String agent, String manager, String office) {}
    public record Opportunity(String id, String agent, String product, String account, String stage,
                              LocalDate engage, LocalDate close, BigDecimal value) {}

    /** Counters printed at the end so the cleaning is auditable. */
    public final Map<String, Integer> log = new TreeMap<>();
    private void count(String k) { log.merge(k, 1, Integer::sum); }

    private static final Map<String, String> SECTOR_FIXES = Map.of(
            "technolgy", "technology", "tecnology", "technology", "technoloy", "technology",
            "telecom", "telecommunications", "telecomunications", "telecommunications",
            "telecommunication", "telecommunications");
    private static final Map<String, String> LOCATION_FIXES = Map.of("philipines", "Philippines");

    static boolean isMissing(String s) {
        if (s == null) return true;
        String t = s.trim();
        return t.isEmpty() || t.equalsIgnoreCase("na") || t.equalsIgnoreCase("n/a")
                || t.equalsIgnoreCase("null") || t.equals("-");
    }

    private String text(String v, String field, String fallback) {
        if (isMissing(v)) { count("missing " + field + (fallback == null ? " -> NULL" : " -> " + fallback)); return fallback; }
        return v.trim().replaceAll("\\s+", " ");
    }

    public static String standardiseSector(String raw) {
        String s = raw.trim().toLowerCase().replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").trim();
        s = SECTOR_FIXES.getOrDefault(s, s);
        StringBuilder sb = new StringBuilder();
        for (String w : s.split(" "))
            if (!w.isEmpty()) sb.append(sb.length() > 0 ? " " : "").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        return sb.toString();
    }

    private BigDecimal decimal(String v, String field) {
        if (isMissing(v)) { count("missing " + field + " -> NULL"); return null; }
        try { return new BigDecimal(v.trim().replace(",", "")); }
        catch (NumberFormatException e) { count("unparseable " + field + " -> NULL"); return null; }
    }

    private Integer integer(String v, String field) {
        BigDecimal d = decimal(v, field);
        return d == null ? null : d.intValue();
    }

    private LocalDate date(String v, String field) {
        if (isMissing(v)) { count("missing " + field + " -> NULL"); return null; }
        try { return LocalDate.parse(v.trim()); }
        catch (DateTimeParseException e) { count("unparseable " + field + " -> NULL"); return null; }
    }

    public List<Account> accounts(List<Map<String, String>> raw) {
        Set<String> names = new HashSet<>();
        for (Map<String, String> r : raw) names.add(r.getOrDefault("account", "").trim());
        Map<String, Account> out = new LinkedHashMap<>();
        for (Map<String, String> r : raw) {
            String name = text(r.get("account"), "accounts.account", null);
            if (name == null || out.containsKey(name)) { count("skipped duplicate/blank account row"); continue; }
            String sectorRaw = r.get("sector");
            String sector = isMissing(sectorRaw) ? text(sectorRaw, "accounts.sector", "Unknown") : standardiseSector(sectorRaw);
            if (!isMissing(sectorRaw) && !sector.equalsIgnoreCase(sectorRaw.trim())) count("sector standardised");
            String loc = text(r.get("office_location"), "accounts.office_location", "Unknown");
            String fixed = LOCATION_FIXES.get(loc.toLowerCase());
            if (fixed != null) { loc = fixed; count("office_location typo fixed"); }
            String parent = isMissing(r.get("subsidiary_of")) ? null : r.get("subsidiary_of").trim();
            if (parent != null && !names.contains(parent)) { count("unknown parent company -> NULL"); parent = null; }
            out.put(name, new Account(name, sector, integer(r.get("year_established"), "accounts.year_established"),
                    decimal(r.get("revenue"), "accounts.revenue"), integer(r.get("employees"), "accounts.employees"),
                    loc, parent));
        }
        return new ArrayList<>(out.values());
    }

    public List<Product> products(List<Map<String, String>> raw) {
        List<Product> out = new ArrayList<>();
        for (Map<String, String> r : raw) {
            String p = text(r.get("product"), "products.product", null);
            if (p == null) continue;
            BigDecimal price = decimal(r.get("sales_price"), "products.sales_price");
            out.add(new Product(p, text(r.get("series"), "products.series", "Unknown"),
                    price == null ? BigDecimal.ZERO : price));
        }
        return out;
    }

    public List<SalesTeam> salesTeams(List<Map<String, String>> raw) {
        List<SalesTeam> out = new ArrayList<>();
        for (Map<String, String> r : raw) {
            String a = text(r.get("sales_agent"), "sales_teams.sales_agent", null);
            if (a == null) continue;
            out.add(new SalesTeam(a, text(r.get("manager"), "sales_teams.manager", "Unknown"),
                    text(r.get("regional_office"), "sales_teams.regional_office", "Unknown")));
        }
        return out;
    }

    private static String key(String s) { return s.toLowerCase().replaceAll("[^a-z0-9]", ""); }

    public List<Opportunity> pipeline(List<Map<String, String>> raw, List<Product> products,
                                      List<SalesTeam> teams, List<Account> accounts) {
        Map<String, String> productByKey = new HashMap<>();
        for (Product p : products) productByKey.put(key(p.product()), p.product());
        Set<String> agents = new HashSet<>(), accts = new HashSet<>();
        teams.forEach(t -> agents.add(t.agent()));
        accounts.forEach(a -> accts.add(a.account()));
        Set<String> seen = new HashSet<>();
        List<Opportunity> out = new ArrayList<>();
        for (Map<String, String> r : raw) {
            String id = text(r.get("opportunity_id"), "pipeline.opportunity_id", null);
            if (id == null || !seen.add(id)) { count("skipped duplicate/blank opportunity"); continue; }
            String agent = text(r.get("sales_agent"), "pipeline.sales_agent", null);
            String prodRaw = text(r.get("product"), "pipeline.product", null);
            String prod = prodRaw == null ? null : productByKey.get(key(prodRaw));
            if (prod != null && !prod.equals(prodRaw)) count("product name normalised");
            if (agent == null || !agents.contains(agent) || prod == null) { count("skipped opportunity with unknown agent/product"); continue; }
            String acct = text(r.get("account"), "pipeline.account", null);
            if (acct != null && !accts.contains(acct)) { count("unknown account -> NULL"); acct = null; }
            String stage = text(r.get("deal_stage"), "pipeline.deal_stage", null);
            if (stage == null) { count("skipped opportunity with no stage"); continue; }
            stage = stage.substring(0, 1).toUpperCase() + stage.substring(1).toLowerCase();
            BigDecimal value = decimal(r.get("close_value"), "pipeline.close_value");
            if (value == null && stage.equals("Lost")) { value = BigDecimal.ZERO; count("Lost deal value -> 0"); }
            out.add(new Opportunity(id, agent, prod, acct, stage, date(r.get("engage_date"), "pipeline.engage_date"),
                    date(r.get("close_date"), "pipeline.close_date"), value));
        }
        return out;
    }
}
