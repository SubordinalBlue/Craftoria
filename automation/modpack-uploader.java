import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class ModpackTool {

    static Properties settings = new Properties();
    static Properties secrets = new Properties();

    static Path ROOT = Paths.get("").toAbsolutePath();
    static Path OUTPUT = ROOT.resolve("exports");

    static String curl = isWindows() ? "curl.exe" : "curl";

    public static void main(String[] args) throws Exception {
        Path start = Paths.get("").toAbsolutePath();

        loadProperties("settings.properties", settings);
        validateSecrets();

        loadProperties("secrets.properties", secrets);

        testDependencies();
        updateVersionFiles();
        updatePackToml();
        newClientFiles();
        pushClientFiles();

        if (bool("ENABLE_SERVER_FILE_MODULE") && !bool("ENABLE_MODPACK_UPLOADER_MODULE")) {
            newServerFiles(null);
        }

        newGitHubRelease();
        newChangelog();
        updateModlist();

        System.out.println("Modpack Upload Complete!");

        System.setProperty("user.dir", start.toString());
    }

    // -------------------------
    // Config helpers
    // -------------------------
    static boolean bool(String key) {
        return Boolean.parseBoolean(settings.getProperty(key, "false"));
    }

    static String str(String key) {
        return settings.getProperty(key);
    }

    static void loadProperties(String file, Properties props) throws IOException {
        Path p = ROOT.resolve(file);
        if (Files.exists(p)) {
            try (var in = Files.newInputStream(p)) {
                props.load(in);
            }
        }
    }

    static void validateSecrets() throws IOException {
        Path file = ROOT.resolve("secrets.properties");
        if (!Files.exists(file)) {
            System.out.println("Creating secrets.properties...");
            String content = """
                # Add your CurseForge token
                CURSEFORGE_TOKEN=your-token-here
                CURSEFORGE_USER=your-username
                """;
            Files.writeString(file, content);
        }
    }

    // -------------------------
    // Dependencies
    // -------------------------
    static void testDependencies() throws Exception {
        checkCommand("packwiz", "https://github.com/WhitePhant0m/packwiz/releases");
        checkCommand("7z", "https://www.7-zip.org/download.html");
        checkCommand(curl, "https://curl.se/download.html");
    }

    static void checkCommand(String cmd, String url) throws Exception {
        try {
            new ProcessBuilder(cmd).start();
        } catch (IOException e) {
            throw new RuntimeException(cmd + " not found. Install: " + url);
        }
    }

    // -------------------------
    // Client files
    // -------------------------
    static void newClientFiles() throws Exception {
        if (!bool("ENABLE_CLIENT_FILE_MODULE")) return;

        Files.createDirectories(OUTPUT);

        String zipName = str("CLIENT_ZIP_NAME") + ".zip";
        Path zip = OUTPUT.resolve(zipName);

        Files.deleteIfExists(zip);

        System.out.println("Running packwiz cf export...");

        run("packwiz", "cf", "export", "--output", zip.toString());

        if (!Files.exists(zip)) {
            throw new RuntimeException("packwiz failed");
        }
    }

    // -------------------------
    // Push client files
    // -------------------------
    static void pushClientFiles() throws Exception {
        if (!bool("ENABLE_MODPACK_UPLOADER_MODULE")) return;

        String metadata = "{ changelog:'" + str("CLIENT_CHANGELOG") + "' }";

        Path zip = OUTPUT.resolve(str("CLIENT_ZIP_NAME") + ".zip");

        List<String> cmd = new ArrayList<>();
        cmd.add(curl);
        cmd.add("--url");
        cmd.add("https://minecraft.curseforge.com/api/projects/" + str("CURSEFORGE_PROJECT_ID") + "/upload-file");
        cmd.add("--user");
        cmd.add(secrets.getProperty("CURSEFORGE_USER") + ":" + secrets.getProperty("CURSEFORGE_TOKEN"));
        cmd.add("-F");
        cmd.add("metadata=" + metadata);
        cmd.add("-F");
        cmd.add("file=@" + zip.toString());

        run(cmd.toArray(new String[0]));
    }

    // -------------------------
    // Server files
    // -------------------------
    static void newServerFiles(Integer id) throws Exception {
        if (!bool("ENABLE_SERVER_FILE_MODULE")) return;

        Files.createDirectories(OUTPUT);

        Path zip = OUTPUT.resolve(str("SERVER_ZIP_NAME") + ".zip");
        Files.deleteIfExists(zip);

        run("7z", "a", "-tzip", zip.toString(), str("SERVER_FILES_FOLDER") + "/*");

        if (bool("ENABLE_MODPACK_UPLOADER_MODULE")) {
            pushServerFiles(id);
        }
    }

    static void pushServerFiles(Integer id) throws Exception {
        Path zip = OUTPUT.resolve(str("SERVER_ZIP_NAME") + ".zip");

        run(curl,
                "--url", "https://minecraft.curseforge.com/api/projects/" + str("CURSEFORGE_PROJECT_ID") + "/upload-file",
                "-F", "file=@" + zip.toString()
        );
    }

    // -------------------------
    // Version updates
    // -------------------------
    static void updateVersionFiles() throws IOException {
        System.out.println("Updating version files...");

        Path version = ROOT.resolve("version_info.json");
        if (Files.exists(version)) {
            String content = Files.readString(version);
            content = content.replaceAll("\"version\"\\s*:\\s*\".*?\"",
                    "\"version\":\"" + str("MODPACK_VERSION") + "\"");
            Files.writeString(version, content);
        }
    }

    static void updatePackToml() throws IOException, InterruptedException {
        if (!bool("UPDATE_PACK_TOML")) return;

        Path file = ROOT.resolve("pack.toml");
        if (!Files.exists(file)) throw new RuntimeException("pack.toml missing");

        String content = Files.readString(file);

        content = content.replaceAll("(?m)^name\\s*=\\s*\".*\"",
                "name = \"" + str("MODPACK_NAME") + "\"");

        Files.writeString(file, content);

        run("packwiz", "refresh");
    }

    // -------------------------
    // Changelog / modlist
    // -------------------------
    static void newChangelog() throws Exception {
        if (!bool("ENABLE_CHANGELOG_GENERATOR_MODULE")) return;

        run("java", "-jar", str("CHANGELOG_GENERATOR_JAR"),
                "changelog");
    }

    static void updateModlist() throws Exception {
        if (!bool("ENABLE_MODLIST_CREATOR_MODULE")) return;

        run("java", "-jar", str("MODLIST_CREATOR_JAR"),
                "modlist");
    }

    // -------------------------
    // GitHub release
    // -------------------------
    static void newGitHubRelease() throws Exception {
        if (!bool("ENABLE_GITHUB_RELEASE_MODULE")) return;

        HttpClient client = HttpClient.newHttpClient();

        String json = """
            {
              "tag_name": "%s",
              "name": "%s"
            }
            """.formatted(str("MODPACK_VERSION"), str("MODPACK_VERSION"));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com/repos/"
                        + str("GITHUB_NAME") + "/"
                        + str("GITHUB_REPOSITORY") + "/releases"))
                .header("Authorization", "Bearer " + secrets.getProperty("GITHUB_TOKEN"))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    // -------------------------
    // Utilities
    // -------------------------
    static void run(String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        Process p = pb.start();
        if (p.waitFor() != 0) {
            throw new RuntimeException("Command failed: " + String.join(" ", cmd));
        }
    }

    static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}