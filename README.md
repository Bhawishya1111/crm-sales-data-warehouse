# CRM Sales Opportunities Data Warehouse

```
sql/schema.sql       table definitions (re-runnable; ETL_Main executes it)
sql/warehouse.sql    full export: schema + loaded data (import with: mysql -u root -p < sql/warehouse.sql)
etl/                 CSVParser, XMLParser, DataTransformer, MySQLLoader, ETL_Main (Java 17+)
web/queryEngine.php  PHP middleware (PDO, whitelisted queries, JSON out)
web/index.html       front end (reports + analyses)
```

## Run it
1. **ETL** (needs MySQL Connector/J jar, MySQL running):
   `cd etl && DB_USER=root DB_PASS=yourpass ./build_and_run.sh /path/to/mysql-connector-j.jar /path/to/Dataset_CRM_Sales_Opportunities`
   Or skip the ETL and import `sql/warehouse.sql`.
2. **Web**: copy `web/` into Apache's htdocs (XAMPP: `htdocs/crm/`), set credentials via env vars
   `DB_USER`/`DB_PASS` or edit the `$cfg` array at the top of `queryEngine.php`, then open `http://localhost/crm/index.html`.

## Design notes
- Natural keys (account, product, sales_agent, opportunity_id) with foreign keys between all tables.
- `data_dictionary.csv` is never read by the ETL.

## Transformations (results from the actual run)
| Rule | Rows affected |
|---|---|
| accounts.sector standardised (trim, lower-case, fix "technolgy" typo, Title Case) | 12 |
| accounts.office_location typo "Philipines" fixed | 1 |
| pipeline.product "GTXPro" normalised to "GTX Pro" (matches products.csv, needed for the foreign key) | 1,480 |
| Missing pipeline.account -> NULL (Prospecting/Engaging deals with no account yet) | 1,425 |
| Missing engage_date -> NULL (Prospecting) / close_date and close_value -> NULL (still-open deals) | 500 / 2,089 |
| Blank mandatory text -> "Unknown"; Lost deal with blank value -> 0; blank parent company -> NULL | rules in place, 0 hits |

Open deals keep NULL values (not 0) so they don't distort averages.

## Analysis choices to mention in your write-up
- Sales Opportunity Analysis defaults to **Won** deals; a dropdown adds lost deals (value 0) for comparison.
- Revenue is in USD millions (per the data dictionary).

## Extra features beyond the brief
- **Dashboard view**: headline numbers (accounts, win rate, won value, top product/sector) via a new `overview` query.
- **Search**: the Sales Opportunities report can be filtered by account or sales agent name.
- **Sortable columns**: click any column header on the opportunities table to sort by it.
- **CSV export**: every report/analysis has an "Export CSV" button (`&format=csv` on any query).
- Redesigned front end: dark sidebar navigation, monospace figures for scannable numbers, minimal flat styling instead of card/shadow defaults.
