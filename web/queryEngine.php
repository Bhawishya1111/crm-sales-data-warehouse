<?php
/**
 * Query engine: middleware between the front end and the MySQL data warehouse.
 * GET queryEngine.php?query=<overview|products|opportunities|establishment|opportunity_analysis>
 *
 * Shared:      &format=csv        streams the result as a CSV download instead of JSON
 * opportunities: &stage=Won|Lost|All  &product=<name>  &search=<text on account/agent>
 *                &sort=<opportunity_id|account|sales_agent|product|close_date|close_value>
 *                &dir=asc|desc  &page=<n>  &page_size=<5-200>
 * opportunity_analysis: &stage=Won|ClosedAll
 *
 * Security: every value that reaches SQL is bound through a prepared statement.
 * Only the query names and column names listed in the whitelists below can be
 * selected by the caller - nothing from $_GET is ever concatenated into SQL.
 */
header('Content-Type: application/json; charset=utf-8');

$cfg = [
    'host' => getenv('DB_HOST') ?: 'localhost',
    'name' => getenv('DB_NAME') ?: 'crm_warehouse',
    'user' => getenv('DB_USER') ?: 'root',
    'pass' => getenv('DB_PASS') ?: '',
];

function fail(int $code, string $msg): void {
    http_response_code($code);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode(['error' => $msg]);
    exit;
}

try {
    $pdo = new PDO("mysql:host={$cfg['host']};dbname={$cfg['name']};charset=utf8mb4", $cfg['user'], $cfg['pass'], [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
        PDO::ATTR_EMULATE_PREPARES => false,
    ]);
} catch (PDOException $e) {
    fail(500, 'Cannot connect to the data warehouse. Check the credentials at the top of queryEngine.php.');
}

function run(PDO $pdo, string $sql, array $params = []): array {
    $st = $pdo->prepare($sql);
    $st->execute($params);
    return $st->fetchAll();
}

$query = $_GET['query'] ?? '';
$meta = [];

try {
    switch ($query) {
        case 'overview':   // extra: headline numbers for a landing dashboard
            $accounts = run($pdo, "SELECT COUNT(*) c FROM accounts")[0]['c'];
            $products = run($pdo, "SELECT COUNT(*) c FROM products")[0]['c'];
            $stageCounts = run($pdo, "SELECT deal_stage, COUNT(*) c, COALESCE(SUM(close_value),0) v FROM sales_pipeline GROUP BY deal_stage");
            $won = 0; $lost = 0; $wonValue = 0;
            foreach ($stageCounts as $r) {
                if ($r['deal_stage'] === 'Won') { $won = (int)$r['c']; $wonValue = (float)$r['v']; }
                if ($r['deal_stage'] === 'Lost') $lost = (int)$r['c'];
            }
            $winRate = ($won + $lost) > 0 ? round($won / ($won + $lost) * 100, 1) : null;
            $topProduct = run($pdo, "SELECT sp.product, SUM(sp.close_value) v FROM sales_pipeline sp
                                     WHERE sp.deal_stage = 'Won' GROUP BY sp.product ORDER BY v DESC LIMIT 1")[0] ?? null;
            $topSector = run($pdo, "SELECT sector, SUM(revenue) v FROM accounts GROUP BY sector ORDER BY v DESC LIMIT 1")[0] ?? null;
            $rows = [[
                'accounts' => (int)$accounts, 'products' => (int)$products,
                'won_opportunities' => $won, 'lost_opportunities' => $lost,
                'win_rate_pct' => $winRate, 'won_value' => $wonValue,
                'top_product' => $topProduct['product'] ?? null,
                'top_sector' => $topSector['sector'] ?? null,
            ]];
            break;

        case 'products':   // Products Report: all product information
            $rows = run($pdo, "SELECT product, series, sales_price FROM products ORDER BY series, sales_price");
            break;

        case 'opportunities':   // Sales Opportunities Report: won/lost with values and products
            $stage = $_GET['stage'] ?? 'All';
            $product = $_GET['product'] ?? '';
            $search = trim($_GET['search'] ?? '');
            $size = min(max((int)($_GET['page_size'] ?? 25), 5), 200);
            $page = max((int)($_GET['page'] ?? 1), 1);

            $sortable = ['opportunity_id', 'account', 'sales_agent', 'product', 'close_date', 'close_value'];
            $sort = in_array($_GET['sort'] ?? '', $sortable, true) ? $_GET['sort'] : 'close_date';
            $dir = (($_GET['dir'] ?? 'desc') === 'asc') ? 'ASC' : 'DESC';

            $where = ["sp.deal_stage IN ('Won','Lost')"];
            $params = [];
            if (in_array($stage, ['Won', 'Lost'], true)) { $where[] = 'sp.deal_stage = ?'; $params[] = $stage; }
            if ($product !== '') { $where[] = 'sp.product = ?'; $params[] = $product; }
            if ($search !== '') { $where[] = '(sp.account LIKE ? OR sp.sales_agent LIKE ?)'; $params[] = "%$search%"; $params[] = "%$search%"; }
            $w = implode(' AND ', $where);

            $total = (int)run($pdo, "SELECT COUNT(*) c FROM sales_pipeline sp WHERE $w", $params)[0]['c'];
            $sum = (float)run($pdo, "SELECT COALESCE(SUM(sp.close_value),0) s FROM sales_pipeline sp WHERE $w", $params)[0]['s'];
            $offset = ($page - 1) * $size;
            $rows = run($pdo, "SELECT sp.opportunity_id, sp.deal_stage, sp.product, p.series, p.sales_price,
                                      sp.close_value, sp.account, sp.sales_agent, sp.close_date
                               FROM sales_pipeline sp JOIN products p ON p.product = sp.product
                               WHERE $w ORDER BY sp.$sort $dir, sp.opportunity_id
                               LIMIT $size OFFSET $offset", $params);
            $meta = ['total' => $total, 'page' => $page, 'page_size' => $size,
                     'pages' => max((int)ceil($total / $size), 1), 'sum_close_value' => $sum,
                     'sort' => $sort, 'dir' => strtolower($dir), 'search' => $search,
                     'products' => array_column(run($pdo, "SELECT product FROM products ORDER BY product"), 'product')];
            break;

        case 'establishment':   // Establishment Year Revenue Analysis
            $rows = run($pdo, "SELECT year_established, COUNT(*) AS accounts, SUM(revenue) AS total_revenue
                               FROM accounts WHERE year_established IS NOT NULL
                               GROUP BY year_established ORDER BY year_established");
            $meta = ['unit' => 'USD millions'];
            break;

        case 'opportunity_analysis':   // total & average opportunity value by product
            $stage = ($_GET['stage'] ?? 'Won') === 'ClosedAll' ? ['Won', 'Lost'] : ['Won'];
            $in = implode(',', array_fill(0, count($stage), '?'));
            $rows = run($pdo, "SELECT sp.product, p.series, COUNT(*) AS opportunities,
                                      SUM(sp.close_value) AS total_value, ROUND(AVG(sp.close_value), 2) AS average_value
                               FROM sales_pipeline sp JOIN products p ON p.product = sp.product
                               WHERE sp.deal_stage IN ($in) GROUP BY sp.product, p.series
                               ORDER BY total_value DESC", $stage);
            $meta = ['basis' => implode(' + ', $stage) . ' opportunities'];
            break;

        default:
            fail(400, 'Unknown query. Use overview, products, opportunities, establishment or opportunity_analysis.');
    }
} catch (PDOException $e) {
    fail(500, 'Query failed.');
}

// DECIMAL columns arrive from PDO as strings; cast the numeric ones back to numbers.
// IDs and dates stay as strings, so this is done per-column rather than with a blanket flag.
$numeric = ['sales_price', 'close_value', 'total_value', 'average_value', 'total_revenue',
            'year_established', 'accounts', 'opportunities', 'products', 'won_opportunities',
            'lost_opportunities', 'win_rate_pct', 'won_value'];
foreach ($rows as &$r) {
    foreach ($numeric as $c) if (isset($r[$c])) $r[$c] = $r[$c] + 0;
}
unset($r);

// Extra: any endpoint can be downloaded as CSV instead of viewed as JSON.
if (($_GET['format'] ?? '') === 'csv') {
    $columns = $rows ? array_keys($rows[0]) : [];
    header('Content-Type: text/csv; charset=utf-8');
    header('Content-Disposition: attachment; filename="' . preg_replace('/[^a-z0-9_]/', '', $query) . '.csv"');
    $out = fopen('php://output', 'w');
    fputcsv($out, $columns);
    foreach ($rows as $r) fputcsv($out, $r);
    fclose($out);
    exit;
}

echo json_encode(['columns' => $rows ? array_keys($rows[0]) : [], 'rows' => $rows, 'meta' => (object)$meta]);
