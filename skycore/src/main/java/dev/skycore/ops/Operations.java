package dev.skycore.ops;

import dev.skycore.SkyCore;
import dev.skycore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Daily backups (database + island worlds, zipped, retention N days) and a daily restart at an off-peak hour.
 * Inside Docker the container's restart policy brings the server back after the restart.
 */
public final class Operations {
    private final SkyCore plugin;
    private final long startedAt = System.currentTimeMillis();
    private String lastBackupDay = "";
    private final List<Integer> warned = new ArrayList<>();
    private volatile boolean backupRunning;

    public Operations(SkyCore plugin) {
        this.plugin = plugin;
    }

    public void tickMinute() {
        var c = plugin.settings.config;
        ZonedDateTime now = ZonedDateTime.now(plugin.settings.zone);
        if (c.getBoolean("operations.backups.enabled", true) && now.getHour() == c.getInt("operations.backups.hour", 4)
                && !now.toLocalDate().toString().equals(lastBackupDay)) {
            lastBackupDay = now.toLocalDate().toString();
            backup(null);
        }
        if (c.getBoolean("operations.restart.enabled", true) && System.currentTimeMillis() - startedAt > 30 * 60_000L) {
            LocalTime target = LocalTime.of(c.getInt("operations.restart.hour", 5), c.getInt("operations.restart.minute", 0));
            long minutesLeft = java.time.Duration.between(now.toLocalTime().withSecond(0).withNano(0), target).toMinutes();
            for (int w : c.getIntegerList("operations.restart.warn-minutes")) {
                if (minutesLeft == w && !warned.contains(w)) {
                    warned.add(w);
                    Bukkit.broadcast(Text.mm(Text.PREFIX + "<yellow>Daily restart in " + w + " minute" + (w == 1 ? "" : "s") + "."));
                }
            }
            if (minutesLeft == 0) {
                Bukkit.broadcast(Text.mm(Text.PREFIX + "<red>Restarting now..."));
                Bukkit.getScheduler().runTaskLater(plugin, Bukkit::shutdown, 40L);
            }
        }
    }

    /** Saves everything, then zips worlds + a consistent DB copy off the main thread. */
    public void backup(Runnable done) {
        if (backupRunning) return;
        backupRunning = true;
        plugin.saveAll();
        List<World> worlds = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            w.save();
            w.setAutoSave(false);
            worlds.add(w);
        }
        File folder = new File(plugin.getServer().getWorldContainer(), plugin.settings.config.getString("operations.backups.folder", "backups"));
        folder.mkdirs();
        String stamp = ZonedDateTime.now(plugin.settings.zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"));
        File dbCopy = new File(folder, "skycore-" + stamp + ".db");
        // 26.x keeps every dimension inside one level folder (the one holding level.dat): zip whole levels
        List<File> worldFolders = worlds.stream().map(w -> levelRoot(w.getWorldFolder().getAbsoluteFile())).distinct().toList();
        plugin.db.backupTo(dbCopy, () -> {
            File zip = new File(folder, "backup-" + stamp + ".zip");
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip.toPath()))) {
                addFile(out, dbCopy.toPath(), "skycore.db");
                for (File wf : worldFolders) addDir(out, wf.toPath(), "worlds/" + wf.getName());
            } catch (IOException e) {
                plugin.getLogger().warning("Backup failed: " + e.getMessage());
            } finally {
                dbCopy.delete();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (World w : worlds) w.setAutoSave(true);
                    backupRunning = false;
                    plugin.getLogger().info("Backup written: " + zip.getName());
                    if (done != null) done.run();
                });
            }
            prune(folder);
        });
    }

    private static File levelRoot(File f) {
        for (File cur = f; cur != null; cur = cur.getParentFile())
            if (new File(cur, "level.dat").exists()) return cur;
        return f;
    }

    private static void addFile(ZipOutputStream out, Path file, String name) throws IOException {
        if (!Files.exists(file)) return;
        out.putNextEntry(new ZipEntry(name));
        Files.copy(file, out);
        out.closeEntry();
    }

    private static void addDir(ZipOutputStream out, Path dir, String prefix) throws IOException {
        if (!Files.isDirectory(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String n = file.getFileName().toString();
                if (n.equals("session.lock")) return FileVisitResult.CONTINUE;
                try {
                    addFile(out, file, prefix + "/" + dir.relativize(file).toString().replace(File.separatorChar, '/'));
                } catch (IOException e) {
                    // a file being written right now: skip it, the next backup will include it
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void prune(File folder) {
        int days = plugin.settings.config.getInt("operations.backups.retention-days", 14);
        LocalDate limit = LocalDate.now(plugin.settings.zone).minusDays(days);
        File[] files = folder.listFiles((d, n) -> n.startsWith("backup-") && n.endsWith(".zip"));
        if (files == null) return;
        for (File f : files) {
            try {
                LocalDate date = LocalDate.parse(f.getName().substring(7, 17));
                if (date.isBefore(limit)) f.delete();
            } catch (Exception ignored) {
            }
        }
    }
}
