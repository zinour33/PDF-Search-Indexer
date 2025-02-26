# PDF-Search-Word
🚀 PDF Search Indexer is a Java-based tool that scans directories for PDF files, extracts text using Apache PDFBox, and stores indexed content in a SQLite FTS5 database for fast searches. It supports multithreading, efficient error handling, and full-text search.

## 🎯 Features
✅ **Multi-threaded PDF Processing** - Fast indexing with concurrent workers  
✅ **Full-Text Search** - Query PDFs instantly using SQLite FTS5  
✅ **Robust Error Handling** - Skips inaccessible files & logs issues  
✅ **Automatic File Skipping** - Avoids duplicate indexing of already processed PDFs  
✅ **Efficient Database Writes** - Uses a queue-based batch insertion system  

---

## 🛠️ Installation & Usage

### **🔹 1. Clone the Repository**
```sh
git clone https://github.com/zinour33/PDF-Search-Indexer.git
cd PDF-Search-Indexer


 2. Compile the Java Code

javac -cp ".;lib/*" PDFSearch.java

3. Run the Indexer & Search PDFs

java -cp ".;lib/*" PDFSearch "C:\path\to\pdfs" "search_term"

Example: Searching for "invoice" in C:\Documents\PDFs

java -cp ".;lib/*" PDFSearch "C:\Documents\PDFs" "invoice"

📂 Directory Structure

📁 PDF-Search-Indexer
 ├── 📜 PDFSearch.java        # Main indexing and search logic
 ├── 📜 README.md             # Project documentation
 ├── 📜 .gitignore            # Files to ignore in Git
 ├── 📜 pdf_search.db         # SQLite Database (Auto-generated)
 ├── 📂 lib                   # External dependencies (PDFBox, SQLite)

🛠️ Dependencies
This project requires:

Java 11+
Apache PDFBox (for PDF processing)
SQLite JDBC (for database storage)

⚡ Performance Tips

🔹 Increase THREAD_COUNT in PDFSearch.java for more parallelism
🔹 Run as Administrator if facing permission issues




