import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.*;

public class ModpackTool {

    static final HttpClient HTTP = HttpClient.newHttpClient();

    static final Properties cfg = new Properties();
    static final Properties secrets = new Properties();

    static final Path ROOT = Paths.get("").toAbsolutePath();
    static final Path OUT = ROOT.resolve("exports");

    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static void main(String[] args) throws Exception {
        log("Starting...");

        load("settings.properties", cfg);
        ensureSecrets();
        load("secrets.properties", secrets);

        checkDeps();

        step("Update version files", ModpackTool::updateVersionFiles);
        step("Update pack.toml", ModpackTool::updatePackToml);

        if (flag("ENABLE_CLIENT_FILE_MODULE")) {
            step("Build client", ModpackTool::buildClient);

            if (flag("ENABLE_MODPACK_UPLOADER_MODULE")) {
                int id = step("Upload client", ModpackTool::uploadClient);

                if (flag("ENABLE_SERVER_FILE_MODULE")) {
                    step("Build server", ModpackTool::buildServer);
                    step("Upload server", () -> uploadServer(id));
                }
            }
        }

        if (flag("ENABLE_GITHUB_RELEASE_MODULE")) {
            step("GitHub release", ModpackTool::createGitHubRelease);
        }

        log("✔ Done");
    }

    // -------------------------
    // Logging
    // -------------------------
    static void log(String msg) {
        System.out.println("[" + TIME.format(LocalTime.now()) + "] " + msg);
    }

    static <T> T step(String name, ThrowingSupplier<T> fn) throws Exception {
        log("→ " + name);
        try {
            T result = fn.get();
            log("✓ " + name);
            return result;
        } catch (Exception e) {
            log("✗ " + name + ": " + e.getMessage());
            throw e;
        }
    }

    static void step(String name, ThrowingRunnable fn) throws Exception {
        step(name, () -> {
            fn.run();
            return null;
        });
    }

    interface ThrowingRunnable { void run() throws Exception; }
    interface ThrowingSupplier<T> { T get() throws Exception; }

    // -------------------------
    // Config
    // -------------------------
    static void load(String file, Properties p) throws IOException {
        Path f = ROOT.resolve(file);
        if (Files.exists(f)) {
            try (var in = Files.newInputStream(f)) {
                p.load(in);
            }
        }
    }

    static void ensureSecrets() throws IOException {
        Path f = ROOT.resolve("secrets.properties");
        if (!Files.exists(f)) {
            Files.writeString(f, """
                CURSEFORGE_TOKEN=
                CURSEFORGE_USER=
                GITHUB_TOKEN=
                """, StandardCharsets.UTF_8);

            throw new RuntimeException("Fill secrets.properties first.");
        }
    }

    static boolean flag(String k) {
        return Boolean.parseBoolean(cfg.getProperty(k, "false"));
    }

    static String s(String k) {
        return cfg.getProperty(k);
    }

    // -------------------------
    // Dependencies
    // -------------------------
    static void checkDeps() {
        check("packwiz");
    }

    static void check(String cmd) {
        try {
            new ProcessBuilder(cmd).start().destroy();
        } catch (IOException e) {
            throw new RuntimeException(cmd + " not found in PATH");
        }
    }

    // -------------------------
    // Build
    // -------------------------
    static void buildClient() throws Exception {
        Files.createDirectories(OUT);

        Path zip = OUT.resolve(s("CLIENT_ZIP_NAME") + ".zip");
        Files.deleteIfExists(zip);

        run("packwiz", "cf", "export", "--output", zip.toString());

        if (!Files.exists(zip)) throw new RuntimeException("Client build failed");
    }

    static void buildServer() throws Exception {
        Path zip = OUT.resolve(s("SERVER_ZIP_NAME") + ".zip");
        Files.deleteIfExists(zip);

        Path source = Paths.get(s("SERVER_FILES_FOLDER")).toAbsolutePath();

        zipFolder(source, zip);
    }

    static void zipFolder(Path source, Path zipFile) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(
                Files.newOutputStream(zipFile))) {

            zos.setLevel(9);

            Files.walk(source).forEach(path -> {
                try {
                    if (Files.isDirectory(path)) return;

                    String entry = source.relativize(path)
                            .toString()
                            .replace("\\", "/");

                    zos.putNextEntry(new ZipEntry(entry));
                    Files.copy(path, zos);
                    zos.closeEntry();

                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    // -------------------------
    // Upload (with retry)
    // -------------------------
    static int uploadClient() throws Exception {
        Path zip = OUT.resolve(s("CLIENT_ZIP_NAME") + ".zip");

        String metadata = json(Map.of(
                "changelog", s("CLIENT_CHANGELOG"),
                "displayName", s("CLIENT_FILE_DISPLAY_NAME"),
                "releaseType", s("CLIENT_RELEASE_TYPE")
        ));

        String res = retry(() -> multipart(url(), zip, metadata));

        return (int) parseJson(res).get("id");
    }

    static void uploadServer(int parentId) throws Exception {
        Path zip = OUT.resolve(s("SERVER_ZIP_NAME") + ".zip");

        String metadata = json(Map.of(
                "displayName", s("SERVER_FILE_DISPLAY_NAME"),
                "parentFileId", parentId
        ));

        retry(() -> multipart(url(), zip, metadata));
    }

    static <T> T retry(ThrowingSupplier<T> fn) throws Exception {
        int attempts = 3;
        long delay = 1000;

        for (int i = 1; i <= attempts; i++) {
            try {
                return fn.get();
            } catch (Exception e) {
                if (i == attempts) throw e;
                log("Retry " + i + " failed, retrying...");
                Thread.sleep(delay);
                delay *= 2;
            }
        }
        throw new RuntimeException("Unreachable");
    }

    static String url() {
        return "https://minecraft.curseforge.com/api/projects/"
                + s("CURSEFORGE_PROJECT_ID") + "/upload-file";
    }

    // -------------------------
    // GitHub
    // -------------------------
    static void createGitHubRelease() throws Exception {
        String body = json(Map.of(
                "tag_name", s("MODPACK_VERSION"),
                "name", s("MODPACK_VERSION")
        ));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com/repos/"
                        + s("GITHUB_NAME") + "/" + s("GITHUB_REPOSITORY") + "/releases"))
                .header("Authorization", "Bearer " + secrets.getProperty("GITHUB_TOKEN"))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    // -------------------------
    // File updates
    // -------------------------
    static void updateVersionFiles() throws IOException {
        Path f = ROOT.resolve("version_info.json");
        if (!Files.exists(f)) return;

        Map<String, Object> json = parseJson(Files.readString(f));
        json.put("version", s("MODPACK_VERSION"));

        Files.writeString(f, json(json));
    }

    static void updatePackToml() throws Exception {
        if (!flag("UPDATE_PACK_TOML")) return;

        Path f = ROOT.resolve("pack.toml");
        if (!Files.exists(f)) throw new RuntimeException("Missing pack.toml");

        String c = Files.readString(f);
        c = c.replaceAll("(?m)^name\\s*=\\s*\".*\"",
                "name = \"" + s("MODPACK_NAME") + "\"");

        Files.writeString(f, c);

        run("packwiz", "refresh");
    }

    // -------------------------
    // Process
    // -------------------------
    static void run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd)
                .directory(ROOT.toFile())
                .inheritIO()
                .start();

        if (p.waitFor() != 0) {
            throw new RuntimeException("Failed: " + String.join(" ", cmd));
        }
    }

    // -------------------------
    // JSON (tiny parser)
    // -------------------------
    static Map<String, Object> parseJson(String json) {
        Map<String, Object> map = new HashMap<>();
        json = json.trim().replaceAll("[{}\"]", "");

        for (String pair : json.split(",")) {
            if (!pair.contains(":")) continue;
            String[] kv = pair.split(":", 2);
            String k = kv[0].trim();
            String v = kv[1].trim();

            if (v.matches("-?\\d+")) map.put(k, Integer.parseInt(v));
            else map.put(k, v);
        }
        return map;
    }

    static String json(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        for (var e : map.entrySet()) {
            if (sb.length() > 1) sb.append(",");
            sb.append("\"").append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof Number) sb.append(v);
            else sb.append("\"").append(v).append("\"");
        }
        return sb.append("}").toString();
    }

    // -------------------------
    // Multipart
    // -------------------------
    static String multipart(String url, Path file, String metadata) throws Exception {
        String boundary = "----JavaBoundary" + System.currentTimeMillis();

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        var w = new PrintWriter(new OutputStreamWriter(body, StandardCharsets.UTF_8), true);

        w.append("--").append(boundary).append("\r\n");
        w.append("Content-Disposition: form-data; name=\"metadata\"\r\n\r\n");
        w.append(metadata).append("\r\n");

        w.append("--").append(boundary).append("\r\n");
        w.append("Content-Disposition: form-data; name=\"file\"; filename=\"" + file.getFileName() + "\"\r\n");
        w.append("Content-Type: application/zip\r\n\r\n");
        w.flush();

        Files.copy(file, body);
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));

        w.append("--").append(boundary).append("--\r\n").flush();

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-Api-Token", secrets.getProperty("CURSEFORGE_TOKEN"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();

        return HTTP.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }
}