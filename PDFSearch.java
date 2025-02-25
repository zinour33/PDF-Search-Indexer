import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class PDFSearch {
    private static final String DB_NAME = "pdf_search.db";
    private static final int THREAD_COUNT = 6;
    private static final BlockingQueue<String> writeQueue = new LinkedBlockingQueue<>();
    private static volatile boolean indexingComplete = false;

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: java PDFSearch <search_directory> <word>");
            return;
        }

        String searchDirectory = args[0];
        String wordToSearch = args[1];

        createDatabase();

        CompletableFuture<List<String>> pdfFilesFuture = findPDFFilesAsync(searchDirectory);

        pdfFilesFuture.thenAccept(pdfFiles -> {
            if (pdfFiles.isEmpty()) {
                System.out.println("No PDF files found.");
                return;
            }

            Set<String> indexedFiles = getIndexedFiles();
            ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
            ExecutorService dbWriter = Executors.newSingleThreadExecutor();
            dbWriter.submit(PDFSearch::processWriteQueue);

            for (String pdfFile : pdfFiles) {
                if (!indexedFiles.contains(pdfFile)) {
                    executor.submit(() -> {
                        System.out.println("Indexing: " + pdfFile);
                        extractAndStorePDF(pdfFile);
                    });
                } else {
                    System.out.println("Skipping (Already Indexed): " + pdfFile);
                }
            }

            executor.shutdown();
            try {
                executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("Indexing interrupted.");
            }

            indexingComplete = true;
            dbWriter.shutdown();
            try {
                dbWriter.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("Database writing interrupted.");
            }

            System.out.println("\nSearching for '" + wordToSearch + "'...\n");
            searchInDatabase(wordToSearch);
            System.out.println("Number of PDFs scanned: " + pdfFiles.size());
        }).join();
    }

    private static void createDatabase() {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_NAME);
             Statement stmt = conn.createStatement()) {

            stmt.execute("PRAGMA journal_mode=WAL;"); 

            String sql = "CREATE VIRTUAL TABLE IF NOT EXISTS pdf_data USING fts5(" +
                         "file_path UNINDEXED, " +
                         "page_number, " +
                         "line_number, " +
                         "content)";
            stmt.execute(sql);

        } catch (SQLException e) {
            System.err.println(" Database error: " + e.getMessage());
        }
    }

    private static CompletableFuture<List<String>> findPDFFilesAsync(String startDirectory) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path directoryPath = Paths.get(startDirectory).toAbsolutePath().normalize();

                if (!Files.exists(directoryPath)) {
                    System.err.println(" Error: Directory does not exist - " + startDirectory);
                    return Collections.emptyList();
                }
                if (!Files.isDirectory(directoryPath)) {
                    System.err.println(" Error: Not a valid directory - " + startDirectory);
                    return Collections.emptyList();
                }
                if (!Files.isReadable(directoryPath)) {
                    System.err.println(" Error: Cannot read directory - " + startDirectory);
                    return Collections.emptyList();
                }

                List<String> pdfFiles = new ArrayList<>();

                Files.walkFileTree(directoryPath, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        try {
                            if (file.toString().toLowerCase().endsWith(".pdf")) {
                                pdfFiles.add(file.toString());
                            }
                        } catch (Exception e) {
                            System.err.println(" Skipping file due to error: " + file + " - " + e.getMessage());
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException e) {
                        System.err.println(" Cannot access file: " + file + " - " + e.getMessage());
                        return FileVisitResult.CONTINUE;
                    }
                });

                return pdfFiles;

            } catch (Exception e) {
                System.err.println(" Error scanning directory: " + e.getMessage());
            }
            return Collections.emptyList();
        });
    }

    private static Set<String> getIndexedFiles() {
        Set<String> indexedFiles = new HashSet<>();
        String query = "SELECT DISTINCT file_path FROM pdf_data";

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_NAME);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {

            while (rs.next()) {
                indexedFiles.add(rs.getString("file_path"));
            }
        } catch (SQLException e) {
            System.err.println(" Database read error: " + e.getMessage());
        }
        return indexedFiles;
    }

    private static void extractAndStorePDF(String pdfPath) {
        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBufferedFile(pdfPath))) {
            PDFTextStripper pdfStripper = new PDFTextStripper();
            int pageNumber = 0;

            for (PDPage page : document.getPages()) {
                pageNumber++;
                pdfStripper.setStartPage(pageNumber);
                pdfStripper.setEndPage(pageNumber);
                String text = pdfStripper.getText(document);

                if (text != null && !text.isEmpty()) {
                    String[] lines = text.split("\n");
                    for (int lineNum = 0; lineNum < lines.length; lineNum++) {
                        if (lines[lineNum].trim().isEmpty()) continue;
                        String entry = String.format("%s|%d|%d|%s", pdfPath, pageNumber, lineNum + 1, lines[lineNum]);
                        writeQueue.offer(entry);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println(" Error reading PDF: " + pdfPath + " - " + e.getMessage());
        }
    }

    private static void processWriteQueue() {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_NAME);
             PreparedStatement pstmt = conn.prepareStatement(
                     "INSERT INTO pdf_data (file_path, page_number, line_number, content) VALUES (?, ?, ?, ?)")) {

            conn.setAutoCommit(false);
            while (!indexingComplete || !writeQueue.isEmpty()) {
                String entry = writeQueue.poll(1, TimeUnit.SECONDS);
                if (entry != null) {
                    String[] parts = entry.split("\\|", 4);
                    pstmt.setString(1, parts[0]);
                    pstmt.setInt(2, Integer.parseInt(parts[1]));
                    pstmt.setInt(3, Integer.parseInt(parts[2]));
                    pstmt.setString(4, parts[3]);
                    pstmt.addBatch();
                }

                if (writeQueue.isEmpty()) {
                    pstmt.executeBatch();
                    conn.commit();
                }
            }

            pstmt.executeBatch();
            conn.commit();

        } catch (SQLException | InterruptedException e) {
            System.err.println(" Database write error: " + e.getMessage());
        }
    }

    private static void searchInDatabase(String word) {
        String query = "SELECT file_path, page_number, line_number, content FROM pdf_data WHERE content LIKE ?";
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_NAME);
             PreparedStatement pstmt = conn.prepareStatement(query)) {

            pstmt.setString(1, "%" + word + "%");
            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                System.out.println(rs.getString("file_path") + " | Page " + rs.getInt("page_number") + 
                        ", Line " + rs.getInt("line_number") + ": " + rs.getString("content"));
            }

        } catch (SQLException e) {
            System.err.println(" Search error: " + e.getMessage());
        }
    }
}
