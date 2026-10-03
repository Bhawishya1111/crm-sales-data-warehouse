import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.List;

/** LOAD: creates the schema and batch-inserts the transformed records into MySQL. */
public class MySQLLoader implements AutoCloseable {
    private final Connection con;

    public MySQLLoader(String url, String user, String pass) throws SQLException {
        con = DriverManager.getConnection(url, user, pass);
        con.setAutoCommit(false);
    }

    /** Runs schema.sql (statements separated by ';'). */
    public void createSchema(Path script) throws IOException, SQLException {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readAllLines(script))
            if (!line.trim().startsWith("--")) sb.append(line.replaceAll("\\s--.*$", "")).append('\n');
        try (Statement st = con.createStatement()) {
            for (String sql : sb.toString().split(";"))
                if (!sql.isBlank()) st.execute(sql);
        }
        con.commit();
    }

    public void loadAccounts(List<DataTransformer.Account> rows) throws SQLException {
        try (Statement st = con.createStatement()) { st.execute("SET FOREIGN_KEY_CHECKS=0"); } // parents may load after children
        String sql = "INSERT INTO accounts (account,sector,year_established,revenue,employees,office_location,subsidiary_of) VALUES (?,?,?,?,?,?,?)";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (var a : rows) {
                ps.setString(1, a.account()); ps.setString(2, a.sector());
                ps.setObject(3, a.year()); ps.setBigDecimal(4, a.revenue()); ps.setObject(5, a.employees());
                ps.setString(6, a.location()); ps.setString(7, a.parent());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (Statement st = con.createStatement()) { st.execute("SET FOREIGN_KEY_CHECKS=1"); }
        con.commit();
    }

    public void loadProducts(List<DataTransformer.Product> rows) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO products (product,series,sales_price) VALUES (?,?,?)")) {
            for (var p : rows) { ps.setString(1, p.product()); ps.setString(2, p.series()); ps.setBigDecimal(3, p.price()); ps.addBatch(); }
            ps.executeBatch();
        }
        con.commit();
    }

    public void loadSalesTeams(List<DataTransformer.SalesTeam> rows) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO sales_teams (sales_agent,manager,regional_office) VALUES (?,?,?)")) {
            for (var t : rows) { ps.setString(1, t.agent()); ps.setString(2, t.manager()); ps.setString(3, t.office()); ps.addBatch(); }
            ps.executeBatch();
        }
        con.commit();
    }

    public void loadPipeline(List<DataTransformer.Opportunity> rows) throws SQLException {
        String sql = "INSERT INTO sales_pipeline (opportunity_id,sales_agent,product,account,deal_stage,engage_date,close_date,close_value) VALUES (?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            int n = 0;
            for (var o : rows) {
                ps.setString(1, o.id()); ps.setString(2, o.agent()); ps.setString(3, o.product());
                ps.setString(4, o.account()); ps.setString(5, o.stage());
                ps.setObject(6, o.engage()); ps.setObject(7, o.close()); ps.setBigDecimal(8, o.value());
                ps.addBatch();
                if (++n % 1000 == 0) ps.executeBatch();
            }
            ps.executeBatch();
        }
        con.commit();
    }

    public int count(String table) throws SQLException {
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next(); return rs.getInt(1);
        }
    }

    @Override public void close() throws SQLException { con.close(); }
}
