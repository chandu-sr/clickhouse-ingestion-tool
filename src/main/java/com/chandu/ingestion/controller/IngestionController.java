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
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Controller
public class IngestionController {

    private final Map<String, File> tempFiles = new HashMap<>();

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("title", "ClickHouse ↔ Flat File Ingestion Tool");
        return "index";
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

                model.addAttribute("headers", headers);
                model.addAttribute("fileName", fileName);
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

                    // Check if value is numeric (don't quote numbers)
                    if (isNumeric(val)) {
                        values.append(val);
                    } else {
                        values.append("'").append(val.replace("'", "\\'")).append("'");
                    }

                    if (i < selectedColumns.size() - 1) values.append(", ");
                }
                values.append("),\n");
                rowCount++;
            }

            if (values.length() > 0) values.setLength(values.length() - 2); // remove last comma and newline

            String columnsStr = String.join(", ", selectedColumns);
            String sql = "INSERT INTO users (" + columnsStr + ") VALUES " + values.toString() + ";";

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
                redirectAttributes.addFlashAttribute("message", "✅ Inserted " + rowCount + " records into ClickHouse!");
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

    // Helper method to detect if string is numeric
    private boolean isNumeric(String str) {
        return str != null && str.matches("\\d+(\\.\\d+)?");
    }
}
