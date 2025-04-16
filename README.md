# ClickHouse ↔ Flat File Ingestion Tool

## 👨‍💻 Developed by: Chandu SR

This is a full-stack Spring Boot-based web application that allows users to ingest data between **CSV flat files** and **ClickHouse databases**. It supports one-way ingestion logic (CSV → ClickHouse) with authentication, data preview, and column selection.

---

## 🚀 Features

- Upload CSV files via web interface
- Preview column headers before ingestion
- Select which columns to insert into ClickHouse
- Insert data via dynamically built SQL
- Record count summary after ingestion
- Type-safe data formatting (e.g., quotes only for strings)
- ClickHouse integration via HTTP API
- JWT or Basic Auth support (defaults to Basic)

---

## 🧰 Tech Stack

- Java 17+
- Spring Boot
- Thymeleaf (for UI)
- Apache Commons CSV
- ClickHouse
- HTML/CSS

---

## 📦 Setup Instructions

```bash
# Clone the repository
git clone https://github.com/your-username/ingestion-tool.git
cd ingestion-tool

# Start ClickHouse server (using Docker)
docker run -d --name clickhouse-server -p 8123:8123 clickhouse/clickhouse-server

# Start Spring Boot app
mvn spring-boot:run

# Open in browser
http://localhost:8080
