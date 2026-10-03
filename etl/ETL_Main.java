import java.nio.file.*;
import java.util.*;

/**
 * Usage: java -cp ".:mysql-connector-j.jar" ETL_Main <dataDir> <schema.sql> [jdbcUrl]
 * Credentials come from env vars DB_USER (default root) and DB_PASS (default empty).
 * data_dictionary.csv is intentionally NOT read: it only documents the tables.
 */
public class ETL_Main {
    public static void main(String[] args) throws Exception {
        Path dataDir = Paths.get(args.length > 0 ? args[0] : "Dataset_CRM_Sales_Opportunities");
        Path schema = Paths.get(args.length > 1 ? args[1] : "../sql/schema.sql");
        String url = args.length > 2 ? args[2] : "jdbc:mysql://localhost:3306/?useUnicode=true&characterEncoding=UTF-8";
        String user = Optional.ofNullable(System.getenv("DB_USER")).orElse("root");
        String pass = Optional.ofNullable(System.getenv("DB_PASS")).orElse("");

        // ---- EXTRACT ----
        var rawAccounts = XMLParser.parse(dataDir.resolve("accounts.xml"));
        var rawTeams    = XMLParser.parse(dataDir.resolve("sales_teams.xml"));
        var rawProducts = CSVParser.parse(dataDir.resolve("products.csv"));
        var rawPipeline = CSVParser.parse(dataDir.resolve("sales_pipeline.csv"));
        System.out.printf("Extracted: accounts=%d teams=%d products=%d pipeline=%d%n",
                rawAccounts.size(), rawTeams.size(), rawProducts.size(), rawPipeline.size());

        // ---- TRANSFORM ----
        DataTransformer t = new DataTransformer();
        var accounts = t.accounts(rawAccounts);
        var products = t.products(rawProducts);
        var teams    = t.salesTeams(rawTeams);
        var pipeline = t.pipeline(rawPipeline, products, teams, accounts);
        System.out.println("Transformation log:");
        t.log.forEach((k, v) -> System.out.printf("  %-45s %d%n", k, v));

        // ---- LOAD (parents before children) ----
        try (MySQLLoader db = new MySQLLoader(url, user, pass)) {
            db.createSchema(schema);
            db.loadAccounts(accounts);
            db.loadProducts(products);
            db.loadSalesTeams(teams);
            db.loadPipeline(pipeline);
            System.out.printf("Loaded: accounts=%d products=%d sales_teams=%d sales_pipeline=%d%n",
                    db.count("accounts"), db.count("products"), db.count("sales_teams"), db.count("sales_pipeline"));
        }
    }
}
