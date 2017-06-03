package stream.flarebot.flarebot.commands;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import net.dv8tion.jda.core.entities.Guild;
import net.dv8tion.jda.core.entities.TextChannel;
import net.dv8tion.jda.core.entities.User;
import stream.flarebot.flarebot.FlareBot;
import stream.flarebot.flarebot.Language;
import stream.flarebot.flarebot.mod.AutoModConfig;
import stream.flarebot.flarebot.mod.AutoModGuild;
import stream.flarebot.flarebot.objects.Poll;
import stream.flarebot.flarebot.objects.Report;
import stream.flarebot.flarebot.util.LocalConfig;
import stream.flarebot.flarebot.util.MessageUtils;
import stream.flarebot.flarebot.util.ReportManager;
import stream.flarebot.flarebot.util.SQLController;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class FlareBotManager {

    private static FlareBotManager instance;

    public static final Gson GSON = new GsonBuilder().create();

    private List<String> loadedSongs = new ArrayList<>();
    private Random rand = new Random();

    private Map<String, Poll> polls = new ConcurrentHashMap<>();
    private Map<String, Set<String>> selfAssignRoles = new ConcurrentHashMap<>();
    private Map<String, AutoModGuild> autoMod = new ConcurrentHashMap<>();
    private Map<String, Language.Locales> locale = new ConcurrentHashMap<>();

    private Set<String> profanitySet = new HashSet<>();
    private Map<Language.Locales, LocalConfig> configs;

    public FlareBotManager() {
        instance = this;
    }

    public static FlareBotManager getInstance() {
        return instance;
    }

    public void loadRandomSongs() {
        try {
            SQLController.runSqlTask(conn -> {
                conn.createStatement()
                        .executeUpdate("CREATE TABLE IF NOT EXISTS random_songs (video_id VARCHAR(12) PRIMARY KEY);");
                ResultSet set = conn.createStatement().executeQuery("SELECT * FROM random_songs;");
                while (set.next()) {
                    loadedSongs.add(set.getString("video_id"));
                }
            });
        } catch (SQLException e) {
            FlareBot.LOGGER.error("Could not load songs!", e);
        }
    }

    public Set<String> getRandomSongs(int amount, TextChannel channel) {
        Set<String> songs = new HashSet<>();
        if (amount < 10 || amount > 100) {
            throw new IllegalArgumentException("Invalid amount. Make sure it is 10 or more and 100 or less!");
        }

        for (int i = 0; i < amount; i++) {
            songs.add(loadedSongs.get(rand.nextInt(loadedSongs.size())));
        }
        return songs;
    }

    public Map<String, Poll> getPolls() {
        return this.polls;
    }

    public Poll getPollFromGuild(Guild guild) {
        return this.polls.get(guild.getId());
    }

    public void executeCreations() {
        try {
            SQLController.runSqlTask(conn -> {
                conn.createStatement().execute("CREATE TABLE IF NOT EXISTS playlist (\n" +
                        "  playlist_name  VARCHAR(60),\n" +
                        "  guild VARCHAR(20),\n" +
                        "  owner VARCHAR(20),\n" +
                        "  list  TEXT,\n" +
                        "  scope  VARCHAR(7) DEFAULT 'local',\n" +
                        "  PRIMARY KEY(playlist_name, guild)\n" +
                        ")");
                conn.createStatement()
                        .execute("CREATE TABLE IF NOT EXISTS selfassign (guild_id VARCHAR(20) PRIMARY KEY NOT NULL, roles TEXT)");
                conn.createStatement()
                        .execute("CREATE TABLE IF NOT EXISTS automod (guild_id VARCHAR(20) PRIMARY KEY NOT NULL, automod_data TEXT)");
                conn.createStatement()
                        .execute("CREATE TABLE IF NOT EXISTS localisation (guild_id VARCHAR(20) PRIMARY KEY NOT NULL, locale TEXT)");
                conn.createStatement()
                        .execute("CREATE TABLE IF NOT EXISTS reports (id INT NOT NULL AUTO_INCREMENT PRIMARY KEY, guild_id VARCHAR(20), time DATETIME, message VARCHAR(500), reporter_id VARCHAR(20), reported_id VARCHAR(20), status INT(2))");
            });
        } catch (SQLException e) {
            FlareBot.LOGGER.error("Database error!", e);
        }
    }

    public void savePlaylist(TextChannel channel, String owner, String name, String list) {
        try {
            SQLController.runSqlTask(connection -> {
                PreparedStatement exists = connection
                        .prepareStatement("SELECT * FROM playlist WHERE playlist_name = ? AND guild = ?");
                exists.setString(1, name);
                exists.setString(2, channel.getGuild().getId());
                exists.execute();
                if (exists.getResultSet().isBeforeFirst()) {
                    channel.sendMessage("That name is already taken!").queue();
                    return;
                }
                PreparedStatement statement = connection
                        .prepareStatement("INSERT INTO playlist (playlist_name, guild, owner, list) VALUES (" +
                                "   ?," +
                                "   ?," +
                                "   ?," +
                                "   ?" +
                                ")");
                statement.setString(1, name);
                statement.setString(2, channel.getGuild().getId());
                statement.setString(3, owner);
                statement.setString(4, list);
                statement.executeUpdate();
                channel.sendMessage(MessageUtils.getEmbed(FlareBot.getInstance().getUserByID(owner))
                        .setDescription("Success!").build()).queue();
            });
        } catch (SQLException e) {
            FlareBot.reportError(channel, "The playlist could not be saved!", e);
            FlareBot.LOGGER.error("Database error!", e);
        }
    }

    public String loadPlaylist(TextChannel channel, User sender, String name) {
        final String[] list = new String[1];
        try {
            SQLController.runSqlTask(connection -> {
                PreparedStatement exists = connection
                        .prepareStatement("SELECT list FROM playlist WHERE (playlist_name = ? AND guild = ?) " +
                                "OR (playlist_name=? AND scope = 'global')");
                exists.setString(1, name);
                exists.setString(2, channel.getGuild().getId());
                exists.setString(3, channel.getGuild().getId());
                exists.execute();
                ResultSet set = exists.getResultSet();
                if (set.next()) {
                    list[0] = set.getString("list");
                } else
                    channel.sendMessage(MessageUtils.getEmbed(sender)
                            .setDescription("*That playlist does not exist!*").build()).queue();
            });
        } catch (SQLException e) {
            FlareBot.reportError(channel, "Unable to load the playlist!", e);
            FlareBot.LOGGER.error("Database error!", e);
        }
        return list[0];
    }

    public void loadProfanity() {
        try {
            Unirest.get("https://flarebot.stream/api/profanity.php").asJson().getBody().getObject()
                    .getJSONArray("words").forEach(word -> profanitySet.add(word.toString()));
        } catch (UnirestException e) {
            e.printStackTrace();
        }
    }

    public Set<String> getSelfAssignRoles(String guildId) {
        return this.selfAssignRoles.computeIfAbsent(guildId, gid -> ConcurrentHashMap.newKeySet());
    }

    public Map<String, Set<String>> getSelfAssignRoles() {
        return this.selfAssignRoles;
    }

    public AutoModConfig getAutoModConfig(String guild) {
        return this.autoMod.getOrDefault(guild, new AutoModGuild()).getConfig();
    }

    public Set<String> getProfanity() {
        return profanitySet;
    }

    public AutoModGuild getAutoModGuild(String guild) {
        if (!autoMod.containsKey(guild))
            autoMod.put(guild, new AutoModGuild());
        return this.autoMod.get(guild);
    }

    public void loadAutoMod() {
        try {
            SQLController.runSqlTask(conn -> {
                ResultSet set = conn.createStatement().executeQuery("SELECT guild_id, automod_data FROM automod");
                while (set.next()) {
                    autoMod.put(set.getString("guild_id"), GSON
                            .fromJson(set.getString("automod_data"), AutoModGuild.class));
                }
            });
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void saveAutoMod() {
        FlareBot.LOGGER.info("Saving automod data!");
        for (String s : autoMod.keySet()) {
            try {
                SQLController.runSqlTask(conn -> {
                    PreparedStatement statement = conn
                            .prepareStatement("INSERT INTO automod (guild_id, automod_data) VALUES (?, ?) ON DUPLICATE KEY UPDATE automod_data = VALUES(automod_data)");
                    statement.setString(1, s);
                    statement.setString(2, GSON.toJson(autoMod.get(s)));
                    statement.execute();
                });
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    public void loadLocalisation() {
        try {
            SQLController.runSqlTask(conn -> {
                ResultSet set = conn.createStatement().executeQuery("SELECT guild_id, locale FROM localisation");
                while (set.next()) {
                    Language.Locales l = Language.Locales.from(set.getString("locale"));
                    locale.put(set.getString("guild_id"), l);
                }
            });
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void saveLocalisation() {
        FlareBot.LOGGER.info("Saving localisation data!");
        for (String s : locale.keySet()) {
            try {
                SQLController.runSqlTask(conn -> {
                    PreparedStatement statement = conn
                            .prepareStatement("INSERT INTO localisation (guild_id, locale) VALUES (?, ?) ON DUPLICATE KEY UPDATE locale = VALUES(locale)");
                    statement.setString(1, s);
                    statement.setString(2, locale.get(s).getCode());
                    statement.execute();
                });
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    public LocalConfig loadLang(Language.Locales l) {
        return configs.computeIfAbsent(l, locale -> new LocalConfig(getClass().getResource("/langs/" + l.getCode() + ".json")));
    }

    public String getLang(Language lang, String id) {
        String path = lang.name().toLowerCase().replaceAll("_", ".");
        LocalConfig config = loadLang(locale.getOrDefault(id, Language.Locales.ENGLISH_UK));
        return config.getString(path);
    }

    public void saveReports() {
        FlareBot.LOGGER.info("Saving reports data");
        for (Report report : ReportManager.getInstance().getAllReports()) {
            try {
                SQLController.runSqlTask(conn -> {
                    PreparedStatement statement = conn.prepareStatement("INSERT INTO reports (guild_id, time, message, reporter_id, reported_id, status) VALUES (?, ?, ?, ?, ?, ?)");
                    statement.setString(1, report.getGuildId());
                    statement.setObject(2, report.getTime());
                    statement.setString(3, report.getMessage());
                    statement.setString(4, report.getReporterId());
                    statement.setString(5, report.getReportedId());
                    statement.setInt(6, report.getStatus().ordinal());
                    statement.execute();
                });
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }
}


