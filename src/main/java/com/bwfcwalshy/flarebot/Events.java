package com.bwfcwalshy.flarebot;

import com.bwfcwalshy.flarebot.commands.Command;
import com.bwfcwalshy.flarebot.commands.CommandType;
import com.bwfcwalshy.flarebot.commands.secret.UpdateCommand;
import com.bwfcwalshy.flarebot.objects.PlayerCache;
import com.bwfcwalshy.flarebot.scheduler.FlarebotTask;
import com.bwfcwalshy.flarebot.util.Welcome;
import net.dv8tion.jda.core.EmbedBuilder;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.OnlineStatus;
import net.dv8tion.jda.core.Permission;
import net.dv8tion.jda.core.entities.Message;
import net.dv8tion.jda.core.entities.Role;
import net.dv8tion.jda.core.entities.TextChannel;
import net.dv8tion.jda.core.events.ReadyEvent;
import net.dv8tion.jda.core.events.guild.GuildJoinEvent;
import net.dv8tion.jda.core.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.core.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.core.events.guild.voice.GuildVoiceJoinEvent;
import net.dv8tion.jda.core.events.guild.voice.GuildVoiceLeaveEvent;
import net.dv8tion.jda.core.events.message.guild.GenericGuildMessageEvent;
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.core.events.user.UserOnlineStatusUpdateEvent;
import net.dv8tion.jda.core.hooks.ListenerAdapter;

import java.awt.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class Events extends ListenerAdapter {

    private FlareBot flareBot;
    private static final ThreadGroup COMMAND_THREADS = new ThreadGroup("Command Threads");
    private static final ExecutorService CACHED_POOL = Executors.newCachedThreadPool(r ->
            new Thread(COMMAND_THREADS, r, "Command Pool-" + COMMAND_THREADS.activeCount()));

    public static final Map<String, AtomicInteger> COMMAND_COUNTER = new ConcurrentHashMap<>();
    private AtomicInteger i = new AtomicInteger(0);


    public Events(FlareBot bot) {
        this.flareBot = bot;
    }

    @Override
    public void onReady(ReadyEvent event) {
        FlareBot.getInstance().latch.countDown();
    }

    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent event) {
        if (flareBot.getWelcomeForGuild(event.getGuild()) != null) {
            Welcome welcome = flareBot.getWelcomeForGuild(event.getGuild());
            TextChannel channel = flareBot.getChannelByID(welcome.getChannelId());
            if (channel != null) {
                String msg = welcome.getMessage()
                        .replace("%user%", event.getMember().getUser().getName())
                        .replace("%guild%", event.getGuild().getName())
                        .replace("%mention%", event.getMember().getUser().getAsMention());
                MessageUtils.sendMessage(msg, channel);
            } else flareBot.getWelcomes().remove(welcome);
        }
        if (flareBot.getAutoAssignRoles().containsKey(event.getGuild().getId())) {
            List<String> autoAssignRoles = flareBot.getAutoAssignRoles().get(event.getGuild().getId());
            List<Role> roles = new ArrayList<>();
            for (String s : autoAssignRoles) {
                Role role = event.getGuild().getRoleById(s);
                if (role != null) {
                    roles.add(role);
                } else autoAssignRoles.remove(s);
            }
            event.getGuild().getController().addRolesToMember(event.getMember(), roles).queue((n) -> {
            }, (e1) -> {
                if (!e1.getMessage().startsWith("Edited roles")) {
                    MessageUtils.sendPM(event.getGuild().getOwner().getUser(),
                            "**Could not auto assign a role!**\n" + e1.getMessage());
                    return;
                }
                StringBuilder message = new StringBuilder();

                message.append("**Hello!\nI am here to tell you that I could not give the role(s) ```\n");
                message.append(roles.stream().map(Role::getName).collect(Collectors.joining("\n")))
                        .append("\n``` to one of your new users!\n");
                message.append("Please move one of the following roles so they are higher up than any of the above: \n```")
                        .append(event.getGuild().getSelfMember().getRoles().stream()
                                .map(Role::getName)
                                .collect(Collectors.joining("\n"))).append("``` in your server's role tab!**");
                MessageUtils.sendPM(event.getGuild().getOwner().getUser(), message);
            });
        }
    }

    @Override
    public void onGuildJoin(GuildJoinEvent event) {
        if (event.getJDA().getStatus() == JDA.Status.CONNECTED)
            MessageUtils.sendMessage(new EmbedBuilder()
                            .setColor(new Color(96, 230, 144))
                            .setThumbnail(event.getGuild().getIconUrl())
                            .setFooter(event.getGuild().getId(), event.getGuild().getIconUrl())
                            .setAuthor(event.getGuild().getName(), null, event.getGuild().getIconUrl())
                            .setDescription("Guild Created: `" + event.getGuild().getName() + "` :smile: :heart:\n" +
                                    "Guild Owner: " + event.getGuild().getOwner().getUser().getName()),
                    FlareBot.getInstance().getGuildLogChannel());
    }

    @Override
    public void onGuildLeave(GuildLeaveEvent event) {
        COMMAND_COUNTER.remove(event.getGuild().getId());
        MessageUtils.sendMessage(new EmbedBuilder()
                        .setColor(new Color(244, 23, 23))
                        .setColor(new Color(96, 230, 144))
                        .setThumbnail(event.getGuild().getIconUrl())
                        .setFooter(event.getGuild().getId(), event.getGuild().getIconUrl())
                        .setAuthor(event.getGuild().getName(), null, event.getGuild().getIconUrl())
                        .setDescription("Guild Deleted: `" + event.getGuild().getName() + "` L :broken_heart:\n" +
                                "Guild Owner: " + (event.getGuild().getOwner() != null ?
                                event.getGuild().getOwner().getUser().getName()
                                : "Non-existent, they had to much L")),
                FlareBot.getInstance().getGuildLogChannel());
    }

    @Override
    public void onGuildVoiceJoin(GuildVoiceJoinEvent event) {
        if (event.getMember().getUser().equals(event.getJDA().getSelfUser())) {
            if (FlareBot.getInstance().getMusicManager().hasPlayer(event.getGuild().getId())) {
                FlareBot.getInstance().getMusicManager().getPlayer(event.getGuild().getId()).setPaused(false);
            }
        }
    }

    @Override
    public void onGuildVoiceLeave(GuildVoiceLeaveEvent event) {
        if (event.getMember().getUser().equals(event.getJDA().getSelfUser())) {
            if (FlareBot.getInstance().getMusicManager().hasPlayer(event.getGuild().getId())) {
                FlareBot.getInstance().getMusicManager().getPlayer(event.getGuild().getId()).setPaused(true);
            }
        } else {
            if (event.getMember().getUser().equals(event.getJDA().getSelfUser())) {
                if (flareBot.getActiveVoiceChannels() == 0 && UpdateCommand.NOVOICE_UPDATING.get()) {
                    MessageUtils.sendMessage("I am now updating, there are no voice channels active!", flareBot.getChannelByID("229704246004547585"));
                    UpdateCommand.update(true, null);
                }
                return;
            }
            if (event.getChannelLeft().getMembers().contains(event.getGuild().getMember(event.getJDA().getSelfUser()))
                    && event.getChannelLeft().getMembers().size() < 2) {
                event.getChannelLeft().getGuild().getAudioManager().closeAudioConnection();
            }
        }
    }

    @Override
    public void onGuildMessageReceived(GuildMessageReceivedEvent event) {
        PlayerCache cache = flareBot.getPlayerCache(event.getAuthor().getId());
        cache.setLastMessage(LocalDateTime.from(event.getMessage().getCreationTime()));
        cache.setLastSeen(LocalDateTime.now());
        cache.setLastSpokeGuild(event.getGuild().getId());
        if (event.getMessage().getRawContent().startsWith(String.valueOf(FlareBot.getPrefixes().get(getGuildId(event))))
                && !event.getAuthor().isBot()) {
            List<Permission> perms = event.getChannel().getGuild().getSelfMember().getPermissions(event.getChannel());
            if (!perms.contains(Permission.ADMINISTRATOR)) {
                if (!perms.contains(Permission.MESSAGE_WRITE)) {
                    return;
                }
                if (!perms.contains(Permission.MESSAGE_EMBED_LINKS)) {
                    MessageUtils.sendMessage("Hey! I can't be used here." +
                            "\nI do not have the `Embed Links` permission! Please go to your permissions and give me Embed Links." +
                            "\nThanks :D", event.getChannel());
                    return;
                }
            }
            String message = event.getMessage().getRawContent();
            String command = message.substring(1);
            String[] args = new String[0];
            if (message.contains(" ")) {
                command = command.substring(0, message.indexOf(" ") - 1);

                args = message.substring(message.indexOf(" ") + 1).split(" ");
            }
            for (Command cmd : flareBot.getCommands()) {
                if (cmd.getCommand().equalsIgnoreCase(command)) {
                    if (cmd.getType() == CommandType.HIDDEN) {
                        if (!cmd.getPermissions(event.getChannel()).isCreator(event.getAuthor())) {
                            return;
                        }
                    }
                    if (UpdateCommand.UPDATING.get()) {
                        MessageUtils.sendMessage("**Currently updating!**", event.getChannel());
                        return;
                    }
                    if (handleMissingPermission(cmd, event))
                        return;
                    COMMAND_COUNTER.computeIfAbsent(event.getChannel().getGuild().getId(), g -> new AtomicInteger()).incrementAndGet();
                    String[] finalArgs = args;
                    CACHED_POOL.submit(() -> {
                        FlareBot.LOGGER.info(
                                "Dispatching command '" + cmd.getCommand() + "' " + Arrays.toString(finalArgs) + " in " + event.getChannel() + "! Sender: " +
                                        event.getAuthor().getName() + '#' + event.getAuthor().getDiscriminator());
                        try {
                            cmd.onCommand(event.getAuthor(), event.getChannel(), event.getMessage(), finalArgs, event.getMember());
                        } catch (Exception ex) {
                            MessageUtils.sendException("**There was an internal error trying to execute your command**", ex, event.getChannel());
                            FlareBot.LOGGER.error("Exception in guild " + "!\n" + '\'' + cmd.getCommand() + "' "
                                    + Arrays.toString(finalArgs) + " in " + event.getChannel() + "! Sender: " +
                                    event.getAuthor().getName() + '#' + event.getAuthor().getDiscriminator(), ex);
                        }
                        if (cmd.deleteMessage())
                            delete(event.getMessage());
                    });
                    return;
                } else {
                    for (String alias : cmd.getAliases()) {
                        if (alias.equalsIgnoreCase(command)) {
                            if (cmd.getType() == CommandType.HIDDEN) {
                                if (!cmd.getPermissions(event.getChannel()).isCreator(event.getAuthor())) {
                                    return;
                                }
                            }
                            if (UpdateCommand.UPDATING.get()) {
                                MessageUtils.sendMessage("**Currently updating!**", event.getChannel());
                                return;
                            }
                            FlareBot.LOGGER.info(
                                    "Dispatching command '" + cmd.getCommand() + "' " + Arrays.toString(args) + " in " + event.getChannel() + "! Sender: " +
                                            event.getAuthor().getName() + '#' + event.getAuthor().getDiscriminator());
                            if (handleMissingPermission(cmd, event))
                                return;
                            COMMAND_COUNTER.computeIfAbsent(event.getChannel().getGuild().getId(),
                                    g -> new AtomicInteger()).incrementAndGet();
                            String[] finalArgs = args;
                            CACHED_POOL.submit(() -> {
                                FlareBot.LOGGER.info(
                                        "Dispatching command '" + cmd.getCommand() + "' " + Arrays.toString(finalArgs) + " in " + event.getChannel() + "! Sender: " +
                                                event.getAuthor().getName() + '#' + event.getAuthor().getDiscriminator());
                                try {
                                    cmd.onCommand(event.getAuthor(), event.getChannel(), event.getMessage(), finalArgs, event.getMember());
                                } catch (Exception ex) {
                                    FlareBot.LOGGER.error("Exception in guild " + "!\n" + '\'' + cmd.getCommand() + "' "
                                            + Arrays.toString(finalArgs) + " in " + event.getChannel() + "! Sender: " +
                                            event.getAuthor().getName() + '#' + event.getAuthor().getDiscriminator(), ex);
                                    MessageUtils.sendException("**There was an internal error trying to execute your command**", ex, event.getChannel());
                                }
                                if (cmd.deleteMessage())
                                    delete(event.getMessage());
                            });
                            return;
                        }
                    }
                }
            }
        } else {
            if (FlareBot.getPrefixes().get(getGuildId(event)) != FlareBot.COMMAND_CHAR
                    && !event.getAuthor().isBot()) {
                if (event.getMessage().getRawContent().startsWith("_prefix")) {
                    MessageUtils.sendMessage(MessageUtils.getEmbed(event.getAuthor())
                            .setDescription("The server prefix is `" + FlareBot.getPrefixes().get(getGuildId(event)) + "`"), event.getChannel());
                }
            }
        }
    }

    @Override
    public void onUserOnlineStatusUpdate(UserOnlineStatusUpdateEvent event) {
        if (event.getPreviousOnlineStatus() == OnlineStatus.OFFLINE) {
            flareBot.getPlayerCache(event.getUser().getId()).setLastSeen(LocalDateTime.now());
        }
    }

    private boolean handleMissingPermission(Command cmd, GenericGuildMessageEvent e) {
        if (cmd.getPermission() != null && cmd.getPermission().length() > 0) {
            if (!cmd.getPermissions(e.getChannel()).hasPermission(e.getMember(), cmd.getPermission())) {
                Message msg = MessageUtils.sendErrorMessage(MessageUtils.getEmbed(e.getAuthor())
                        .setDescription("You are missing the permission ``"
                                + cmd.getPermission() + "`` which is required for use of this command!"), e.getChannel());
                delete(e.getMessage());
                new FlarebotTask("Delete message " + msg.getChannel().toString()) {
                    @Override
                    public void run() {
                        delete(msg);
                    }
                }.delay(5000);
                return true;
            }
        }
        return false;
    }

    private void delete(Message message) {
        if (message.getTextChannel().getGuild().getSelfMember()
                .getPermissions(message.getTextChannel()).contains(Permission.MESSAGE_MANAGE))
            message.deleteMessage().queue();
    }

    private String getGuildId(GenericGuildMessageEvent e) {
        return e.getChannel().getGuild() != null ? e.getChannel().getGuild().getId() : null;
    }
}
