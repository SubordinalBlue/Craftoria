import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class ModpackTool {

    static final HttpClient HTTP = HttpClient.newHttpClient();

    static final Properties cfg = new Properties();
    static final Properties secrets = new Properties();

    static final Path ROOT = Paths.get("").toAbsolutePath();
    static final Path OUT = ROOT.resolve("exports");

    public static void main(String[] args) throws Exception {
        load("settings.properties", cfg);
        ensureSecrets();
        load("secrets.properties", secrets);

        checkDeps();

        updateVersionFiles();
        updatePackToml();

        if (flag("ENABLE_CLIENT_FILE_MODULE")) {
            buildClient();

            if (flag("ENABLE_MODPACK_UPLOADER_MODULE")) {
                int id = uploadClient();

                if (flag("ENABLE_SERVER_FILE_MODULE")) {
                    buildServer();
                    uploadServer(id);
                }
            }
        }

        if (flag("ENABLE_GITHUB_RELEASE_MODULE")) {
            createGitHubRelease();
        }

        System.out.println("✔ Done");
    }

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
        check("7z");
    }

    static void check(String base) {
        if (resolveCommand(base) == null) {
            throw new RuntimeException(base + " not found in PATH");
        }
    }

    static String resolveCommand(String name) {
        List<String> candidates = List.of(name, name + ".exe");
        for (String c : candidates) {
            try {
                new ProcessBuilder(c).start().destroy();
                return c;
            } catch (IOException ignored) {}
        }
        return null;
    }

    // -------------------------
    // Build
    // -------------------------
    static void buildClient() throws Exception {
        Files.createDirectories(OUT);

        Path zip = OUT.resolve(s("CLIENT_ZIP_NAME") + ".zip");
        Files.deleteIfExists(zip);

        run(resolveCommand("packwiz"), "cf", "export", "--output", zip.toString());

        if (!Files.exists(zip)) {
            throw new RuntimeException("Client build failed");
        }
    }

    static void buildServer() throws Exception {
        Path zip = OUT.resolve(s("SERVER_ZIP_NAME") + ".zip");
        Files.deleteIfExists(zip);

        run(resolveCommand("7z"),
                "a",
                "-tzip",
                zip.toString(),
                s("SERVER_FILES_FOLDER")); // no glob!
    }

    // -------------------------
    // Upload (pure Java)
    // -------------------------
    static int uploadClient() throws Exception {
        Path zip = OUT.resolve(s("CLIENT_ZIP_NAME") + ".zip");

        String metadata = json(Map.of(
                "changelog", s("CLIENT_CHANGELOG"),
                "displayName", s("CLIENT_FILE_DISPLAY_NAME"),
                "releaseType", s("CLIENT_RELEASE_TYPE")
        ));

        String res = multipart(url(), zip, metadata);

        return parseJsonInt(res, "id");
    }

    static void uploadServer(int parentId) throws Exception {
        Path zip = OUT.resolve(s("SERVER_ZIP_NAME") + ".zip");

        String metadata = json(Map.of(
                "displayName", s("SERVER_FILE_DISPLAY_NAME"),
                "parentFileId", parentId
        ));

        multipart(url(), zip, metadata);
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
                        + s("GITHUB_NAME") + "/"
                        + s("GITHUB_REPOSITORY") + "/releases"))
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

        String c = Files.readString(f, StandardCharsets.UTF_8);
        c = c.replaceAll("\"version\"\\s*:\\s*\".*?\"",
                "\"version\":\"" + s("MODPACK_VERSION") + "\"");

        Files.writeString(f, c, StandardCharsets.UTF_8);
    }

    static void updatePackToml() throws Exception {
        if (!flag("UPDATE_PACK_TOML")) return;

        Path f = ROOT.resolve("pack.toml");
        if (!Files.exists(f)) throw new RuntimeException("Missing pack.toml");

        String c = Files.readString(f, StandardCharsets.UTF_8);

        c = c.replaceAll("(?m)^name\\s*=\\s*\".*\"",
                "name = \"" + s("MODPACK_NAME") + "\"");

        Files.writeString(f, c, StandardCharsets.UTF_8);

        run(resolveCommand("packwiz"), "refresh");
    }

    // -------------------------
    // Helpers
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

    // minimal JSON builder
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

    // minimal JSON parser (safe enough for API responses)
    static int parseJsonInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(json);
        if (m.find()) return Integer.parseInt(m.group(1));
        throw new RuntimeException("Missing key: " + key + "\n" + json);
    }

    // multipart/form-data
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