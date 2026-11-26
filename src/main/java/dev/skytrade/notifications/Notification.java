package dev.skytrade.notifications;

/**
 * @param suggestCommand optional command (e.g. {@code /viewauction <uuid>}) that a chat alert offers as a
 *                       click: clicking only types it into the chat box, the player still presses Enter
 */
public record Notification(long timestamp, Level level, String title, String message, String suggestCommand) {
    public enum Level { INFO, SUCCESS, WARNING, ERROR }

    public Notification(long timestamp, Level level, String title, String message) {
        this(timestamp, level, title, message, null);
    }
}
