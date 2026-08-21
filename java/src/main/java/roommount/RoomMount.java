package roommount;

import gearth.extensions.ExtensionForm;
import gearth.extensions.ExtensionInfo;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import roommount.core.FurniCatalog;
import roommount.core.InventoryTracker;
import roommount.core.Models;
import roommount.core.MountEngine;
import roommount.core.RoomTracker;
import roommount.core.SnapshotIO;
import gearth.extensions.parsers.HFloorItem;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@ExtensionInfo(Title = "Room Backup", Description = "Save & remount room layouts with Magic Stack Tile", Version = "1.0.1", Author = "reactruler")
public class RoomMount extends ExtensionForm {
    public Label statusLbl;
    public Label helpLbl;
    public TextField nameField;
    public TextArea logArea;
    public ListView<String> snapshotList;
    public CheckBox onTopCbx;
    public CheckBox gameChatCbx;
    public CheckBox forceCbx;
    public ChoiceBox<String> missingChoice;
    public ChoiceBox<String> sourceChoice;
    public Slider delaySlider;
    public Label delayLbl;

    private RoomTracker room;
    private InventoryTracker inventory;
    private FurniCatalog catalog;
    private SnapshotIO snapshots;
    private MountEngine mounter;
    private Path extDir;
    private volatile boolean connected;
    private volatile boolean gameChat = true;
    private final java.util.concurrent.atomic.AtomicInteger delayMsLive = new java.util.concurrent.atomic.AtomicInteger(320);
    private volatile String missingModeLive = "skip";
    private volatile String sourceModeLive = "bcfirst";
    private volatile boolean forceLive = false;

    private static final Set<String> CMD_HELP = setOf("help", "?", "commands");
    private static final Set<String> CMD_SAVE = setOf("save", "msave", "mountsave", "export");
    private static final Set<String> CMD_LOAD = setOf("load", "mload", "mountload", "mount", "import");
    private static final Set<String> CMD_LIST = setOf("list", "mlist", "mountlist", "snapshots");
    private static final Set<String> CMD_DELETE = setOf("delete", "mdel", "mdelete", "mrm", "rm");
    private static final Set<String> CMD_STOP = setOf("stop", "mstop", "mountstop", "abort");
    private static final Set<String> CMD_INV = setOf("inv", "minv", "inventory");
    private static final Set<String> CMD_OPT = setOf("opt", "mopt", "options");
    private static final Set<String> CMD_STACK = setOf("stack", "mstack", "stacktest");

    public void initialize() {
        missingChoice.setItems(FXCollections.observableArrayList("skip", "stop", "place new"));
        missingChoice.getSelectionModel().select("skip");
        missingChoice.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null) missingModeLive = b;
        });
        sourceChoice.setItems(FXCollections.observableArrayList("bcfirst", "invfirst", "bconly", "invonly"));
        sourceChoice.getSelectionModel().select("bcfirst");
        sourceChoice.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null) sourceModeLive = b;
        });
        if (forceCbx != null) {
            forceCbx.setSelected(false);
            forceCbx.selectedProperty().addListener((o, a, b) -> forceLive = b != null && b);
        }
        if (gameChatCbx != null) {
            gameChatCbx.setSelected(true);
            gameChatCbx.selectedProperty().addListener((o, a, b) -> gameChat = b != null && b);
        }
        if (delaySlider != null && delayLbl != null) {
            delaySlider.valueProperty().addListener((o, a, b) -> setDelayMs((int) Math.round(b.doubleValue())));
            setDelayMs((int) Math.round(delaySlider.getValue()));
        }
        snapshotList.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            String sel = snapshotList.getSelectionModel().getSelectedItem();
            if (sel != null) nameField.setText(sel);
        });
        if (helpLbl != null) {
            helpLbl.setText("Chat: :msave · :mload · :mdel · :mlist · :mstop · :mhelp  |  Room Backup");
        }
    }

    @Override
    protected void initExtension() {
        extDir = resolveExtDir();
        Path snapDir = extDir.resolve("snapshots");
        catalog = new FurniCatalog();
        room = new RoomTracker(this);
        inventory = new InventoryTracker(this);
        snapshots = new SnapshotIO(snapDir, catalog);
        mounter = new MountEngine(this, room, inventory, catalog, this::log);
        room.setOnChange(x -> Platform.runLater(this::updateStatus));
        room.setOnLog(this::log);
        inventory.setOnChange(x -> Platform.runLater(this::updateStatus));

        onConnect((host, port, hotelVersion, clientIdentifier, client) -> {
            connected = true;
            Platform.runLater(() -> {
                log("Connected to " + host);
                sayHelpBrief();
                updateStatus();
            });
            loadFurnidata(host);
            new Thread(() -> {
                try { Thread.sleep(400); } catch (InterruptedException ignored) {}
                room.requestReload();
            }, "rm-room-reload").start();
        });

        intercept(HMessage.Direction.TOSERVER, "Chat", this::onChat);
        intercept(HMessage.Direction.TOSERVER, "Shout", this::onChat);
        intercept(HMessage.Direction.TOSERVER, "Whisper", this::onChat);

        Platform.runLater(() -> {
            refreshList(false);
            updateStatus();
            tryLoadLocalFurnidata();
            log("Room Backup 1.0.1 ready");
            sayHelpBrief();
        });
    }

    private void onChat(HMessage msg) {
        String text;
        try {
            text = msg.getPacket().readString();
        } catch (Exception ex) {
            return;
        }
        if (text == null) return;
        text = text.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (!isCommand(lower)) return;
        msg.setBlocked(true);

        ParsedCmd parsed = parseCommand(text);
        if (parsed == null) {
            showHelp();
            return;
        }

        if (CMD_HELP.contains(parsed.action)) {
            showHelp();
            return;
        }
        if (CMD_STOP.contains(parsed.action)) {
            mounter.stop();
            log("Stop requested");
            return;
        }
        if (CMD_LIST.contains(parsed.action)) {
            refreshList(true);
            return;
        }
        if (CMD_DELETE.contains(parsed.action)) {
            String name = parsed.arg.isEmpty() ? nameField.getText() : parsed.arg;
            Platform.runLater(() -> {
                if (name != null && !name.isBlank()) nameField.setText(name);
                doDelete(name);
            });
            return;
        }
        if (CMD_INV.contains(parsed.action)) {
            inventory.request();
            log("Requesting inventory…");
            return;
        }
        if (CMD_SAVE.contains(parsed.action)) {
            String name = parsed.arg.isEmpty() ? nameField.getText() : parsed.arg;
            Platform.runLater(() -> {
                nameField.setText(name);
                doSave(name);
            });
            return;
        }
        if (CMD_LOAD.contains(parsed.action)) {
            String name = parsed.arg.isEmpty() ? nameField.getText() : parsed.arg;
            Platform.runLater(() -> {
                nameField.setText(name);
                doMount(name);
            });
            return;
        }
        if (CMD_OPT.contains(parsed.action)) {
            handleOpt(parsed.arg);
            return;
        }
        if (CMD_STACK.contains(parsed.action)) {
            new Thread(() -> mounter.testStackMove(delayMs()), "rm-stack-test").start();
            return;
        }
        showHelp();
    }

    private static boolean isCommand(String lower) {
        return lower.equals(":msave") || lower.startsWith(":msave ")
                || lower.equals(":mload") || lower.startsWith(":mload ")
                || lower.equals(":mount") || lower.startsWith(":mount ")
                || lower.equals(":mountsave") || lower.startsWith(":mountsave ")
                || lower.equals(":mountload") || lower.startsWith(":mountload ")
                || lower.equals(":mlist") || lower.equals(":mountlist")
                || lower.equals(":mdel") || lower.startsWith(":mdel ")
                || lower.equals(":mdelete") || lower.startsWith(":mdelete ")
                || lower.equals(":mrm") || lower.startsWith(":mrm ")
                || lower.equals(":mstop") || lower.equals(":mountstop")
                || lower.equals(":mhelp") || lower.equals(":mounthelp")
                || lower.equals(":minv") || lower.equals(":mopt") || lower.startsWith(":mopt ")
                || lower.equals(":mstack") || lower.equals(":stack") || lower.equals(":stacktest")
                || lower.equals(":rsave") || lower.startsWith(":rsave ")
                || lower.equals(":rload") || lower.startsWith(":rload ")
                || lower.equals(":rlist") || lower.equals(":rstop") || lower.equals(":rhelp");
    }

    private ParsedCmd parseCommand(String text) {
        String[] parts = text.split("\\s+");
        if (parts.length == 0) return null;
        String head = parts[0].toLowerCase(Locale.ROOT);
        if (head.startsWith(":")) head = head.substring(1);

        if (head.equals("msave") || head.equals("mountsave") || head.equals("rsave")) {
            return new ParsedCmd("save", joinFrom(parts, 1));
        }
        if (head.equals("mload") || head.equals("mountload") || head.equals("mount") || head.equals("rload")) {
            return new ParsedCmd("load", joinFrom(parts, 1));
        }
        if (head.equals("mlist") || head.equals("mountlist") || head.equals("rlist")) {
            return new ParsedCmd("list", "");
        }
        if (head.equals("mdel") || head.equals("mdelete") || head.equals("mrm") || head.equals("delete")) {
            return new ParsedCmd("delete", joinFrom(parts, 1));
        }
        if (head.equals("mstop") || head.equals("mountstop") || head.equals("rstop") || head.equals("abort")) {
            return new ParsedCmd("stop", "");
        }
        if (head.equals("minv")) {
            return new ParsedCmd("inv", "");
        }
        if (head.equals("mopt") || head.equals("mountopt")) {
            return new ParsedCmd("opt", joinFrom(parts, 1));
        }
        if (head.equals("mstack") || head.equals("stack") || head.equals("stacktest")) {
            return new ParsedCmd("stack", "");
        }
        if (head.equals("mhelp") || head.equals("mounthelp") || head.equals("rhelp") || head.equals("help")) {
            return new ParsedCmd("help", "");
        }
        if (parts.length >= 2) {
            String action = parts[1].toLowerCase(Locale.ROOT);
            return new ParsedCmd(action, joinFrom(parts, 2));
        }
        return null;
    }

    private void handleOpt(String argLine) {
        String[] parts = argLine.trim().isEmpty() ? new String[0] : argLine.trim().split("\\s+");
        if (parts.length < 2) {
            log("Options — missing=" + missingChoice.getValue()
                    + " · source=" + sourceChoice.getValue()
                    + " · delay=" + delayMs() + "ms");
            log("Change with :mopt missing skip|stop|place · :mopt source bcfirst|invfirst|bconly|invonly · :mopt delay 80-1200");
            return;
        }
        String k = parts[0].toLowerCase(Locale.ROOT);
        String v = parts[1].toLowerCase(Locale.ROOT);
        Platform.runLater(() -> {
            if (k.equals("missing")) {
                String mode = "skip";
                if (v.equals("stop")) mode = "stop";
                else if (v.equals("place") || v.equals("placenew") || v.equals("place_new") || v.equals("new")) mode = "place new";
                missingChoice.getSelectionModel().select(mode);
                missingModeLive = mode;
            } else if (k.equals("source")) {
                if (Arrays.asList("bcfirst", "invfirst", "bconly", "invonly").contains(v)) {
                    sourceChoice.getSelectionModel().select(v);
                    sourceModeLive = v;
                }
            } else if (k.equals("delay") || k.equals("rate") || k.equals("speed")) {
                try {
                    int ms = clampDelay(Integer.parseInt(v));
                    if (delaySlider != null) delaySlider.setValue(ms);
                    setDelayMs(ms);
                } catch (NumberFormatException ex) {
                    log("delay must be a number (ms)");
                    return;
                }
            } else {
                log("Unknown option '" + k + "' — try :mhelp");
                return;
            }
            log("Option updated: " + k + "=" + v);
        });
    }

    private void showHelp() {
        log("Room Backup 1.0.1 — place a Magic Stack Tile, then :msave / :mload");
        log("Commands: :msave name · :mload name · :mdel name · :mlist · :mstop · :mopt · :mstack · :mhelp");
    }

    private void sayHelpBrief() {
        log("Room Backup 1.0.1 — :msave · :mload · :mdel · :mstack · :mhelp");
    }

    public void onSave(ActionEvent e) {
        doSave(nameField.getText());
    }

    public void onMount(ActionEvent e) {
        doMount(nameField.getText());
    }

    public void onDelete(ActionEvent e) {
        String sel = snapshotList != null ? snapshotList.getSelectionModel().getSelectedItem() : null;
        String name = (sel != null && !sel.isBlank()) ? sel : nameField.getText();
        doDelete(name);
    }

    public void onStop(ActionEvent e) {
        mounter.stop();
        log("Stop requested");
    }

    public void onRefresh(ActionEvent e) {
        refreshList(true);
        inventory.request();
        log("Refreshing snapshots + inventory…");
        updateStatus();
    }

    public void onAlwaysOnTop(ActionEvent e) {
        if (primaryStage != null) primaryStage.setAlwaysOnTop(onTopCbx.isSelected());
    }

    private void doSave(String name) {
        if (name == null || name.trim().isEmpty()) {
            log("Enter a snapshot name first");
            return;
        }
        if (!room.inRoom() && room.wallItems().isEmpty() && room.floorItems().isEmpty()) {
            log("Enter a room first");
            return;
        }
        if (!catalog.isReady()) {
            log("Furnidata not loaded — class names may be incomplete");
        }
        new Thread(() -> {
            try {
                if (room.floorItems().isEmpty()) {
                    room.ensureFloorLoaded(2500);
                }
                room.waitForFurniSettle(500, 8000);
                log("Capturing room: floor=" + room.floorItems().size() + " wall=" + room.wallItems().size());
                List<Models.Target> targets = snapshots.capture(room, false);
                long floors = targets.stream().filter(t -> "floor".equals(t.type)).count();
                long walls = targets.stream().filter(t -> "wall".equals(t.type)).count();
                if (!room.floorItems().isEmpty()) {
                    StringBuilder kinds = new StringBuilder();
                    int n = 0;
                    for (Models.Target t : targets) {
                        if (!"floor".equals(t.type)) continue;
                        if (n++ > 0) kinds.append(", ");
                        kinds.append(t.className).append("@").append(t.x).append(",").append(t.y).append(",z").append(t.z);
                        if (n >= 12) {
                            kinds.append(", …");
                            break;
                        }
                    }
                    if (n == 0) log("WARNING: no floor furniture to save (only stackmagic?)");
                    else log("Saving floor: " + kinds);
                }
                snapshots.save(name.trim(), targets);
                String id = SnapshotIO.sanitize(name.trim());
                int skippedSm = snapshots.lastSkippedStackMagic();
                log("Saved \"" + id + "\" — floor " + floors + " · wall " + walls
                        + (skippedSm > 0 ? (" (skipped " + skippedSm + " Magic Stack Tile)") : ""));
                if (floors == 0) {
                    log("WARNING: floor 0 — place real furniture then :msave again (stackmagic is not saved)");
                }
                Platform.runLater(() -> refreshList(false));
            } catch (Exception ex) {
                log("Save failed: " + ex.getMessage());
            }
        }, "rm-save").start();
    }

    private void doMount(String name) {
        if (name == null || name.trim().isEmpty()) {
            log("Enter a snapshot name first");
            return;
        }
        if (!room.inRoom() && room.floorItems().isEmpty() && room.wallItems().isEmpty()) {
            log("Enter a room first");
            return;
        }
        new Thread(() -> {
            try {
                if (room.floorItems().isEmpty()) {
                    room.ensureFloorLoaded(2500);
                }
                List<Models.Target> targets = snapshots.load(name.trim());
                if (targets.isEmpty()) {
                    log("Snapshot \"" + name.trim() + "\" is empty");
                    return;
                }
                log("Mounting \"" + SnapshotIO.sanitize(name.trim()) + "\" (" + targets.size() + " items)…");
                Models.MountOptions opt = currentOptions();
                mounter.mount(targets, opt);
            } catch (Exception ex) {
                log("Mount failed: " + ex.getMessage());
            }
        }, "rm-mount").start();
    }

    private void doDelete(String name) {
        if (name == null || name.trim().isEmpty()) {
            log("Select a snapshot or type a name to delete");
            return;
        }
        String id = SnapshotIO.sanitize(name.trim());
        try {
            if (!snapshots.delete(id)) {
                log("No snapshot named \"" + id + "\"");
                return;
            }
            log("Deleted \"" + id + "\"");
            if (nameField != null && id.equalsIgnoreCase(SnapshotIO.sanitize(nameField.getText() == null ? "" : nameField.getText()))) {
                nameField.clear();
            }
            refreshList(false);
        } catch (Exception ex) {
            log("Delete failed: " + ex.getMessage());
        }
    }

    private Models.MountOptions currentOptions() {
        Models.MountOptions opt = new Models.MountOptions();
        opt.missingMode = Models.MissingMode.SKIP;
        if ("stop".equals(missingModeLive)) opt.missingMode = Models.MissingMode.STOP;
        else if ("place new".equals(missingModeLive) || "place".equals(missingModeLive)) {
            opt.missingMode = Models.MissingMode.PLACE_NEW;
        }
        String src = sourceModeLive == null ? "bcfirst" : sourceModeLive;
        if ("invfirst".equals(src)) opt.sourceMode = Models.SourceMode.INV_FIRST;
        else if ("bconly".equals(src)) opt.sourceMode = Models.SourceMode.BC_ONLY;
        else if ("invonly".equals(src)) opt.sourceMode = Models.SourceMode.INV_ONLY;
        else opt.sourceMode = Models.SourceMode.BC_FIRST;
        opt.delayMs = delayMs();
        opt.delayLive = this::delayMs;
        opt.force = forceLive;
        return opt;
    }

    private void setDelayMs(int ms) {
        int v = clampDelay(ms);
        delayMsLive.set(v);
        if (delayLbl != null) delayLbl.setText(v + " ms");
    }

    private static int clampDelay(int ms) {
        return Math.max(40, Math.min(1500, ms));
    }

    private int delayMs() {
        return delayMsLive.get();
    }

    private void refreshList(boolean announce) {
        try {
            Files.createDirectories(snapshots.snapDir());
            List<String> names = snapshots.listNames();
            snapshotList.setItems(FXCollections.observableArrayList(names));
            if (announce) {
                if (names.isEmpty()) log("No snapshots yet — :msave <name>");
                else {
                    log("Snapshots (" + names.size() + "):");
                    for (String n : names) log("  · " + n);
                }
            }
        } catch (Exception ex) {
            log("List failed: " + ex.getMessage());
        }
    }

    private void updateStatus() {
        if (statusLbl == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(connected ? "Connected" : "Disconnected");
        sb.append(" · room ").append(room != null && room.inRoom() ? "yes (" + room.floorItems().size() + "f/" + room.wallItems().size() + "w)" : "no");
        sb.append(" · inv ").append(inventory == null ? "?" : inventory.getState());
        sb.append(" · furnidata ").append(catalog != null && catalog.isReady() ? "ok" : "missing");
        statusLbl.setText(sb.toString());
    }

    private void tryLoadLocalFurnidata() {
        Path[] candidates = new Path[]{
                extDir.resolve("furnidata.xml"),
                Paths.get("c:/Users/Florin/DevProjects/Habbo/Habbo/xampp/htdocs/public/swf/gamedata/furnidata.xml"),
                Paths.get(System.getProperty("user.home"), "furnidata.xml")
        };
        for (Path p : candidates) {
            if (p != null && Files.isRegularFile(p)) {
                try {
                    catalog.loadXml(p);
                    log("Furnidata loaded (" + String.join(", ", catalog.summary()) + ")");
                    updateStatus();
                    return;
                } catch (Exception ex) {
                    log("Furnidata load failed: " + ex.getMessage());
                }
            }
        }
        log("Place furnidata.xml next to RoomBackup.jar for class names / BC offers");
    }

    private void loadFurnidata(String host) {
        new Thread(() -> {
            try {
                String hotel = "www.habbo.com";
                if (host != null) {
                    String h = host.toLowerCase(Locale.ROOT);
                    if (h.contains("game-es") || h.contains(".es")) hotel = "www.habbo.es";
                    else if (h.contains("game-br") || h.contains(".com.br")) hotel = "www.habbo.com.br";
                    else if (h.contains("game-de") || h.contains(".de")) hotel = "www.habbo.de";
                    else if (h.contains("game-fr") || h.contains(".fr")) hotel = "www.habbo.fr";
                    else if (h.contains("game-it") || h.contains(".it")) hotel = "www.habbo.it";
                    else if (h.contains("game-nl") || h.contains(".nl")) hotel = "www.habbo.nl";
                    else if (h.contains("game-fi") || h.contains(".fi")) hotel = "www.habbo.fi";
                    else if (h.contains("game-tr") || h.contains(".com.tr")) hotel = "www.habbo.com.tr";
                }
                String url = "https://" + hotel + "/gamedata/furnidata_xml/0";
                log("Downloading furnidata from " + hotel + "…");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                conn.setRequestProperty("User-Agent", "RoomBackup/1.0.1");
                try (java.io.InputStream in = conn.getInputStream()) {
                    Path dest = extDir.resolve("furnidata.xml");
                    Files.copy(in, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    catalog.loadXml(dest);
                    int sm = catalog.typeIdForClass("tile_stackmagic");
                    log("Live furnidata ok (" + String.join(", ", catalog.summary())
                            + ") tile_stackmagic id=" + sm);
                    Platform.runLater(this::updateStatus);
                    return;
                }
            } catch (Exception ex) {
                log("Live furnidata failed: " + ex.getMessage() + " — using local");
            }
            Platform.runLater(this::tryLoadLocalFurnidata);
        }, "rm-furnidata").start();
    }

    private Path resolveExtDir() {
        try {
            File jar = new File(RoomMount.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File dir = jar.isFile() ? jar.getParentFile() : jar;
            return dir.toPath();
        } catch (URISyntaxException ex) {
            return Paths.get(".").toAbsolutePath();
        }
    }

    private void log(String msg) {
        String line = msg;
        try {
            writeToConsole("cyan", "[Room Backup] " + line);
        } catch (Exception ignored) {
        }
        if (gameChat && shouldWhisper(line)) {
            try {
                String shortMsg = line.length() > 90 ? line.substring(0, 87) + "…" : line;
                sendToClient(new HPacket("Whisper", HMessage.Direction.TOCLIENT, -1, "Room Backup: " + shortMsg, 0, 30, 0, -1));
            } catch (Exception ignored) {
            }
        }
        Platform.runLater(() -> {
            if (logArea != null) {
                logArea.appendText(line + "\n");
            }
        });
    }

    private static boolean shouldWhisper(String line) {
        String s = line.toLowerCase(Locale.ROOT);
        return s.contains("abort")
                || s.contains("stack hit")
                || s.contains("stack move")
                || s.contains("ready")
                || s.contains("done —")
                || s.contains("fail")
                || s.startsWith("ok ")
                || s.contains("live furnidata");
    }

    private static String joinFrom(String[] parts, int from) {
        if (from >= parts.length) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < parts.length; i++) {
            if (i > from) sb.append(' ');
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    private static Set<String> setOf(String... values) {
        return new HashSet<String>(Arrays.asList(values));
    }

    private static final class ParsedCmd {
        final String action;
        final String arg;

        ParsedCmd(String action, String arg) {
            this.action = action;
            this.arg = arg == null ? "" : arg.trim();
        }
    }
}
