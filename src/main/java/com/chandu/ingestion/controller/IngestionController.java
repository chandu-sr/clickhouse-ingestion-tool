package com.chandu.ingestion.controller;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import jakarta.servlet.http.HttpServletResponse;

@Controller
public class IngestionController {

    private final Map<String, File> tempFiles = new HashMap<>();
    private final Map<String, String> tempTableNames = new HashMap<>();

    @GetMapping("/view-data")
    public String viewData(@RequestParam(value = "table", defaultValue = "users") String tableName, Model model) {
        try {
            String clickhouseUrl = "http://localhost:8123";

            // Get list of all tables
            List<String> tables = getAllTables(clickhouseUrl);
            model.addAttribute("tables", tables);

            // Query the selected table
            String query = "SELECT * FROM default." + quoteIdentifier(tableName) + " LIMIT 100";
            URL url = new URL(clickhouseUrl + "/?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("default:".getBytes()));

            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                List<String[]> rows = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] cols = line.split("\t");
                        rows.add(cols);
                    }
                }
                model.addAttribute("dataRows", rows);
                model.addAttribute("selectedTable", tableName);
            } else {
                model.addAttribute("message", "❌ Failed to fetch data from table '" + tableName + "': " + responseCode);
            }
        } catch (IOException e) {
            model.addAttribute("message", "❌ Error fetching data: " + e.getMessage());
        }

        return "index";
    }

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("title", "ClickHouse ↔ Flat File Ingestion Tool");
        try {
            List<String> tables = getAllTables("http://localhost:8123");
            model.addAttribute("tables", tables);
        } catch (IOException e) {
            // Ignore errors, tables list will be empty
        }
        return "index";
    }

    @GetMapping("/export-csv")
    public void exportCsv(@RequestParam(value = "table", defaultValue = "users") String tableName, HttpServletResponse response) {
        String clickhouseUrl = "http://localhost:8123";
        String query = "SELECT * FROM default." + quoteIdentifier(tableName) + " FORMAT CSVWithNames";
        try {
            URL url = new URL(clickhouseUrl + "/?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("default:".getBytes()));

            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                response.setContentType("text/csv");
                response.setHeader("Content-Disposition", "attachment; filename=" + tableName + "_export.csv");
                try (InputStream input = connection.getInputStream(); OutputStream output = response.getOutputStream()) {
                    input.transferTo(output);
                }
            } else {
                response.sendError(responseCode, "Failed to export CSV from table '" + tableName + "'");
            }
        } catch (IOException e) {
            try {
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error exporting CSV: " + e.getMessage());
            } catch (IOException ignored) {
            }
        }
    }

    @PostMapping("/delete-data")
    public String deleteData(@RequestParam(value = "table", defaultValue = "users") String tableName, RedirectAttributes redirectAttributes) {
        String clickhouseUrl = "http://localhost:8123";
        String sql = "TRUNCATE TABLE default." + quoteIdentifier(tableName) + ";";

        try {
            URL url = new URL(clickhouseUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("default:".getBytes()));
            connection.setRequestProperty("Content-Type", "text/plain");

            try (OutputStream os = connection.getOutputStream()) {
                os.write(sql.getBytes());
                os.flush();
            }

            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                redirectAttributes.addFlashAttribute("message", "✅ All data has been deleted from table '" + tableName + "'.");
            } else {
                String err = new BufferedReader(new InputStreamReader(connection.getErrorStream()))
                        .lines().reduce("", (a, b) -> a + "\n" + b);
                redirectAttributes.addFlashAttribute("message", "❌ Delete failed: " + err);
            }
        } catch (IOException e) {
            redirectAttributes.addFlashAttribute("message", "❌ Error deleting data: " + e.getMessage());
        }

        return "redirect:/view-data?table=" + URLEncoder.encode(tableName, StandardCharsets.UTF_8);
    }

    @PostMapping("/preview-columns")
    public String previewColumns(
            @RequestParam("direction") String direction,
            @RequestParam("file") MultipartFile file,
            @RequestParam("clickhouseUrl") String clickhouseUrl,
            @RequestParam("jwt") String jwt,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        try {
            // Save uploaded file to temp location
            String fileName = UUID.randomUUID().toString() + "_" + StringUtils.cleanPath(file.getOriginalFilename());
            File tempFile = Files.createTempFile("upload_", "_" + fileName).toFile();
            file.transferTo(tempFile);
            tempFiles.put(fileName, tempFile);

            // Read CSV headers
            try (Reader reader = new FileReader(tempFile);
                 CSVParser parser = new CSVParser(reader, CSVFormat.DEFAULT.withFirstRecordAsHeader())) {
                List<String> headers = new ArrayList<>(parser.getHeaderMap().keySet());

                String defaultTableName = generateUniqueTableName(file.getOriginalFilename());
                tempTableNames.put(fileName, defaultTableName);

                model.addAttribute("headers", headers);
                model.addAttribute("fileName", fileName);
                model.addAttribute("tableName", defaultTableName);
                model.addAttribute("direction", direction);
                model.addAttribute("clickhouseUrl", clickhouseUrl);
                model.addAttribute("jwt", jwt);
            }

        } catch (IOException e) {
            redirectAttributes.addFlashAttribute("message", "❌ Error reading CSV: " + e.getMessage());
            return "redirect:/";
        }

        return "index";
    }

    @PostMapping("/ingest")
    public String handleIngestion(
            @RequestParam("direction") String direction,
            @RequestParam("clickhouseUrl") String clickhouseUrl,
            @RequestParam("jwt") String jwt,
            @RequestParam("fileName") String fileName,
            @RequestParam("tableName") String tableName,
            @RequestParam(value = "selectedColumns", required = false) List<String> selectedColumns,
            RedirectAttributes redirectAttributes
    ) {
        File file = tempFiles.get(fileName);
        if (file == null || selectedColumns == null || selectedColumns.isEmpty()) {
            redirectAttributes.addFlashAttribute("message", "❌ No file or columns selected.");
            return "redirect:/";
        }

        try (Reader reader = new FileReader(file);
             CSVParser parser = new CSVParser(reader, CSVFormat.DEFAULT.withFirstRecordAsHeader())) {

            StringBuilder values = new StringBuilder();
            int rowCount = 0;

            for (CSVRecord record : parser) {
                values.append("(");
                for (int i = 0; i < selectedColumns.size(); i++) {
                    String col = selectedColumns.get(i);
                    String val = record.get(col).trim();

                    // Always quote as string since table columns are String type
                    values.append("'").append(val.replace("'", "\\'")).append("'");

                    if (i < selectedColumns.size() - 1) values.append(", ");
                }
                values.append("),\n");
                rowCount++;
            }

            if (values.length() > 0) values.setLength(values.length() - 2); // remove last comma and newline

            String sanitizedTableName = sanitizeTableName(tableName);
            createClickhouseTable(clickhouseUrl, sanitizedTableName, selectedColumns);

            String columnsStr = String.join(", ", selectedColumns.stream().map(this::quoteIdentifier).toList());
            String sql = "INSERT INTO default." + quoteIdentifier(sanitizedTableName) + " (" + columnsStr + ") VALUES " + values.toString() + ";";

            // Send to ClickHouse
            URL url = new URL(clickhouseUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);

            // Use Basic Auth
            String basicAuth = "Basic " + Base64.getEncoder().encodeToString("default:".getBytes());
            connection.setRequestProperty("Authorization", basicAuth);
            connection.setRequestProperty("Content-Type", "text/plain");

            try (OutputStream os = connection.getOutputStream()) {
                os.write(sql.getBytes());
                os.flush();
            }

            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                redirectAttributes.addFlashAttribute("message", "✅ Created table '" + sanitizedTableName + "' and inserted " + rowCount + " records into ClickHouse!");
            } else {
                String err = new BufferedReader(new InputStreamReader(connection.getErrorStream()))
                        .lines().reduce("", (a, b) -> a + "\n" + b);
                redirectAttributes.addFlashAttribute("message", "❌ Insert failed: " + err);
            }

        } catch (IOException e) {
            redirectAttributes.addFlashAttribute("message", "❌ Error inserting: " + e.getMessage());
        }

        return "redirect:/";
    }

    private void createClickhouseTable(String clickhouseUrl, String tableName, List<String> columns) throws IOException {
        String columnDefs = String.join(", ", columns.stream()
                .map(this::quoteIdentifier)
                .map(col -> col + " String")
                .toList());

        String createSql = "CREATE TABLE IF NOT EXISTS default." + quoteIdentifier(tableName)
                + " (" + columnDefs + ") ENGINE = MergeTree() ORDER BY tuple();";

        executeClickhouseSql(clickhouseUrl, createSql);
    }

    private void executeClickhouseSql(String clickhouseUrl, String sql) throws IOException {
        URL url = new URL(clickhouseUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("default:".getBytes()));
        connection.setRequestProperty("Content-Type", "text/plain");

        try (OutputStream os = connection.getOutputStream()) {
            os.write(sql.getBytes());
            os.flush();
        }

        int responseCode = connection.getResponseCode();
        if (responseCode != 200) {
            String err = new BufferedReader(new InputStreamReader(connection.getErrorStream()))
                    .lines().reduce("", (a, b) -> a + "\n" + b);
            throw new IOException("ClickHouse SQL failed: " + responseCode + " - " + err);
        }
    }

    private List<String> getAllTables(String clickhouseUrl) throws IOException {
        String query = "SELECT name FROM system.tables WHERE database = 'default' ORDER BY name";
        URL url = new URL(clickhouseUrl + "/?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("default:".getBytes()));

        List<String> tables = new ArrayList<>();
        int responseCode = connection.getResponseCode();
        if (responseCode == 200) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        tables.add(line.trim());
                    }
                }
            }
        }
        return tables;
    }

    private String generateUniqueTableName(String originalFileName) {
        String baseName = StringUtils.cleanPath(originalFileName == null ? "csv" : originalFileName)
                .replaceAll("\\.csv$", "")
                .replaceAll("\\s+", "_")
                .replaceAll("[^A-Za-z0-9_]+", "_")
                .toLowerCase();
        if (baseName.isEmpty()) {
            baseName = "csv";
        }
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return "csv_" + baseName + "_" + suffix;
    }

    private String sanitizeTableName(String name) {
        if (name == null || name.isBlank()) {
            return "csv_table_" + UUID.randomUUID().toString().substring(0, 8);
        }
        String sanitized = name.replaceAll("\\s+", "_")
                .replaceAll("[^A-Za-z0-9_]+", "_")
                .toLowerCase();
        if (sanitized.matches("^[0-9].*")) {
            sanitized = "t_" + sanitized;
        }
        return sanitized;
    }

    // Helper method to quote ClickHouse identifiers
    private String quoteIdentifier(String id) {
        if (id == null) return "";
        // Quote if contains spaces, special chars, or starts with digit
        if (id.contains(" ") || id.contains("-") || id.contains(".") || id.matches("^\\d.*")) {
            return "`" + id.replace("`", "``") + "`";
        }
        return id;
    }
}
