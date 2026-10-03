import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** EXTRACT: reads a CSV file into a list of header-keyed rows (handles quotes, BOM, CRLF). */
public class CSVParser {
    public static List<Map<String, String>> parse(Path file) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line = in.readLine();
            if (line == null) return rows;
            if (line.startsWith("\uFEFF")) line = line.substring(1);
            List<String> header = split(line);
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> f = split(line);
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < header.size(); i++)
                    row.put(header.get(i).trim(), i < f.size() ? f.get(i) : "");
                rows.add(row);
            }
        }
        return rows;
    }

    private static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { sb.append('"'); i++; }
                else if (c == '"') quoted = false;
                else sb.append(c);
            } else if (c == '"') quoted = true;
            else if (c == ',') { out.add(sb.toString()); sb.setLength(0); }
            else sb.append(c);
        }
        out.add(sb.toString());
        return out;
    }
}
