package nest.oudiabridge.command;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends unexpected command failures to the command source with an IDE-like,
 * syntax-coloured Java stack trace.
 */
public final class CommandErrorReporter {
    private static final Pattern STACK_FRAME =
            Pattern.compile("^(\\s*at\\s+)([^ (]+)(\\((.*)\\))$");

    private CommandErrorReporter() {
    }

    public static void report(CommandSourceStack source, String title, Throwable throwable) {
        if (throwable == null) {
            source.sendFailure(Component.literal(title + ": unknown error")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        source.sendFailure(Component.literal(title)
                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));

        StringWriter buffer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(buffer));
        String[] lines = buffer.toString().split("\\R");

        for (int i = 0; i < lines.length; i++) {
            Component formatted = formatLine(lines[i], i == 0);
            source.sendSuccess(() -> formatted, false);
        }
    }

    private static Component formatLine(String line, boolean firstLine) {
        if (firstLine || line.startsWith("Caused by:")) {
            return Component.literal(line).withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
        }
        if (line.startsWith("Suppressed:")) {
            return Component.literal(line).withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD);
        }

        Matcher matcher = STACK_FRAME.matcher(line);
        if (matcher.matches()) {
            return Component.literal(matcher.group(1)).withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal(matcher.group(2)).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal("(").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(matcher.group(4)).withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal(")").withStyle(ChatFormatting.DARK_GRAY));
        }

        if (line.trim().matches("\\.\\.\\. \\d+ more")) {
            return Component.literal(line).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC);
        }
        return Component.literal(line).withStyle(ChatFormatting.GRAY);
    }
}
